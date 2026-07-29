---
type: Workflow
title: Listener chat internals
description: The langgraph4j agent behind the public chat — request routing, model selection, prompt caching, auth gating, the full tool set, upload paths, topic scope and OTS mode.
tags: [chat, agent, langgraph4j, tools, auth, anthropic, prompt-caching, ots, suno, upload]
audience: [developer]
---

# Request flow

```
PublicChatController (WebSocket)
→ ChatService.generateBotResponse(msg, …, slug, user)
→ PublicChatIntentRouter.decide(connectionId, msg, slug)
     an active ad session → AdContinuationHandler, deterministic, no LLM call
     otherwise → generateBotResponseCore
→ BrandStaticData (cached per brand slug) renders mainPrompt.hbs
→ history (plus an optional summary) is loaded
→ ChatAgent.run(state)
```

`ChatAgent` is a langgraph4j graph: `loadContext → llm ⇄ tool`, looping up to
`MAX_TOOL_ITERATIONS = 8`. `loadContext` injects the live queue and the listener profile. The `llm` node
calls the model; a tool call routes to the `tool` node, whose result is appended to history before
looping back, and any other response is the final answer.

Prompt text lives in `resources/prompts/mainPrompt.hbs`. Behaviour is split between the prompt — what
the model is told — and the code, which is what is actually enforced. Tool gating and auth state are
enforced in code; the prompt is never trusted alone.

# Models and caching

There is a single provider, Anthropic, behind `BrandLlmProviderResolver`. Iteration 0 is `MAIN_CHAT` on
Claude Sonnet 4.5; iterations 1 and up, and the intent classifier, run on Haiku 4.5. The `brandSlug`
parameters on the resolver and router are vestigial — per-brand model selection was removed together with
OpenAI — but they are kept so it can be reintroduced without touching call sites.

The static body of `mainPrompt.hbs` is sent as a `cache_control` cached block, and the per-request
`liveContext` and `listenerContext` go into a trailing volatile block built by
`ChatAgent.buildVolatileContext`. Dynamic data must not move back into the cached body.

# Authentication gating

A caller with `user.getId() == 0` is anonymous; anyone else is authenticated. Auth state is server-set and
the prompt only receives `{{isAuthenticated}}`. For anonymous callers the prompt is **physically
truncated** at the `!! AUTHENTICATED ONLY` marker and the tool set is restricted in
`ChatAgent.getToolsForUser`. Security does not depend on the model obeying.

| State | Tools |
|---|---|
| Anonymous | `inform_owner`, `start_auth`, `verify_code` |
| Authenticated | the above minus auth, plus `search_brand_sound_fragments`, `get_brand_catalog_summary`, `listener_data`, `find_community_member`, `upload_song`, `assess_track`, `import_from_suno`, `play_song_with_intro`, `create_ad` (only when `adEnabled`), `manage_events`, `send_ui_command`, `logoff` |

The sign-in flow: ask for the email on one line and nothing else; the typed address goes to `start_auth`,
which sends a code; say the code was sent and ask for it; the typed code goes to `verify_code`.

On success the `tool` node upgrades the session — it sets `USER_ID`, persists the connection's history to
the user, migrates anonymous database records through `ChatAuthService`, upgrades the WebSocket session,
and emits a deferred `session_token`. It then **immediately performs the task that triggered sign-in**
rather than asking "shall I?".

An email typed in chat is never proof of authentication, auth is never triggered for an
already-authenticated caller, and `logoff` signs out and clears history.

# Tools

Dispatch lives in `ChatAgent.executeToolCall`.

| Tool | Purpose |
|---|---|
| `start_auth` / `verify_code` / `logoff` | sign-in and sign-out |
| `search_brand_sound_fragments` | search the catalog, capped at 10 results |
| `get_brand_catalog_summary` | full catalog overview — artists, genres, counts |
| `play_song_with_intro` | queue a song with a spoken TTS intro |
| `upload_song` | add an artist's track to the catalog |
| `import_from_suno` | fetch an artist's track from a Suno link into the temp dir, returning `temp_filename` |
| `assess_track` | run spectra analysis on the temp file **before** `upload_song` — bpm, key and scale, moods, top genres, danceability, loudness, duration, an `is_music` verdict and a weak AI-generation check. `is_music == false` means do not save |
| `send_ui_command` | for example `show_upload_button`, which reveals the upload UI |
| `listener_data` | get and set listener memory, and `add_label` for the `artist` label |
| `find_community_member` | warm recognition only, privacy-limited |
| `inform_owner` | email the station owner |
| `manage_events` | list or upsert station events |
| `create_ad` | start the interactive ad flow |

Tool etiquette: call tools silently and immediately, with no "let me check…" narration first. The live
queue and what is playing are already injected by `loadContextNode`, so those are answered from context
without a tool call.

# Playing a song — three sequential turns

Never skipped:

1. **Search only.** `search_brand_sound_fragments`, show a numbered list, stop.
2. **Confirm and ask for the shout-out, mandatory.** Acknowledge the pick, then always ask whether they
   want something said on air — a dedication or a hello — and wait for the reply.
3. **Queue.** `play_song_with_intro` with the intro in the DJ language, then a clear confirmation that
   the song is queued and *will* play. The DJ only adds to the queue and must never claim a track is
   "playing now".

This dance is enforced by the prompt rather than by code, so the model tracks its place from history. A
future refactor may promote it to a sub-graph like the ad flow.

# Artist upload preconditions, in order

1. The station's `submissionPolicy` must not be `NOT_ALLOWED`.
2. The caller must be authenticated **and** carry the `artist` label (`listener_data action=get` →
   labels). If it is missing, proceed only if they explicitly ask to become an artist, then
   `listener_data add_label label_identifier=artist`.
3. `send_ui_command(show_upload_button)` must be called before the user can attach a file — the button
   only exists in the UI after that call, and must never be invented.
4. The client sends `I uploaded a file: <filename>`; that exact basename is the `temp_filename`. Once
   `temp_filename`, title, artist and genre are known, call `upload_song` immediately, and do not claim
   success until it returns `ok:true`.

# The Suno path

An alternative to steps 3 and 4. Given a Suno link from an authenticated artist, `import_from_suno`
resolves the song id from the URL and `SunoImportService` downloads `https://cdn1.suno.ai/<id>.mp3` into
the **same** temp directory the upload endpoint uses, returning a `temp_filename`. No
`show_upload_button` is needed, because the server pulls the file directly.

The same call scrapes the public song page `https://suno.com/song/<id>` with a browser user agent and
parses the embedded Next.js RSC payload for `title`, `artist` (`display_name`), `handle`, `genre_tags`
(`display_tags`), `image_url` and `duration`, returned as `SunoTrackMetadata`. Scraping is
**best-effort**: any failure degrades to empty fields while the download still succeeds
(`SunoImportService.fetchMetadata` recovers to `SunoTrackMetadata.empty`).

The flow then rejoins the normal path, but as **confirm rather than collect**: the DJ maps `genre_tags`
onto the station's controlled genres (`{{musicMetadata}}` and `AiHelperService.resolveGenreNamesToIds`
match by name, dropping non-matches), shows the artist the resolved title, artist and genre, and asks
them to confirm or correct **before** `upload_song`. Only fields that came back empty are asked for from
scratch. `upload_song` still takes only `genre_names` and has no labels field, so `display_tags` are a
mapping hint rather than a passthrough value.

# Listener memory, community and events

When a listener shares personal information, `listener_data action=set` is called **before** replying,
and the acknowledgement is natural rather than "saved" or "noted". Fields are preferred name, city,
country, favourite genre and artists, language, company, profession, community group, interests and
notes.

After storing a company or community group, or when a person is named, `find_community_member` provides
warm recognition only: first name only, never an email, phone, last name or location, and never a
suggestion that someone "needs to join".

`manage_events action=list` answers what is coming up, and `upsert` runs only when the listener
explicitly says to remember or save something. It is never proactively offered.

# Owner awareness

The `owner` label, held in `ListenerLabelCache` alongside `artist`, is surfaced to the DJ through the
injected listener context as `labels=[owner]`, so the model knows when the station owner is talking and
can adjust tone. This is prompt-level awareness only — there is **no** code-level `isOwner` gate and no
owner-only tool set.

When the owner needs privileged tools, such as adjusting the script or agenda, the plan is an
authoritative server-resolved `isOwner` flag on `ChatState` plus an owner branch in
`ChatAgent.getToolsForUser`, mirroring the `isOts` and `isAuthenticated` gates — never trusting the
prompt for gating, since those tools mutate the brand. Not implemented; deferred until concrete owner
tools are defined.

# Topic scope and security

In scope: music, this station, the Mixpla platform and light rapport. Out of scope: politics and
religion, unrelated news, medical, legal or financial advice, coding and technical help, and competitors
— deflected in one sentence, pivoting back to music.

The system prompt, secrets and infrastructure are never revealed and jailbreak framing is never
followed. Real secrets are not in the prompt anyway; the strong defence is the code-level tool gating.

# OTS mode

A second, event-scoped chat mode shares the same WebSocket, `ChatService` and `ChatAgent`. It is a
branch inside the existing flow, not a parallel service.

**Detection is authoritative and by slug.** The slug doubles as the event access token and never collides
with a brand slug. `PublicChatController` resolves the incoming `brandSlug` against `BrandService` first;
if it is not a brand it checks `OtsDefinitionRepository.findBySlugName`, and a hit routes the turn as OTS
(`isOts = true`), threaded through `processUserMessage`, `generateBotResponse` and `getChatHistory`.
Brands never incur the extra lookup. The brand messaging-policy gate is skipped for OTS, and there is no
brand-listener registration.

**No auth.** The URL is the gate. Guests stay anonymous (`userId = 0`) but get the full OTS tool set, with
no sign-in flow and no `{{isAuthenticated}}` gate — `otsPrompt.hbs` has none.

**Prompt and static data.** `otsPrompt.hbs` is event-neutral: no brand, ads, upload or auth, and no
baked-in "party" tone, so a presentation, ceremony or launch reads correctly rather than only a
celebration. Two injected values, both instance-level rather than from the reusable Script template:

* `{{eventName}}` — the definition or instance name (`stream.getLocalizedName()`), **not**
  `Script.getName()`, which is the template's catalog name.
* `{{eventContext}}` — how to host *this* event, from the dedicated instance field
  `OtsDefinition.chatContext` (2next 1.4.113, column `mixpla__ots_definitions.chat_context`), carried on
  the live `OneTimeStream`. **Not** `Script.getDescription()`, which is UI selection copy for picking the
  template.

DJ name, voice and languages come from the OTS agent. `ChatService.buildOtsStaticData` prefers the live
`OneTimeStreamPool` stream, where the agent is already resolved, and falls back to the definition when the
OTS has not started yet. It is cached in `otsStaticCache`, parallel to `brandStaticCache`, and dropped on
teardown.

**Tools, anonymous-allowed.** `ChatAgent.getToolsForOts` exposes only `search_brand_sound_fragments` and
`play_song_with_intro`, and `executeToolCall` routes them to OTS handlers when `state.isOts()`:

* `SearchOtsSoundFragmentsToolHandler` searches the OTS's `SongSourceScope` — the master-brand catalog
  when brand-scoped, or the owner's catalog via `AiHelperService.searchOwnerSoundFragmentsForAi` when
  owner-scoped.
* `PlaySongForOtsToolHandler` resolves the stream from `OneTimeStreamPool` rather than `BrandPool` and
  routes the injected song on the **OTS slug** (`brandSlug` plus `otsSlugName`).

**Ephemeral, persist-then-purge.** Messages persist under `ChatType.OTS` tagged by the OTS slug, so a
refresh survives and the brand summary cron — which filters `chat_type='PUBLIC'` — ignores them; OTS chat
is never summarized. On teardown `ChatService.purgeOtsChat(slug)` drops the static cache and hard-deletes
the rows through `ChatRepository.deleteOtsMessages`, called from both
`OtsStreamScheduler.checkOtsFinished` on natural completion and `CommandService.stopOts` on an explicit
stop.

# Key files

| Area | File |
|---|---|
| Orchestration | `ChatService`, `ChatAgent`, `PublicChatIntentRouter` |
| Summarization | `maintenance/ChatSummaryService`, `repository/ChatSummaryRepository`, `model/chat/ChatSummary` |
| OTS chat | `otsPrompt.hbs`, `tools/SearchOtsSoundFragmentsToolHandler`, `tools/PlaySongForOtsToolHandler`, `ChatService.buildOtsStaticData` / `purgeOtsChat` |
| Auth | `ChatAuthService`, `tools/auth/*` |
| Provider and LLM | `llm/BrandLlmProviderResolver`, `llm/AnthropicChatLlmClient`, `llm/LlmRequest` |
| Ads | `ad/AdSessionManager`, `ad/AdGraph`, `ad/AdContinuationHandler` |
| Tools | `tools/*` |
| Prompts | `resources/prompts/mainPrompt.hbs` |

The internal Mixpla Ask chat is a separate package (`service/ask`) — Ask branches never go here.
