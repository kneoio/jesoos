# OTS (One-Time Stream) Workflow — Essential Guide

> **Scope: One-Time Streams only.** For the continuous brand radio (build pipeline, timeline,
> mixing, talkativity, TTS, DJ/Live Boost, metrics contract), see
> `../agenda/RADIO_WORKFLOW.md` — OTS reuses that machinery (`TimelineBuilder`, `SongEmitter`,
> `JingleSongEmitter`, `GeneratedContentEmitter`, `QueueSupplier`) but has its own scheduler,
> routing identity, and lifecycle, documented here.
>
> Coherent with aivox's `service/OTS_SCOPE.md` / `service/RADIO_SCOPE.md` — same terminology
> (**brand-scoped** vs **owner-scoped**), same routing model. If you change either side, update
> both pairs of docs together.

## 1. What an OTS is

A single, ephemeral stream that plays one script — its scenes run once, sequentially, anchored
at the moment it starts, then it tears down. Not a station in the continuous-radio sense: no
loop baseline, no wall-clock scheduling, no `AgendaTicker`/`SceneTicker`. Identified by its own
`slugName` (e.g. `birthday-party-aidazi`), generated at creation.

## 2. Two scopes — brand-scoped vs owner-scoped

An `OtsDefinition` (datanest-owned, `mixpla__ots_definitions`) has an optional `brandId` and a
mandatory-when-`brandId`-is-null `agentId`:

| | Brand-scoped (`brandId` set) | Owner-scoped (`brandId == null`) |
|---|---|---|
| Song sourcing | `SongSourceScope.BrandScope` — borrows the master brand's `SoundFragment` catalog | `SongSourceScope.OwnerScope` — sources from the definition owner's (author's) catalog |
| `OneTimeStream.masterBrand` | The resolved `Brand` | `null` |
| Timezone / country / bitrate / `aiOverriding` | Inherited from the master brand | Fallback: owner's timezone (else system default), `64` kbps, `CountryCode.UNKNOWN`, default `AiOverriding` |
| Codec/bitrate on the aivox side | Inherited from the master brand | Fixed aivox default: `OPUS`, `64000` (`LiveStreamPool.OWNER_SCOPED_OTS_BASE_BITRATE`) |
| DJ intros (TTS) | **Always on** — same as owner-scoped, ignores the master brand's `isDjEnabled` toggle entirely (see §3) | **Always on** |
| `aiAgentId` | `definition.getAgentId()` if set, else the master brand's | `definition.getAgentId()` (mandatory) |

`OtsService.coldStart` (aivox) only refuses to start when there is **neither** a brand **nor**
an agent — mirroring `OneTimeStreamService.startFromDefinition`'s own check. `brandId == null`
on a definition is a legitimate, intentional state, not a data bug.

**Terminology for the future: "synthetic brand."** aivox's `LiveStreamPool.initializeOtsStation`
already builds one for the owner-scoped case — an in-memory-only `Brand` stand-in, never
persisted, that exists purely to carry default values (codec/bitrate today) into code that
otherwise expects a real `Brand`. **"Synthetic brand" is the agreed term for that pattern** —
use it (not "fake brand", "dummy brand", "stand-in", etc.) if/when jesoos grows an equivalent —
e.g. collapsing `OneTimeStream`'s constructor `if (masterBrand != null) {...} else {...}` branch
(§`OneTimeStream.java`) into building a synthetic `Brand` from the owner's context (timezone,
country, `aiOverriding`) up front, instead of the current inline fallback fields.
**Defaults only, never routing/sourcing/DJ:** a synthetic brand must **not** be handed to
`SongSourceScope.BrandScope` (there's no real catalog behind it), used as the RabbitMQ routing
key (nothing is registered under its slug), or checked against `DjStateService` (no toggle is
ever set for it). Those three stay governed by the real `masterBrand == null` check — see §3.
Conflating "carries defaults" with "is a real, addressable brand" is exactly the bug this doc's
§3 exists to prevent; a synthetic brand must not reopen it one level removed.

## 3. Routing identity — read this before touching `OtsStreamScheduler`

**The OTS's own slug is always the routing/station identity — brand-scoped or not.** aivox's
`LiveStreamPool.initializeOtsStation(slug, masterBrandSlug, ownerAgentId)` keys the in-memory
station on `slug` directly; `masterBrandSlug` there is defaults-only (codec/bitrate), never the
station key. So on the jesoos side, `OtsStreamScheduler` must send `stream.getSlugName()` — not
the master brand's slug — as:
- the RabbitMQ routing key (`QueueSupplier.sendSongsToQueue`'s `streamSlug` param, wire field
  `SongQueueMessageDTO.brandSlug` — that DTO field name is a 2next/aivox cross-service contract,
  not renamed here),
- the `otsSlugName` tag on each `LiveScene`/`SongQueueMessageDTO`.

**A previous bug conflated routing with the DJ-toggle check, and a later product decision
removed the DJ-toggle check from OTS entirely.** `SongEmitter`/`JingleSongEmitter` used to call
`djStateService.isDjEnabled(...)` (a per-**brand** toggle, `CommandService.enableDj`/`disableDj`)
to decide whether to generate a TTS intro. `OtsStreamScheduler` originally read
`stream.getMasterBrand().getSlugName()` once and passed that single value for *both* routing and
the DJ check — which NPE'd the moment `masterBrand` was null (owner-scoped OTS), and would have
been the wrong value for routing even when it wasn't null. The immediate fix split that into two
values (`streamSlug` for routing, a separate nullable brand slug for the DJ check). Explicitly
decided afterward: **an OTS always talks, brand-scoped or not** — a personal one-time stream
shouldn't go silent just because its (optional) master brand's ambient live DJ happens to be
toggled off. So `SongEmitter.send`/`JingleSongEmitter.send` now take a plain `boolean djOn`
instead of a brand slug to check: radio callers (`StaggeredSongScheduler`) still resolve it from
`djStateService.isDjEnabled(brandName)`; `OtsStreamScheduler` always passes `true`. `streamSlug`
remains the sole routing/tagging identity, threaded through `scheduleStream` →
`scheduleSceneSongs` → `scheduleEntry` → `emitEntry` unchanged.

Don't reintroduce a brand-slug-based DJ lookup for OTS — that's exactly the confusion that
caused the original bug, and the "always on" decision means OTS has no use for one at all.

## 4. Lifecycle

1. **Create (dormant).** `OneTimeStreamService.start(slugName)` (datanest-backed, supports both
   scopes) builds an OTS agenda via `AgendaService.buildOtsAgenda` — an OTS-specific script,
   scenes laid **sequentially and anchored at the start moment** (not wall-clock), played
   one-by-one, no loop baseline (see `../agenda/RADIO_WORKFLOW.md` §2 for the shared build
   internals). The stream is created and stays **dormant**, waiting to be started.
2. **Start.** Never started via the old `startImmediately` path — an OTS cold-starts itself the
   first time a listener hits its URL: aivox `OtsService.coldStart` → jesoos `startOts` →
   `OtsStreamScheduler.scheduleStream`. Entries emit with `otsSlugName` set, via the same
   `SongEmitter` / `JingleSongEmitter` / `GeneratedContentEmitter` used by radio (prioritized:
   `PRIORITIZED_FRONT` for generated content, `PRIORITIZED` otherwise).
3. **Songs from the parent-or-owner catalog.** See §2 — `SongSourceScope.BrandScope` or
   `.OwnerScope` depending on whether `masterBrand` is present.
4. **Complete → teardown.** `OtsStreamScheduler.checkOtsFinished` runs after every `TimelineEntry`
   reaches a terminal status; once every `LiveScene` in the agenda is `isFinished()` (guarded to
   fire once per stream), it publishes an `ots_finished` metric, sends aivox the
   `CommandType.JESOOS_OTS_FINISHED` command (`{"streamSlug": ...}`, aivox tears down its station
   via `LiveStreamPool.stopAndRemoveStation`), cancels remaining timers
   (`OtsStreamScheduler.cancelOtsTimers`), and removes the stream from jesoos's own
   `OneTimeStreamPool` (`pool.stopAndRemove`).
5. **Explicit stop.** `CommandService.stopOts` is queue-only — reached via the RabbitMQ
   `JESOOS_STOP_OTS` command (`CommandService.handleQueueCommand`), no REST path (REST-triggered
   stop was removed; commands go over the queue, not REST, per the platform's messaging
   convention). It cancels the stream's timers and removes it from `OneTimeStreamPool` — unlike
   natural completion above, it does not currently notify aivox.

## 4b. Event chat (guest DJ chat)

An OTS can be chatted with, event-scoped, via the same public chat WebSocket as brand radio — a mode
branch, not a separate service. Guests open the event URL/QR (the slug **is** the access token, no
sign-in) and ask the DJ to play songs and read shout-outs/congratulations. Detection is authoritative
by slug (`OtsDefinitionRepository.findBySlugName` when the slug is not a brand). Song search/play use the
OTS's `SongSourceScope` and route on the OTS slug; the chat is ephemeral and purged on teardown
(`ChatService.purgeOtsChat`, called from `checkOtsFinished` here and `CommandService.stopOts`). Full
detail in `../chat/CHAT_WORKFLOW.md` §9.

## 4c. Scheduling & emission (`OtsStreamScheduler`)

**Every scene is scheduled once, upfront.** `scheduleStream` walks all `LiveScene`s and arms a
vertx timer for every `PENDING` entry in a single pass at stream start. There is no ticker and no
per-scene replanning (§1): the plan is computed once and never revisited. Radio, by contrast,
schedules scene-by-scene as each scene begins, so each replan sees current reality — a deliberate
difference, not an oversight.

**Lead time.** A timer fires `jesoos.aivox-delay-seconds` (default `60`) *before* the entry's
`scheduledEmissionTime`, giving jesoos time to generate intro text, run TTS and get the message to
aivox before the audio is due. Radio uses the same lead.

**Timer keys must include the scene.** `otsTimers` is `slug → ("sceneId:sequenceNumber" → timerId)`.
`sequenceNumber` restarts at `0` in every scene, and because OTS arms all scenes in one pass, a bare
sequence number **collides across scenes**: the last scene written wins the key, and the first
scene's fire-callback then cancels *that* timer through `removeTimer`. The symptom is brutal and
silent — the final scene's entry sits at `SCHEDULED` forever while its timer is already dead, the
DJ never says goodbye, and nothing is logged. Never key OTS timers by sequence number alone.

**Two skip paths, both currently silent.**
- at schedule time — an entry whose window has already fully passed → `SKIPPED`
- at fire time — `now >= scene.getEndTime()` → `SKIPPED`

Neither publishes a metric, so from the outside a legitimately skipped entry is indistinguishable
from one whose timer was lost. If you add skip observability, carry a reason code — that ambiguity
has cost real debugging time.

**Drift is never corrected — the stream just gets longer.** A chat/DJ song request
(`PlaySongForOtsToolHandler`) builds its own `SongQueueMessageDTO` and goes straight to aivox via
`internalRestCall.addSongToQueue` with `GENTLE_INTERRUPT`/`HARD_INTERRUPT` priority. It never
touches the agenda, the timeline or the timers. Consequences, all current behaviour:
- nothing shifts — every entry still fires at its originally planned moment;
- nothing is skipped — the displaced entry still generates its intro text and **still pays for
  TTS**, even though the audio lands later than planned;
- the extra duration is absorbed entirely by aivox's queue depth, so real playback slides later
  while jesoos keeps emitting to the original plan.

The estimate written at build time is never updated, so the fire-time deadline check compares the
plan against itself and effectively never trips on insert-induced drift. **Known gap:** to actually
save the TTS spend, the injected duration would have to be recorded against the stream and the
affected entries' estimates pushed forward, so the existing deadline check starts firing on its own
— before TTS generation. Not implemented.

**The silence watchdog does not stop an OTS.** `MetricPublisher.checkSilenceRisk` publishes a
`silence_risk` WARNING and nothing more. It once self-stopped a "lingering" OTS; that was removed
because emission cadence is the wrong signal for an OTS:
- on a correctly chained timeline the next emission lands *exactly* on `nextExpectedEmitAt`
  (`emit + contentDuration`), so every entry runs with precisely `SILENCE_GRACE_SECONDS + 60` = 180s
  of slack — never more, no matter how healthy the stream;
- `trackEmission` is only reached on the emit **success** path (`QueueSupplier`), so a single failed
  intro generation never advances the clock and the stream is condemned against a stale expectation.

The result was healthy streams being torn down mid-run: `stopOts` cancels every remaining timer and
removes the stream from the pool, so the agenda 404s while aivox happily plays its buffered backlog
for another ten minutes. Do not reintroduce a cadence-based auto-stop here.

**Teardown must wait for real audio, not the plan.** `TimelineBuilder` budgets a **flat 10s per
intro**, but real TTS length varies widely (8s–36s observed). For any entry but the last the
overrun simply pushes into the next slot and aivox's queue absorbs it; the **final** entry has
nothing after it, so a teardown timed on `scene.getEndTime()` lands mid-song. This was observed
live: Bye was planned at 190s (180s song + 10s budget) but its Google intro came out at 25s, so
`finishOts` fired at the planned 18:32:36 while the audio ran to 18:32:51 — aivox obeyed
`JESOOS_OTS_FINISHED` immediately (`ots_stop_command_sent`, reason `command_received_via_queue`)
and the goodbye was cut off 15s early. `checkOtsFinished` therefore takes
`max(plannedDeadline, trackedEnd + aivox-delay-seconds)`: `trackEmission` records the *actual*
song + intro seconds at emit time, and emission runs one lead ahead of playback, so playout ends a
lead after the tracked instant. Never finish earlier than planned — only later.

**Backpressure is a no-op for an OTS.** `CommandService.backpressure` feeds
`StaggeredSongScheduler.skipCounters`, which is only ever read inside radio's own fire-time check.
OTS entries run through `OtsStreamScheduler` and never consult it, so the signal was silently
swallowed while reporting `backpressure_ok`. It now short-circuits with a `backpressure_ignored_ots`
WARNING instead of a misleading success. aivox does not distinguish OTS from radio stations — its
`QueueBackpressureChecker` scans every online station — so an OTS *will* keep receiving this call;
the honest answer just lives on the jesoos side.

## 5. Metrics

OTS mirrors the radio contract (`../agenda/RADIO_WORKFLOW.md` §5) — same event types, same
"trace propagation is mandatory" rule — tagged by `streamSlug` (the OTS's own slug) instead of
a brand slug, at two layers:
- **Command layer** (`CommandService.startOts`, `ProcessType.FLOW`): `ots_start_received` →
  `ots_start_ok` / `ots_start_failed`. Both the REST path (`CommandResource.handleOtsStart`) and
  the RabbitMQ `JESOOS_START_OTS` command path (`CommandService.handleQueueCommand`) go through
  `startOts` — don't call `OneTimeStreamService.startFromDefinition`/`.start` directly from a new
  entry point, or you'll silently lose these metrics again (this was the actual bug: the RabbitMQ
  path used to bypass `startOts` and call `startFromDefinition` straight, so OTS runs started via
  RabbitMQ never showed up in metriq at all).
- **Build layer** (`AgendaService.buildOtsAgendaFromScenes`, `ProcessType.INDEPENDENT`):
  `scene_content_gap` (WARNING, `fitSeconds > 360`) per scene, `agenda_build_completed`
  (INFORMATION, `elapsedMs`/`elapsedSec`/`scenes`) on success, `agenda_empty_or_failed` (ERROR)
  on build failure — same codes/thresholds as the radio build path, so metriq dashboards don't
  need OTS-specific queries.
- **Scheduling layer** (`OtsStreamScheduler.scheduleSceneSongs`, `ProcessType.FLOW`):
  `entries_scheduled` (INFORMATION) per scene, mirroring radio's payload (`scene`, `entries`,
  `currentTime`). Because OTS arms every scene in one pass these land as a burst within ~1ms of
  each other, one event per scene.
- **Command layer, backpressure:** `backpressure_ignored_ots` (WARNING) instead of
  `backpressure_ok` when the slug resolves to an OTS (§4c).
- **Cron layer:** `silence_risk` (WARNING, `ProcessType.CRON`). Note the process type — these do
  **not** appear in a FLOW trace view, which is exactly why an OTS teardown once looked causeless
  for hours. When an OTS misbehaves, read the CRON stream too.

**Absence of an event is not proof of absence.** Metric publishing is fire-and-forget:
`publishMetric` swallows failures into a logged error, so a genuinely dropped event is invisible in
metriq and visible only in jesoos's log. Just as important, *read* paths differ — `/metriq/snapshot`
is a capped buffer and will silently omit older events that `/metriq/{slug}/traces` still returns.
Before concluding "X never ran", query the per-slug endpoint and cross-check the agenda's
`statusHistory`, which is authoritative state rather than telemetry.

## 6. Rules for agents working here

Same spirit as `../agenda/RADIO_WORKFLOW.md` §6 — this is settled behavior, refine don't
redesign. In particular:
1. **Never reintroduce a single "brand slug" parameter that does double duty** for routing and
   for a brand-specific lookup (catalog scope, codec/bitrate defaults). Owner-scoped OTS has no
   brand — any code path that assumes one will NPE or silently misbehave for it. Grep
   `getMasterBrand(`/`getMasterBrandId(` before adding a new one; null-check. DJ status is no
   longer one of these lookups — OTS always talks (see §3) — but the principle still applies to
   song-sourcing and defaults.
2. **Keep this doc and aivox's `OTS_SCOPE.md`/`RADIO_SCOPE.md` coherent.** They describe the two
   ends of the same contract (jesoos decides *when*/*what* to emit; aivox decides *how* to serve
   the station). A routing-identity or scope-semantics change on one side is a change to both.
3. **RabbitMQ message shape is a cross-service contract** (`SongQueueMessageDTO`, `CommandDTO` in
   2next) — coordinate before changing field names or semantics, same rule as radio.
4. **Never key per-entry state by `sequenceNumber` alone.** It restarts per scene and OTS holds all
   scenes at once (§4c). Timer maps, dedup sets, caches — all need the scene id in the key.
5. **Don't add a cadence-based auto-stop.** An OTS emits ahead of playback and legitimately goes
   quiet between entries; only the agenda's own completion (`checkOtsFinished`) or an explicit
   `JESOOS_STOP_OTS` may tear one down (§4c).
6. **An OTS lives only in memory.** `OneTimeStreamPool` and the vertx timers have no persistence:
   anything that rebuilds the CDI context destroys a running OTS with no metric and no command —
   the agenda simply 404s while aivox plays out its buffer. In dev this includes **Quarkus hot
   reload**, which is why `quarkus.live-reload.enabled=false` is set in `application.properties`;
   recompiling while a stream is live would otherwise silently kill it and look like a product bug.
   Restart jesoos deliberately, between runs, never during one.

## Key files

| Area | File |
|---|---|
| OTS lifecycle (create/start/delete) | `service/OneTimeStreamService.java` |
| OTS emission scheduling | `live/OtsStreamScheduler.java` |
| In-memory OTS registry | `live/OneTimeStreamPool.java` |
| Stream model | `model/stream/OneTimeStream.java`, `model/stream/AbstractStream.java` |
| REST | `rest/OtsResource.java` |
| Definition (datanest-owned data) | `repository/OtsDefinitionRepository.java`, 2next `OtsDefinition` |
| Shared with radio (see `RADIO_WORKFLOW.md`) | `agenda/AgendaService.buildOtsAgenda`, `agenda/TimelineBuilder`, `live/SongEmitter`, `live/JingleSongEmitter`, `live/GeneratedContentEmitter`, `messaging/QueueSupplier` |
