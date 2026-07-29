# Public Chat Workflow — Essential Guide

Quick orientation for the `com.semantyca.jesoos.service.chat` package. Read this before
diving into individual files; it captures the runtime flow, auth model, and each
sub-workflow (sign-in, playing songs, upload, ads, memory, events) so you don't
have to reverse-engineer them.

> Prompt text lives in `src/main/resources/prompts/mainPrompt.hbs` (main agent).
> Behaviour rules are split between the
> **prompt** (what the LLM is told to do) and **code** (what is actually enforced).
> Tool gating and auth state are enforced in code — never trust the prompt alone.
>
> Internal Mixpla Ask chat is a **separate** package (`service/ask`) — see `ASK_WORKFLOW.md`.
> Do not add Ask branches here.

---

## 1. Request flow (one user turn)

```
PublicChatController (WS)
  → ChatService.generateBotResponse(msg, ..., slug, user)
      → PublicChatIntentRouter.decide(connectionId, msg, slug)
          • Ad  session active  → AdContinuationHandler   (deterministic, no LLM)
          • else                → generateBotResponseCore
      → generateBotResponseCore
          • BrandStaticData (cached per brand slug) → renders mainPrompt.hbs
          • loads history (+ optional summary)
          • ChatAgent.run(state)
```

`ChatAgent` is a **langgraph4j** graph: `loadContext → llm ⇄ tool` looping up to
`MAX_TOOL_ITERATIONS = 8`. `loadContext` injects the live queue and listener profile.
`llm` calls the model; if it returns a tool call → `tool` node runs it, appends the
result to history, loops back; otherwise the text is the final answer.

**Models / provider:** single provider (Anthropic) via `BrandLlmProviderResolver`.
- iteration 0 = `MAIN_CHAT` → Claude Sonnet 4.5
- iterations 1+ and the intent classifier = Haiku 4.5
The `brandSlug` params on the resolver/router are vestigial (per-brand selection was
removed with OpenAI) but kept so it can be reintroduced without touching call sites.

**Prompt caching:** `mainPrompt.hbs` static body is sent as a cached (`cache_control`)
block; per-request `liveContext` + `listenerContext` go into a trailing volatile block
(`ChatAgent.buildVolatileContext`). Do not move dynamic data back into the cached body.

---

## 2. Authentication (the single most important gate)

- `user.getId() == 0` ⇒ **anonymous**. Otherwise authenticated.
- Auth state is server-set; the prompt receives `{{isAuthenticated}}`. For anonymous
  users the prompt is **physically truncated** at `!! AUTHENTICATED ONLY` and the tool
  set is restricted in `ChatAgent.getToolsForUser`. Security does **not** rely on the
  model obeying.

**Tools by auth state** (`ChatAgent.getToolsForUser`):
- Anonymous: `inform_owner`, `start_auth`, `verify_code`.
- Authenticated: all of the above-minus-auth plus `search_brand_sound_fragments`,
  `get_brand_catalog_summary`, `listener_data`, `find_community_member`, `upload_song`,
  `assess_track`, `import_from_suno`, `play_song_with_intro`, `create_ad` (only when
  `adEnabled`), `manage_events`, `send_ui_command`, `logoff`.

**Sign-in flow** (anonymous asks for any gated feature):
1. Ask for email (one line, nothing else).
2. User types email → `start_auth` (sends a code).
3. Tell them the code was sent; ask for it.
4. User types the code → `verify_code`.
5. On success the `tool` node upgrades the session: sets `USER_ID`, persists connection
   history to the user, migrates anonymous DB records (`ChatAuthService`), upgrades the
   WS session, and emits a deferred `session_token`. Then **immediately perform the task
   that triggered sign-in** (don't ask "shall I?").

Never accept an email typed in chat as proof of auth; never trigger auth when already
authenticated; `logoff` signs out and clears history.

---

## 3. Tools (dispatch in `ChatAgent.executeToolCall`)

| Tool | Purpose |
|---|---|
| `start_auth` / `verify_code` / `logoff` | sign-in / sign-out |
| `search_brand_sound_fragments` | search catalog (capped at 10 results) |
| `get_brand_catalog_summary` | full catalog overview (artists, genres, counts) |
| `play_song_with_intro` | queue a song with a spoken TTS intro |
| `upload_song` | add an artist's track to the catalog |
| `import_from_suno` | fetch an artist's track from a Suno link into the temp dir (returns `temp_filename`) |
| `assess_track` | run spectra analysis on the temp file **before** `upload_song`: bpm, key/scale, moods, top_genres, danceability, loudness, duration, an `is_music` verdict and a weak AI-generation check. `is_music == false` ⇒ do not save |
| `send_ui_command` | e.g. `show_upload_button` — reveals the upload UI |
| `listener_data` | get/set listener memory; `add_label` (e.g. artist) |
| `find_community_member` | warm recognition only (privacy-limited) |
| `inform_owner` | email the station owner |
| `manage_events` | list / upsert station events |
| `create_ad` | start the interactive ad flow |

**Tool etiquette:** call tools silently and immediately — no "let me check…" narration
before a call. Live queue / "what's playing" is already in context (injected by `loadContextNode`) — answer from it, no tool needed.

---

## 4. Playing songs — 3 sequential turns (never skip)

1. **TURN 1 – search only:** `search_brand_sound_fragments`, show a numbered list, stop.
2. **TURN 2 – confirm + ask shout-out (mandatory):** acknowledge the pick, then ALWAYS
   ask whether they want something said on air (dedication / hello). Wait for reply.
3. **TURN 3 – queue:** `play_song_with_intro` (intro in DJ language). Then give a
   **clear, confirmed** message that the song is queued and WILL play. The DJ only adds
   to the queue — never claim it is "playing now".

This 3-turn dance is enforced by the prompt, not code, so the LLM must track its place
from history. (A future refactor may promote it to a sub-graph like Ad.)

---

## 5. Artist song upload

Preconditions, in order:
1. Station `submissionPolicy` must not be `NOT_ALLOWED`.
2. User must be authenticated **and** have the `artist` label
   (`listener_data action=get` → labels). If missing, only proceed if they explicitly
   ask to become an artist → `listener_data add_label label_identifier=artist`.
3. `send_ui_command(show_upload_button)` MUST be called before the user can attach a
   file. The upload button only exists in the UI after this call — never invent it.
4. Client sends `I uploaded a file: <filename>`; use that exact basename as
   `temp_filename`. Once `temp_filename + title + artist + genre` are known, call
   `upload_song` immediately. Don't claim success until it returns `ok:true`.

**Suno link path (alternative to steps 3–4):** if the artist shares a Suno link instead of a
file, `import_from_suno` (authenticated + artist label) resolves the song id from the URL,
downloads `https://cdn1.suno.ai/<id>.mp3` (`SunoImportService`) into the **same** temp dir the
upload endpoint uses, and returns a `temp_filename`. No `show_upload_button` is needed — the
server pulls the file directly. In the same call it also scrapes the public song page
(`https://suno.com/song/<id>`, browser UA) and parses the embedded Next.js RSC payload for
`title`, `artist` (`display_name`), `handle`, `genre_tags` (`display_tags`), `image_url` and
`duration` — returned as `SunoTrackMetadata`. Scraping is **best-effort**: any failure degrades to
empty fields and the download still succeeds (`SunoImportService.fetchMetadata` recovers to
`SunoTrackMetadata.empty`). The flow then rejoins the normal path but as **confirm, not collect**:
the DJ maps `genre_tags` onto the station's controlled genres (`{{musicMetadata}}` /
`AiHelperService.resolveGenreNamesToIds` matches by name, dropping non-matches), shows the artist
the resolved title/artist/genre, and asks them to confirm or correct **before** `upload_song`. Only
fields that came back empty are asked for from scratch. Note `upload_song` still takes only
`genre_names` (no labels field), so `display_tags` are a mapping hint, not a passthrough value.

---

## 6. Advertisements (authenticated only)

State machine in `AdSessionManager` + `AdGraph`. Gated per-brand by
`Brand.chatFeatureFlags` (extensible flag map, see `CreateAdToolHandler.resolveAdType`):
- `CREATE_AD` (default **on**) → personal/classified ads (bicycle, car, job, ...).
- `STORE_PROMO` (default **off**) → store/business promotions (discounts, sales).
- Neither flag on → `create_ad` tool withheld entirely (`ChatAgent.getToolsForUser`) and
  `mainPrompt.hbs` tells the DJ to decline conversationally (`{{adEnabled}}`); the tool
  handler also rejects defensively if it's still invoked.

`create_ad` starts the interactive flow and returns a `firstQuestion` (wording depends on
which type(s) are enabled) — say it verbatim, don't pre-collect details or explain the
process first.

**Ad type resolution:**
- Only one flag enabled → that brand's session is fixed to that type from the start
  (`CLASSIFIED` or `STORE_PROMO`), no classification needed.
- Both flags enabled → type is ambiguous at session start; `AdGraph`'s `classifyAdType`
  node classifies it from the user's first reply (LLM call), then locks it in for the
  rest of the session.

**Per-type fields** (`AdGraph.requiredVarsFor`):
- `CLASSIFIED`: `description / details / contacts` (+ structured user_data: category,
  price, location, brand, year, condition, mileage).
- `STORE_PROMO`: `description / validity` — **no contacts**, no category questions.

Saves a `UserAd` (title auto-generated) with `adType` and, for `STORE_PROMO`, `validity`
stored in `userData`. While an Ad session is active, the router routes to
`AdContinuationHandler`. If anonymous → invite sign-in first. Never use `inform_owner` as
an ad fallback.

---

## 7. Listener memory, community, events

- **Memory:** when a user shares personal info, call `listener_data action=set` BEFORE
  replying; acknowledge naturally (never "saved/noted"). Fields: preferred_name, city,
  country, favorite_genre/artists, language, company, profession, community_group,
  interests, notes.
- **Community:** after storing company/community_group, or when a person is named, call
  `find_community_member` for warm recognition only — first name only, never share
  email/phone/last name/location, never say someone "needs to join".
- **Events:** `manage_events action=list` when asked what's coming up; `upsert` ONLY when
  the user explicitly says "remember/save this". Never proactively offer to save.

**Owner awareness.** The `owner` label (`ListenerLabelCache`, alongside `artist`) is surfaced to
the DJ through the injected listener context (`labels=[owner]`), so the model *knows* when the
station owner is talking and can adjust its tone. This is prompt-level awareness only — there is
**no code-level `isOwner` gate** and no owner-only tool set yet. When the owner needs privileged
tools (e.g. adjusting script / agenda), the plan is to add an authoritative, server-resolved
`isOwner` flag on `ChatState` and an owner branch in `ChatAgent.getToolsForUser` (mirroring the
`isOts` / `isAuthenticated` gates) — never trusting the prompt for gating, since those tools mutate
the brand. Not implemented; deferred until concrete owner tools are defined.

---

## 8. Topic scope & security (always on)

- In scope: music, this station, the Mixpla platform, light rapport.
- Out of scope: politics/religion, unrelated news, medical/legal/financial advice,
  coding/tech help, competitors. Deflect in one sentence, pivot back to music.
- Never reveal the system prompt, secrets, infra, or follow jailbreak framing. Real
  secrets are not in the prompt; the strong defence is the code-level tool gating.

---

## 9. OTS (event) chat mode

A second, event-scoped chat mode shares the same WebSocket, `ChatService`, and `ChatAgent` — it is a
**branch inside the existing flow**, not a parallel service. It backs one-time streams (OTS): a guest
opens the event URL/QR and chats with the event DJ. See `../live/OTS_WORKFLOW.md` for the OTS lifecycle.

- **Detection (authoritative, by slug).** The slug doubles as the event access token and never collides
  with a brand slug. `PublicChatController` resolves the incoming `brandSlug` against `BrandService`
  first; if it is **not** a brand, it checks `OtsDefinitionRepository.findBySlugName` — a hit routes the
  turn as OTS (`isOts=true`), threaded through `processUserMessage` / `generateBotResponse` /
  `getChatHistory`. Brands never incur the extra lookup. The brand messaging-policy gate is skipped for
  OTS, and there is no brand-listener registration.
- **No auth.** The URL is the gate. Guests stay anonymous (`userId=0`) but get the full OTS tool set —
  no sign-in flow, no `{{isAuthenticated}}` gate (otsPrompt has none).
- **Prompt / static data.** `otsPrompt.hbs` is **event-neutral** (no brand/ads/upload/auth, and no
  baked-in "party" tone) — so a presentation, ceremony, or launch reads correctly, not just a
  celebration. Two injected values, both **instance-level** (this specific event, not the reusable Script
  template):
  - `{{eventName}}` — the definition/instance name (`stream.getLocalizedName()`), **not** `Script.getName()`
    (which is the template's catalog name).
  - `{{eventContext}}` — how to host THIS event. Sourced from the dedicated instance field
    `OtsDefinition.chatContext` (2next 1.4.113, column `mixpla__ots_definitions.chat_context`), carried on
    the live `OneTimeStream`. **Not** `Script.getDescription()` — that is UI *selection* copy for picking
    the template ("handles a birthday, plays 3h, stages…"), not hosting guidance.

  DJ name/voice/languages come from the OTS agent. `ChatService.buildOtsStaticData` prefers the live
  `OneTimeStreamPool` stream (agent already resolved) and falls back to the definition when the OTS isn't
  started yet. Cached in `otsStaticCache` (parallel to `brandStaticCache`), dropped on teardown.
- **Tools (anonymous-allowed).** `ChatAgent.getToolsForOts` exposes only `search_brand_sound_fragments`
  and `play_song_with_intro`. `executeToolCall` routes these to OTS handlers when `state.isOts()`:
  - `SearchOtsSoundFragmentsToolHandler` — searches the OTS's `SongSourceScope`: master-brand catalog
    (brand-scoped) or the owner's catalog (owner-scoped synthetic brand, via
    `AiHelperService.searchOwnerSoundFragmentsForAi`).
  - `PlaySongForOtsToolHandler` — resolves the stream from `OneTimeStreamPool` (not `BrandPool`) and
    routes the injected song on the **OTS slug** (`brandSlug` + `otsSlugName`), per OTS_WORKFLOW §3.
- **Ephemeral, persist-then-purge.** Messages persist under `ChatType.OTS` tagged by the OTS slug (so
  refresh survives and the brand summary cron — which filters `chat_type='PUBLIC'` — ignores them; OTS
  chat is never summarized). On teardown, `ChatService.purgeOtsChat(slug)` drops the static cache and
  hard-deletes the rows (`ChatRepository.deleteOtsMessages`). Called from both
  `OtsStreamScheduler.checkOtsFinished` (natural completion) and `CommandService.stopOts` (explicit stop).

---

## 10. Chat summarization → on-air context (`ChatSummaryService`)

Summarization is not archival — it is how the **air DJ learns what happened in chat**. Chat and air
are one persona to the listener, so a summary must let the DJ sound like the same person who was
just talking to them ("thanks Mira for reaching me in chat — so we have someone from Michigan
tonight…"). Two summary types, both LLM-generated:

- **BRAND** (`chat_type='PUBLIC'`, brand-wide) — consumed on air, see RADIO_WORKFLOW §4f.
- **USER** (per listener/chatType) — consumed by chat itself for conversation continuity
  (`ChatService` → `getLatestUserSummary`). Keeps the last 5 messages unsummarized.

**Listener knowledge is part of the input.** `buildListenerProfiles` resolves the `Listener` behind
every distinct speaker in the batch and passes a profile block alongside the messages: the whole
`userData` map (free-form key/values the chat bot collected *during* the conversation — city,
country, interests, profession, …), the preferred/localized name, and labels resolved via
`ListenerLabelCache` (`artist`, `owner`). Without this the summary could only say *what* was said,
never *who* said it — which is what made chat and air feel like two different people.

Both prompts therefore **preserve names and identifying detail** and are told never to invent one.
(The USER prompt previously forbade names outright and had to be inverted — anonymised summaries
destroy the single-persona illusion.)

**Roles are labelled in the transcript.** `formatMessagesForSummary` prefixes each line with
`HOST (you)` (`MessageType.BOT`) or `LISTENER` (`MessageType.USER`); other types are skipped, and
listener profiles resolve from `USER` messages only. The bot posts under the DJ persona name, so an
unlabelled transcript made the host read as a listener — summaries then described the DJ as
"a knowledgeable user" and would have had the DJ greet itself on air.

**Never put private data into a summary.** This text is spoken on a public broadcast. The BRAND
prompt forbids phone numbers, emails, addresses, full names, prices and payment details even when a
listener typed them in chat (an arranged ad is mentioned as an arranged ad, never read back).
Handle-style names with digits and anonymous users are skipped — they cannot be addressed naturally
on air.

**Triggers** (`@Scheduled(every="5m")`, per active brand):
- `count >= BRAND_SUMMARY_THRESHOLD` (20), **or**
- oldest unsummarized message ≥ `BRAND_SUMMARY_MAX_TAIL_AGE_MINUTES` (10).

The age trigger matters: on a count-only rule a quiet station never crosses 20 and the DJ stays
blind indefinitely.

**Brands are summarized sequentially, never fanned out** (`transformToUniAndConcatenate`). Fanning
out means every brand due in the same tick fires a simultaneous LLM call; the provider rate-limits
the burst and whole batches fail at the same instant. One brand's failure is recovered so it cannot
abort the rest of the sequence.

**Failure is loud and never persisted** (RADIO_WORKFLOW §6 rule 5). A failed or blank generation
must not be saved: a placeholder row would mark its messages summarized — losing them permanently —
and hand the DJ an error string as on-air context. The batch stays unsummarized and is retried next
tick. Metrics for metriq:

| code | severity | payload |
|---|---|---|
| `chat_summary_failed` | `ERROR` | summaryType, messageCount, periodStart/End, provider, model, errorType, error |
| `chat_summary_created` | `INFORMATION` | summaryType, messageCount, summaryLength, periodStart/End |

`provider`/`model` are in the payload deliberately — without them a rate limit is indistinguishable
from a bad batch in metriq.

**Freshness & single use** — both guard against the DJ voicing chat that is no longer real:
- `getBrandChatContext` returns `BrandChatContext(summaryId, summary, fresh)`; `fresh` is false past
  `BRAND_SUMMARY_MAX_AGE_MINUTES` (60), measured from `period_end`. A dead conversation must never be
  voiced as if it were happening now.
- `aired_at` (nullable, `mixpla__chat_summary`) — `getLatestBrandSummary` returns only **un-aired**
  BRAND summaries, and `DraftFactory` calls `markBrandSummaryAired` when it hands one to the script.
  So each chat moment is offered to air **at most once**; no repeated thank-yous.
  *Caveat:* marking happens at draft **render**, not at emission — a Groovy script that receives a
  summary and randomizes it away still consumes it. Do not add a probability gate around
  `chatSummary` in draft templates; `aired_at` already provides the novelty, and a coin flip on top
  silently discards summaries.

Retention: `deleteOldSummarizedMessages` nightly at 03:00, `MESSAGE_RETENTION_DAYS`=7. OTS chat is
never summarized (§9).

---

## Key files

| Area | File |
|---|---|
| Orchestration | `ChatService`, `ChatAgent`, `PublicChatIntentRouter` |
| Summarization | `maintenance/ChatSummaryService`, `repository/ChatSummaryRepository`, `model/chat/ChatSummary` |
| OTS chat | `otsPrompt.hbs`, `tools/SearchOtsSoundFragmentsToolHandler`, `tools/PlaySongForOtsToolHandler`, `ChatService.buildOtsStaticData`/`purgeOtsChat` |
| Auth | `ChatAuthService`, `tools/auth/*` |
| Provider / LLM | `llm/BrandLlmProviderResolver`, `llm/AnthropicChatLlmClient`, `llm/LlmRequest` |
| Ads | `ad/AdSessionManager`, `ad/AdGraph`, `ad/AdContinuationHandler` |
| Tools | `tools/*` |
| Prompts | `resources/prompts/mainPrompt.hbs` |
