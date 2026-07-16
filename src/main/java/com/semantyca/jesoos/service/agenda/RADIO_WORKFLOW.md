# Radio Workflow — Essential Guide

> **Scope: the continuous brand radio only.** A radio station *is* a brand's station — always
> brand-scoped, infinite by nature, started manually. For the one-time, ephemeral, brand-**or**
> user-scoped personal stream (OTS), see `../live/OTS_WORKFLOW.md` instead — it reuses the
> build/timeline/emitter primitives documented here but has its own routing identity and
> lifecycle. Coherent with aivox's own `RADIO_SCOPE.md` / `OTS_SCOPE.md` split — keep the two
> pairs of docs in sync.

---

## 1. Glossary (learn these first)

| Term | Meaning |
|---|---|
| **Brand** | A station. Owns a timezone, one or more `Script`s, and a **mandatory** `AiAgent` (every brand has one). Root of everything. |
| **Script** | An ordered container of `Scene`s for a brand. The agenda is built from one script. |
| **Scene** | An authored programming block: a `PlaylistRequest` (how to source songs), `introPrompts`, `actions`, `talkativity`, `weekdays`, `startTime`s, and a `SceneType`. |
| **SceneType.LOOP** | The 24h **baseline** scene. Fills all time not claimed by a one-time scene. Anchored at 00:00 if it declares no start time. |
| **SceneType.ONE_TIME** | A scene that **preempts** the loop for a short fixed window at a declared start time, then never repeats that day. |
| **Talkativity** | `0.0–1.0` probability knob controlling how often DJ intros (TTS) are inserted vs. pure song/jingle transitions. |
| **PlaylistRequest / WayOfSourcing** | How a scene gets its songs: `GENERATED` (AI content, no catalog songs), `QUERY` (filter by genre/label/search), `STATIC_LIST` (explicit ids), or default `RANDOM` (newest/oldest/random mix + shared fragments). |
| **SongPool** | The fetched `List<SoundFragment>` + a `sharerMap` (id → sharer name) for shared songs. |
| **SF (SoundFragment)** | The audio-item model (song / jingle / generated content / stream). "SF" is the platform-wide shorthand. |
| **OTS (One-Time Stream)** | A temporary, **user-started** personal stream, separate from the brand's continuous radio. Always has its own slug/routing identity; a brand is *optional* (brand-scoped vs owner-scoped) and, when present, only supplies song-sourcing/defaults — see `../live/OTS_WORKFLOW.md`. |
| **StreamAgenda** | The built product: an ordered set of `LiveScene`s for a brand, with a timezone and build timestamp. Held per-brand in `BrandPool`. |
| **LiveScene** | A runtime scene: carries the `timeline`, `contentStatus`, `traceId`, agent id, one-time flag, and `fitSeconds`. |
| **TimelineEntry** | One emission unit inside a scene: 1–2 songs + a `MixingType`, a `scheduledEmissionTime`, an estimated duration, intro/jingle flags, and a `TimelineEntryStatus`. |
| **MixingType** | The recipe aivox uses to stitch an entry (e.g. `SONG_ONLY`, `SONG_CROSSFADE_SONG`, `INTRO_SONG`, `JINGLE_INTRO_SONG`, `SONG_INTRO_SONG`, `*_GENERATED_*`). |
| **PromptEntry** | Per-song intro assignment: a `promptId` **or** a `CustomAction`, plus a language code. Empty = no intro. |
| **fitSeconds** | `sceneDuration − actualContentDuration`. Positive = content gap (underfill); large negative = overshoot (trimmed). |
| **TriggerContext** | `ON_TIME` vs `LATE` — how the ticker classifies a scene activation relative to its start. |
| **Boost / DJ state** | Live listener-driven signal (`DjStateService`) that can force an intro onto an otherwise silent entry at schedule/emit time. |
| **Emission** | Handing a `SongQueueMessageDTO` to RabbitMQ (`streaming` channel) for aivox to mix and stream. |

---

## 2. Build pipeline (script → StreamAgenda)

Entry points in `AgendaService`:
- `getStreamAgenda(brand, user)` / `getStreamAgenda(brand, scriptId, user)` — the daily/on-start build.
- `buildOtsAgenda(brand, scriptId, startTime, user)` — one-time-stream build (sequential, fixed per-scene durations, no loop baseline).

Regular build (`buildAgenda`):

```
scriptService.getById → sceneService.getAllWithPromptIds
  → orderedSceneSet         (sort by seqNum, then id)
  → filter isActiveOnWeekday (ISO 1..7; empty = every day)
  → expand into time slots:
       • each scene startTime → a SceneTimeSlot
       • no start times at all → the LOOP scene becomes the 00:00 baseline
  → sort slots by start; compute gap to next slot (wraps past midnight)
  → ExpandedSlot list:
       • LOOP slot  → fills its whole gap
       • ONE_TIME   → fixed 60s, remaining gap back-filled by current LOOP scene
  → per ExpandedSlot (sequential Uni chain, carrying a usedIds exclusion set):
       fetchSongsForSceneWithDuration → (retry once, clearing exclusions, if pool too short)
       convertToSongEntries → TimelineBuilder.buildTimeline → assignPromptsToTimeline
       → LiveScene → schedule.addScene
```

Key rules baked in here — **do not silently change**:
- **Loop is the baseline; one-time preempts.** Gaps are always filled by the current loop scene, never left empty.
- **De-duplication across scenes** via `usedIds` (STREAM sources are exempt), honoured by `RANDOM` **and** `QUERY`. If the catalog can't fill a scene, the exclusion set is reset once rather than emitting silence.
- **Non-repetition outranks matching the scene's criteria.** Slots are filled along a four-rung ladder, each rung used only when the one above it is empty: (1) criteria-matched and unused; (2) **any** song, unused — the filter is dropped rather than a song replayed (`widenToFill` → `ScheduleSongSupplier.getAnySongs`); (3) reuse, but **never adjacent**, in pool order so the least-recently-played returns first; (4) adjacent — unreachable unless the pool holds one single song. Matched songs sit at the head of the pool and are always consumed first. Dropping a scene's filter is *preferred* to repeating one of its songs — that ordering is the whole point, do not reorder the rungs.
- **Song count heuristic:** `max(10, ceil(effectiveDuration / 150))`; generated scenes subtract `AVERAGE_GENERATED_CONTENT_DURATION_SECONDS` from the budget first. This is a target for *fetching*; `selectDistinctSongsToFillDuration` then consumes only as many as the budget needs, and un-consumed songs are never marked used, so they remain available to later scenes.
- **Metrics are part of the contract:** `agenda_build_completed` always; `scene_content_gap` when `fitSeconds > 360`. Keep publishing them.

### 2a. Song sourcing (`ScheduleSongSupplier`)
- `RANDOM`: parallel fetch of newest (~30%), oldest (~40%), random (rest) + shared fragments, merged (first-wins) and shuffled.
- `QUERY`: filtered (honouring `excludeIds`), quantity-limited, then shuffled.
- `STATIC_LIST`: id-based; an explicit curation, so every pinned fragment is returned in pinned order, with no quantity limit.
- `GENERATED`: returns an empty pool — content is produced later by the emitter.

**Catalog Boost (per-song, build-time).** Each brand↔fragment link carries a
`boost` column (`mixpla__brand_sound_fragments.boost`, and `ssf.boost` for shared songs) whose
values reuse the `Boost` enum: `SUPER_BOOST(2)`, `BOOST(1)`, `NOTHING(0)`, `QUARANTINE(-1)`.
It shapes **which songs enter the agenda** at build time, entirely in SQL (`SoundFragmentBrandRepository`, `SharedSoundFragmentRepository`):
- **Deterministic** newest/oldest/filter queries: `ORDER BY COALESCE(boost,0) DESC, …` — boosted songs float to the top of the pool.
- **Random** query: weighted random `RANDOM() * CASE boost WHEN 2 THEN 4.0 WHEN 1 THEN 2.0 WHEN -1 THEN 0.05 ELSE 1.0 END DESC` — SUPER_BOOST ≈4× likelier, BOOST ≈2×, normal 1×.
- **QUARANTINE (-1):** filtered out of deterministic queries entirely (`COALESCE(boost,0) > -1`) and weighted down to 0.05× in random — effectively suppressed without being deleted.

> This is **Catalog Boost**, not the **Live Boost** of §4e. Same enum, different axis: Catalog
> Boost biases *song selection during build*; Live Boost *forces DJ intros on-air during
> emission*. Don't conflate them.

**Shared sound fragments(SFF).** A brand's pool is not only its own catalog — other users/brands can
*share* songs into it (`shared_sound_fragments` join, `SharedSoundFragmentService` /
`SharedSoundFragmentRepository`). During build they are treated as follows:
- **Criteria-matched (ladder rung 1): `RANDOM`/default only** (`getSongsRandomly`). `QUERY` and
  `STATIC_LIST` cannot criteria-match a shared song, because `SharedSoundFragmentRepository`
  narrows by **type only** — it has no genre/label conditions. **Known gap:** giving `buildQuery`
  the same genre/label conditions as `SoundFragmentQueryBuilder` would close it.
- **As widening (ladder rung 2): every sourcing path** (`getAnySongs`). Once a scene's own filter
  is exhausted the criteria are dropped anyway, so the type-only shared query is sufficient — a
  `QUERY` scene whose filter matches too few songs *will* be filled with received songs, and their
  `sharerMap` is carried through `widenToFill` so credit survives.
- **Eligibility:** `target_brand_id = brand`, `status = 505` (accepted), `archived = 0`, and
  Catalog Boost `> -1` (quarantined shares are excluded).
- **Selection:** 40% newest / 60% weighted-random (same `ssf.boost` weighting as §2a Catalog Boost),
  merged first-wins and shuffled, then folded into the brand pool with `putIfAbsent` — **the brand's
  own copy wins** on id collision.
- **Sharer identity:** each shared song carries a `sharerName` (`source_user_name`) → `SharedSongEntry`
  → `SongPool.sharerMap` → `SongEntry.sharerName`. This is the **only** sourcing path that populates
  `sharerMap`; it is later fed to the DJ draft (`IntroTtsGenerator`) so an intro can credit/dedicate
  to the sharer.
- **De-duplication:** shared songs are ordinary (non-`STREAM`) sources, so their ids join the
  cross-scene `usedIds` exclusion set like any other song.

### 2b. Timeline building (`TimelineBuilder` + `MixingTypeShuffler`)
- Walks the song pool, asking `MixingTypeShuffler.selectStrategy` per step; strategy depends on remaining songs, `talkativity`, whether intros are allowed, and anti-repetition counters (no 3+ same mix type, no 3+ consecutive 2-song or intro entries).
- Generated scenes get a leading generated slot before songs.
- `scheduledEmissionTime` advances by `entryDuration − crossfadeOverlap` (crossfades overlap, so wall-clock < sum of durations).
- Overshoot > `INTRO_TRIM_OVERSHOOT_THRESHOLD_SECONDS` (30s) → downgrade the last entry's mix type (drop its intro) via `INTRO_DOWNGRADE`.
- Sets `fitSeconds` and `timelineBuild=true` on the scene.

### 2c. Prompt / language assignment (`assignPromptsToTimeline`)
- The brand's `AiAgent` is **mandatory** (always present). Pool = active `introPrompts` + `actions`.
  (Any `agent == null` guards in the code are defensive legacy, not an optional path.)
- For each entry marked `hasIntro`, at the intro-bearing song index (`introAtIndex`), assign a random prompt/action and a weight-selected language onto the `PromptEntry`.

---

## 3. Emission pipeline (StreamAgenda → aivox)

The built agenda is stored, then three schedulers move it to air:

```
BrandPool.getRadioStream(brand)        // build + store; forceIntroOnFirstEntry (warmup)
  → pool holds RadioStream{agenda}

AgendaTicker  @Scheduled 60s           // WHICH scene is live now
  • findActiveOneTime (window [start, start+dur), latest wins, skip finished)
  • else findLoopingScene (absolute [start,end) window; latest started; cross-midnight aware)
  → ScenePool.setActiveScene(brand, scene)   (+ scene_started metric, TriggerContext)

SceneTicker   @Scheduled 15s           // arm entries of the active scene
  → StaggeredSongScheduler.scheduleSceneSongs(brand, scene)

StaggeredSongScheduler                 // WHEN each entry fires
  • per PENDING entry: skip if fully in the past; apply DJ boost intro if enabled
  • schedule a Vert.x timer at (emissionTime − aivoxDelaySeconds lead)
  • at fire: deadline/backpressure checks → EMITTING → emitTimelineEntry
       generated → GeneratedContentEmitter
       jingle    → JingleSongEmitter
       else      → SongEmitter
  → QueueSupplier.sendSongsToQueue → RabbitMQ "streaming" (routingKey = brandSlug) → aivox
```

Invariants — **do not silently change**:
- **Two tickers, two jobs.** `AgendaTicker` (60s) only selects the active scene; `SceneTicker` (15s) + `StaggeredSongScheduler` only schedule/emit its entries. Don't merge or cross these.
- **One-time preempts loop at runtime too** (`findActiveOneTime` before `findLoopingScene`), bounded by the scene's own window so stale instances don't latch.
- **Status is a state machine** (`TimelineEntryStatus`): SCHEDULED→PENDING→…→EMITTING→COMPLETED/FAILED/SKIPPED, transitioned with `compareAndSet` to stay idempotent under the 15s re-tick. Never reset status ad hoc.
- **Lead time & deadline:** entries fire `aivoxDelaySeconds` early; entries past the scene `endTime` are SKIPPED, not emitted late.
- **Backpressure** (`backpressure(brand)`) queues skip counts consumed at fire time; a failed entry triggers the next immediately (`triggerNextEntry`).
- **Timer hygiene:** `cancelBrandTimers` on scene change / removal / shutdown — always cancel when you deactivate.
- **Every stage emits metrics** with the propagated `traceId` / `emissionTraceId`. Preserve trace propagation end-to-end.
---

## 4. Mixing, talkativity, TTS & generated content

These four are the most misunderstood parts, so they get their own section. The key mental
split: **what** to say and **whether** to say it is decided at *build* time (baked into the
timeline); the actual **text + audio** is produced at *emit* time.

### 4a. Mixing (`MixingType`)
`MixingType` is the recipe aivox uses to stitch one entry; it travels in the DTO as
`mergingMethod`. Families:
- **No-TTS:** `SONG_ONLY`, `SONG_CROSSFADE_SONG`, `FILLER_JINGLE`.
- **With intro (TTS):** `INTRO_SONG`, `JINGLE_INTRO_SONG`, `SONG_INTRO_SONG`, `INTRO_SONG_INTRO_SONG`.
- **Generated:** `JINGLE_GENERATED_JINGLE[_WITH_BACKGROUND]`, `INTRO_JINGLE_GENERATED_JINGLE_WITH_BACKGROUND`.

Chosen at build by `MixingTypeShuffler` (per entry), but **re-decided at emit time** in several places — do not assume the build-time type is final:
- DJ boost / warmup **upgrade** a silent entry to an intro type.
- DJ **offline** → `SongEmitter` downgrades to a no-intro type (`getNoIntroMergingTypes`); `JingleSongEmitter` sends `FILLER_JINGLE` (or `JINGLE_INTRO_SONG` if DJ on + intro).
- `introAtIndex`/`needsIntroAtIndex` encode that `SONG_INTRO_SONG` only introduces the *second* song.

### 4b. Talkativity
A `0.0–1.0` per-scene probability that governs **how often intros appear** — nothing about
mixing quality. It is consumed at build time only:
- `selectDistinctSongsToFillDuration` uses it to estimate per-song overhead when deciding how many songs fill the duration budget — as the *expected* overhead (`talkativity·intro + (1−talkativity)·jingle`), deliberately not a per-song coin flip, so sizing stays deterministic and cannot disagree with the actual intro decisions made below.
- `MixingTypeShuffler.selectStrategy`: `random < talkativity` picks the intro path vs the no-TTS path, with anti-repetition (≥2 consecutive intros + talkativity<1 suppresses the next). This sets each entry's `hasIntro` / `needsIntros`.

So talkativity → how many entries are flagged `hasIntro` and get a `PromptEntry` assigned. The TTS itself still only fires at emit time **if the DJ is enabled** (see 4c).

### 4c. Where TTS happens (`IntroTtsGenerator`, emit time)
TTS is produced **at emission**, inside the emitters, and **only when
`djStateService.isDjEnabled(brand)` and the entry `hasIntro`**. If the DJ is off, no intro is
generated and mixing is downgraded to song/jingle only. Pipeline per intro:

```
resolveForLanguage(promptId, lang)                 (or CustomAction path)
  → DraftFactory builds draft (song facts, sharerName, optional chat summary)
  → LLM spoken text  (Anthropic or Groq per agent.llmType; system prompt from
                       `prompts/introSystemPrompt.hbs` (regular flow) or
                       `prompts/introActionSystemPrompt.hbs` (CustomAction flow) —
                       language BCP-47-locked, non-Latin song/artist names transliterated rather
                       than refused; emoji stripped; "technical difficulty" → discarded)
  → TTS engine per agent Voice.engineType: ElevenLabs | Modelslab | GCP | Fish Audio
  → mp3 saved to {uploads}/intro-tts/temp
  → ffprobe → IntroAudioResult(filePath, durationSeconds, gain, engineType)
```

- **Action intros** render the `CustomAction.instruction` via Handlebars + context vars before the LLM; may email an owner debug copy.
- **Fallbacks:** on any LLM/text failure, `tts-fallbacks.json` per-language canned line (fail-soft here is intentional — silence on air is worse).
- The resulting file path + duration go into the DTO `filePaths` map (`IntroKey.*`); aivox overlays the intro onto the song according to `mergingMethod`. jesoos never mixes audio.

### 4d. Generated content (`GeneratedContentEmitter`, emit time)
> A generated entry can **also carry a DJ intro**: with
> `INTRO_JINGLE_GENERATED_JINGLE_WITH_BACKGROUND` the emitter produces **two** LLM+TTS outputs at
> emission — the spoken **intro** (via `IntroTtsGenerator`, §4c) *and* the **generated content
> body** itself (news/ad/weather → `generateAudio`).
>
> There are several generated mixing types (currently `JINGLE_GENERATED_JINGLE`,
> `JINGLE_GENERATED_JINGLE_WITH_BACKGROUND`, `INTRO_JINGLE_GENERATED_JINGLE_WITH_BACKGROUND`) and
> **more may be added** — treat the set as open.
>
> **Generated content is always persisted as a `SoundFragment`** (with an `expires_at`). This means:
> - It can be **reused** if the fragment already exists in the DB — no need to re-run the LLM/TTS,
>   which **saves cost** when the same generated item plays again within its lifetime (e.g. a news
>   bulletin generated in the morning and replayed through the day).
> - A scheduled cleanup **deletes expired** generated fragments (e.g. purged at night), so stale
>   generated content doesn't accumulate. jesoos only sets `expires_at`; the actual pruning cron runs
>   in **metriq** (`ArchivedCleanupService` → `SoundFragmentRepository.findExpiredFragments`,
>   `expires_at < NOW()`), which deletes storage files then DB records. Out of jesoos's scope.
> - Once persisted, aivox mixes it **exactly like any song** — it neither knows nor cares that the
>   fragment was AI-generated; it's just a `SoundFragment` in the recipe.

For scenes with `WayOfSourcing.GENERATED`:
- **Build:** the scene contributes a single leading "generated" slot (`entry.generated=true`); `ScheduleSongSupplier` returns an empty song pool. `contentPrompts`, `mixingType`, and `mixingArtefacts` are copied onto the `LiveScene`.
- **Emit:** `contentPrompts[0].promptId` → ad vs news chosen by prompt type/title-contains-"ad" (`GeneratedUserAdService` / `GeneratedNewsService`; also `GeneratedWeatherService`). `generateAudio` yields a `SoundFragment` (LLM text → TTS).
- **Assembly by `scene.getMixingType()`:** `JINGLE_GENERATED_JINGLE` (jingle-wrapped only), `*_WITH_BACKGROUND` adds a `BACKGROUND_LOOP` bed, `INTRO_JINGLE_..._WITH_BACKGROUND` also generates a TTS intro. Jingles/background come from `mixingArtefacts` ids or by type on the master brand, picked at random. Null mixingType → `sendGeneratedOnly` (`SONG_ONLY`).
- Emitted at `PRIORITIZED_FRONT`. **Fails loud** (error metric, aborts the entry) if no jingles or no background are available. Ad plays record `PlayHistory`.

### 4e. DJ state & Live Boost (`DjStateService`)
> Not to be confused with the **Catalog Boost** of §2a. Live Boost is a *runtime, per-brand*
> signal that forces DJ intros at emission; Catalog Boost biases *song selection during build*.
> Same `Boost` enum, different axis.

Two independent per-brand runtime flags, held in memory (not in the agenda):

- **DJ enabled** (`isDjEnabled`) — the master gate for TTS. Default **off** (cost saving). When off, emitters send songs/jingles only, no intros. Toggled by `enableDj` / `disableDj` commands (`CommandService` ← `CommandResource` REST / RabbitMQ). Cleared when the brand leaves the pool (`BrandPool.onRemoved` → `remove`).
- **Live Boost** — a short, decrementing counter that **forces intros onto otherwise-silent entries**, independent of talkativity. `activateLiveBoost(brand, entries, type)` sets a `LiveBoostState(remaining, type)`.

How Live Boost affects emission (`StaggeredSongScheduler`, at both schedule and fire time):
- Only applies when `!entry.hasIntro`, active intro prompts exist, DJ is enabled, and `<2` consecutive boosted intros so far.
- `consumeLiveBoostEntry` decrements; each call spends one entry, auto-removing the state at 0 / below 0.
- `Boost.BOOST` → forces a plain intro type (`INTRO_SONG` / `SONG_INTRO_SONG`). `Boost.SUPER_BOOST` → also sets a jingle (`JINGLE_INTRO_SONG`). Then `assignBoostPrompt` picks a random active intro prompt.
- Emits a `dj_boost_applied` **WARNING** metric per boosted entry.

Where Live Boost is activated (`CommandService`):
- `startBrand` → `activateLiveBoost(brand, 3, SUPER_BOOST)` — first 3 entries of a fresh station are lively.
- `enableDj` → `activateLiveBoost(brand, 3, BOOST)` — 3 warm-up intros right after the DJ is switched on.

`Boost` enum values: `SUPER_BOOST(2)`, `BOOST(1)`, `NOTHING(0)`, `QUARANTINE(-1)`.

> Do not confuse the two: **DJ-enabled** decides *whether TTS can happen at all*; **boost**
> just guarantees a few intros fire even when the shuffler/talkativity would have stayed silent.

### 4f. Prompt resolution, draft & language (emit time)
How a spoken intro's text is produced, all at emission:

**1. Language selection** — `AiHelperUtils.selectLanguageByWeight(agent)` does a **weighted-random**
pick over the agent's `preferredLang` list (each `LanguagePreference` has a weight). One preference
→ that language; none → `EN_US` default. This chosen language then drives everything below and is
also hard-locked in the TTS system prompt ("respond exclusively in BCP-47 tag …").

The pick is **weighted (not fixed) on purpose**: a DJ can speak two or more languages and is meant
to deliver content in different languages across the broadcast — reflecting multilingual audiences
(in many countries listeners routinely speak 2+ languages). The weights let a station bias the mix
(e.g. 70% local / 30% English) while still varying per intro.

**2. Prompt resolution — master + language variants** (`PromptService.resolveForLanguage`):
- A `DjPrompt` is a **master** prompt (`masterId`) that may have **per-language variant** children.
- If the master's own `languageTag` equals the chosen language → use it (`fallBacked=false`).
- Else look up `findByMasterAndLanguage(masterId, language)`; found → use the variant; not found →
  fall back to the master prompt with `fallBacked=true` (the flag propagates to `IntroAudioResult`).

**3. Draft (deterministic) vs prompt (instruction)** — the spoken text is a two-part build:
- **Draft** (`DraftFactory.createDraft`) assembles a *deterministic* context via Groovy templates —
  song genres/labels, station profile, brand listeners, latest chat summary, sharer name, time
  context, and (for generated content) weather/news from external APIs. Drafts are authored in
  **English** regardless of output language.
- **Prompt** is the LLM instruction; the emitter sends `prompt.getPrompt()` + `"Draft input:\n" +
  draft` to Anthropic/Groq under the shared system prompt (`prompts/introSystemPrompt.hbs`,
  Handlebars-rendered with `langInstruction`/`manner`). The model turns the
  factual draft into spoken radio text in the chosen language; emoji stripped; obvious error/"technical
  difficulty" outputs are discarded in favour of the language fallback.

So: **draft = facts (deterministic), prompt = voice/instruction (LLM), language = weighted per emission.**
The scheduler itself does none of this — it only decides *when* an entry fires (§3); the emitter owns
prompt/draft/language/TTS.

---

## 5. Metrics (observability contract)

Metrics are how metriq sees this pipeline — treat them as part of the contract, not logging.
`MetricPublisher` serializes a `MetricEventDTO` and fires it onto the RabbitMQ `metrics`
channel (fire-and-forget; a publish failure is logged, never breaks the flow).

Every event carries: **app id** (jesoos), **brandName**, **eventType**, **processType**,
**traceId**, a string **code**, and a free-form **payload** map.

- **`MetricEventType`** (severity): `FATAL_ERROR`, `ERROR`, `COMMAND`, `INFORMATION`, `IMPORTANT_INFORMATION`, `SECURITY`, `WARNING`, `DEBUG`.
- **`ProcessType`** (origin): `FLOW` (live emission path), `CRON` (scheduled tickers/jobs), `INDEPENDENT` (build / one-off).
- **`code`** is the stable event name you group by in metriq (e.g. `agenda_build_completed`, `scene_started`, `entries_scheduled`, `entry_emitting_started`, `entry_emitted`, `entry_failed`, `cascade_entry_failed`, `dj_boost_applied`, `intro_tts_audio_generated`, `scene_content_gap`, `silence_risk`).

**Trace propagation is mandatory.** A single build/emission threads one `traceId`
(`buildTraceId` per agenda, `emissionTraceId` per entry) through all its events so metriq can
reconstruct one timeline. When you add or move a step, keep passing the existing id — never
mint a fresh `UUID.randomUUID()` mid-chain unless you are genuinely starting a new unit.

**Silence watchdog:** `trackEmission(brand, durationSeconds)` records when the last emitted
content should finish; a `@Scheduled 60s` `checkSilenceRisk` publishes a `silence_risk`
WARNING when a brand is overdue past `SILENCE_GRACE_SECONDS` (120s). This is the primary
"is the station actually on air?" signal — keep `trackEmission` calls in step with real emissions.

**Rule:** if you touch a stage, preserve its existing metric `code`s and severities, and keep
publishing on the same failure/success branches. Renaming a `code` or dropping an event
silently breaks metriq dashboards.

---

## 6. Rules for agents working here

These exist so improvements stay safe. **The workflow above is settled; your job is refinement, not redesign.**

1. **Improve, don't re-architect.** Tuning heuristics, clarifying names, fixing an off-by-one, adding a metric — fine. Changing the scene-selection model, the status state machine, the ticker split, the loop/one-time contract, or the RabbitMQ message shape — **stop and ask first.**
2. **Respect service boundaries.** jesoos builds and schedules; aivox mixes and streams. Never encode mixing logic here, and never reach into aivox. Cross-service message shapes (`SongQueueMessageDTO`, `CommandDTO`) are contracts — coordinate before changing.
3. **Keep timing deterministic and code-owned.** No new "the LLM decides when/what order" logic. Randomness stays confined to the existing `Random`/shuffle points.
4. **Preserve invariants and metrics.** If you touch a stage, keep its metric events and `traceId` propagation. They are how emission is observed in metriq.
5. **Fail loud, no silent fallbacks.** Match the existing style: empty agenda / missing scenes throw or publish an error metric — don't paper over with defaults. (See project `feedback_no_fallbacks`.)
6. **Match surrounding style.** Reactive `Uni` chains, sequential exclusion-set threading, `LocalTime`/`ZoneId` brand-timezone math. Don't introduce blocking calls or a new scheduling primitive.
7. **When a change feels "radical," it is.** Ask before: altering duration/overhead constants' meaning, the 15s/60s cadences, the lead-time model, or the de-duplication policy.
8. **Keep the two boosts distinct — never merge them.** They share only the `Boost` enum:
   - **Catalog Boost** (§2a) — a per-song DB value; biases *which songs enter the agenda* at build, in SQL only (`*SoundFragment*Repository`).
   - **Live Boost** (§4e) — a runtime per-brand counter (`DjStateService`, `LiveBoost*`); forces *DJ intros on-air* at emission.
   When you touch either, name it explicitly (`liveBoost…` vs the SQL `boost` column), don't introduce a bare `boost` identifier, and don't let one's logic leak into the other.

---

## Key files

| Area | File |
|---|---|
| Build orchestration | `agenda/AgendaService` |
| Song sourcing | `agenda/ScheduleSongSupplier` |
| Per-song boost (SQL) | `repository/soundfragment/SoundFragmentBrandRepository`, `SharedSoundFragmentRepository` |
| Shared fragments | `service/soundfragment/SharedSoundFragmentService`, `repository/soundfragment/SharedSoundFragmentRepository`, `model/stream/SharedSongEntry` |
| Timeline & mixing | `agenda/TimelineBuilder`, `agenda/MixingTypeShuffler`, `agenda/MixingStrategy` |
| Scene selection (live) | `live/AgendaTicker`, `live/ScenePool`, `live/BrandPool` |
| Entry scheduling | `live/SceneTicker`, `live/StaggeredSongScheduler` |
| Emitters | `live/SongEmitter`, `live/JingleSongEmitter`, `live/GeneratedContentEmitter` |
| TTS | `live/IntroTtsGenerator`, `live/scripting/DraftFactory` |
| Prompt / language resolution | `service/PromptService` (master + language variants), `util/AiHelperUtils` (language weighting) |
| Generated content | `live/generated/GeneratedNewsService`, `GeneratedUserAdService`, `GeneratedWeatherService` |
| DJ / live boost state | `live/DjStateService`, `live/LiveBoostState` |
| Metrics | `messaging/MetricPublisher` |
| Commands (DJ toggle / backpressure / rebuild) | `service/CommandService`, `rest/CommandResource`, `messaging/CommandConsumer` |
| Publish to aivox | `messaging/QueueSupplier`, `messaging/CommandPublisher` |
| Daily rebuild | `maintenance/DailyAgendaRebuildService` |
| Models | `model/stream/StreamAgenda`, `LiveScene`, `TimelineEntry`, `SongEntry`, `PromptEntry` |
</content>
</invoke>
