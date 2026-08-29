---
type: Workflow
title: One-time stream internals
description: Routing identity, scopes and the synthetic brand, RabbitMQ binding, cold start, scheduling differences, drift, teardown timing, metrics layers and the rules that keep OTS from regressing.
tags: [ots, routing, rabbitmq, coldstart, teardown, otsruntype, scheduler, synthetic-brand, metrics]
audience: [developer]
---

# What an OTS is, mechanically

A single ephemeral stream that plays one script — its scenes run once, sequentially, anchored at the
moment it starts, then it tears down. It is not a station in the continuous-radio sense: no loop
baseline, no wall-clock scheduling, no `AgendaTicker` or `SceneTicker`. It is identified by its own
`slugName`, generated at creation.

OTS reuses the radio machinery — `TimelineBuilder`, `SongEmitter`, `JingleSongEmitter`,
`GeneratedContentEmitter`, `QueueSupplier` — but has its own scheduler, routing identity and lifecycle.
This is the jesoos end of a two-sided contract with aivox: jesoos decides *when* and *what* to emit,
aivox decides *how* to serve the station. A routing-identity or scope-semantics change on one side is a
change to both.

# Scopes

An `OtsDefinition` (datanest-owned, `mixpla__ots_definitions`) has an optional `brandId` and an
`agentId` that becomes mandatory when `brandId` is null.

| | Brand-scoped (`brandId` set) | Owner-scoped (`brandId == null`) |
|---|---|---|
| Song sourcing | `SongSourceScope.BrandScope` — borrows the master brand's `SoundFragment` catalog | `SongSourceScope.OwnerScope` — the definition owner's catalog |
| `OneTimeStream.masterBrand` | the resolved `Brand` | `null` |
| Timezone, country, bitrate, `aiOverriding` | inherited from the master brand | owner's timezone else system default, 64 kbps, `CountryCode.UNKNOWN`, default `AiOverriding` |
| Codec and bitrate on the aivox side | inherited from the master brand | fixed aivox default `OPUS`, `64000` (`LiveStreamPool.OWNER_SCOPED_OTS_BASE_BITRATE`) |
| DJ intros | **always on** — the master brand's `isDjEnabled` toggle is ignored entirely | **always on** |
| `aiAgentId` | `definition.getAgentId()` if set, else the master brand's | `definition.getAgentId()`, mandatory |

`OtsService.coldStart` in aivox refuses to start only when there is **neither** a brand **nor** an
agent, mirroring `OneTimeStreamService.startFromDefinition`'s own check. A definition with
`brandId == null` is a legitimate, intentional state, not a data bug.

# The synthetic brand

aivox's `LiveStreamPool.initializeOtsStation` already builds one for the owner-scoped case: an
in-memory-only `Brand` stand-in, never persisted, existing purely to carry default values — codec and
bitrate today — into code that otherwise expects a real `Brand`.

**"Synthetic brand" is the agreed term for that pattern.** Use it rather than "fake brand", "dummy
brand" or "stand-in" if jesoos ever grows an equivalent — for example collapsing `OneTimeStream`'s
constructor branch on `masterBrand != null` into building a synthetic `Brand` from the owner's context
(timezone, country, `aiOverriding`) up front instead of the current inline fallback fields.

**Defaults only, never routing, sourcing or DJ.** A synthetic brand must not be handed to
`SongSourceScope.BrandScope` — there is no real catalog behind it — must not be used as the RabbitMQ
routing key, since nothing is registered under its slug, and must not be checked against
`DjStateService`, since no toggle is ever set for it. Those three stay governed by the real
`masterBrand == null` check. Conflating "carries defaults" with "is a real, addressable brand" is
exactly the bug the routing section below exists to prevent.

# Routing identity

**The OTS's own slug is always the routing and station identity, brand-scoped or not.** aivox's
`LiveStreamPool.initializeOtsStation(slug, masterBrandSlug, ownerAgentId)` keys the in-memory station
on `slug` directly, and `masterBrandSlug` there is defaults-only, never the station key. So
`OtsStreamScheduler` must send `stream.getSlugName()` — not the master brand's slug — as both the
RabbitMQ routing key (`QueueSupplier.sendSongsToQueue`'s `streamSlug` parameter, wire field
`SongQueueMessageDTO.brandSlug`, whose name is a 2next and aivox cross-service contract and is not
renamed here) and the `otsSlugName` tag on each `LiveScene` and `SongQueueMessageDTO`.

A previous bug conflated routing with the DJ-toggle check, and a later product decision removed the
DJ-toggle check from OTS entirely. `SongEmitter` and `JingleSongEmitter` used to call
`djStateService.isDjEnabled(...)`, a per-**brand** toggle, to decide whether to generate a TTS intro.
`OtsStreamScheduler` originally read `stream.getMasterBrand().getSlugName()` once and passed that single
value for both routing and the DJ check — which threw a `NullPointerException` the moment `masterBrand`
was null on an owner-scoped OTS, and would have been the wrong value for routing even when it wasn't.
The immediate fix split it into two values. It was then decided explicitly that **an OTS always talks**:
a personal one-time stream should not go silent because its optional master brand's ambient live DJ
happens to be toggled off. So both emitters now take a plain `boolean djOn` — radio callers resolve it
from `djStateService.isDjEnabled(brandName)` and `OtsStreamScheduler` always passes `true` — while
`streamSlug` remains the sole routing and tagging identity, threaded through `scheduleStream` →
`scheduleSceneSongs` → `scheduleEntry` → `emitEntry`.

Always-on is the DJ *toggle*, not a guarantee of speech. A scene with no active intro prompt or custom
action plays the song without an intro (`SONG_ONLY` / `SONG_CROSSFADE_SONG`). That is legitimate, not
a data bug.

Do not reintroduce a brand-slug-based DJ lookup for OTS: that is exactly the confusion that caused the
original bug, and the always-on decision means OTS has no use for one.

# RabbitMQ binding

An OTS binds its **own** routing key exactly like a radio station:
`initializeOtsStation` → `queueBind(queueName, STREAMING_EXCHANGE, otsSlugName)`, and
`removeOtsStation` unbinds it. jesoos publishes with `routingKey = streamSlug`, which is the brand slug
for radio and the OTS slug for an OTS. Earlier prose claiming an OTS has no binding and shares the
master brand's channel describes the bug, not the behaviour — with no binding the messages were
silently unroutable.

# Lifecycle

1. **Create, dormant.** `OneTimeStreamService.start(slugName)`, datanest-backed and supporting both
   scopes, builds an agenda through `AgendaService.buildOtsAgenda`: an OTS-specific script whose scenes
   are laid sequentially and anchored at the start moment rather than wall-clock, played one by one,
   with no loop baseline. The stream is created and stays dormant.
2. **Start.** Never through the old `startImmediately` path — an OTS cold-starts the first time a
   listener hits its URL: aivox `OtsService.coldStart` → jesoos `startOts` →
   `OtsStreamScheduler.scheduleStream`. Entries emit with `otsSlugName` set through the same emitters
   radio uses, at `PRIORITIZED_FRONT` for generated content and `PRIORITIZED` otherwise.
3. **Songs** come from the brand or owner catalog per the scope table above.
4. **Complete and tear down.** `OtsStreamScheduler.checkOtsFinished` runs after every `TimelineEntry`
   reaches a terminal status. Once every `LiveScene` is `isFinished()`, guarded to fire once per stream,
   it publishes an `ots_finished` metric, sends aivox `CommandType.JESOOS_OTS_FINISHED` with
   `{"streamSlug": …}` so aivox tears the station down via `LiveStreamPool.stopAndRemoveStation`,
   cancels remaining timers (`cancelOtsTimers`) and removes the stream from `OneTimeStreamPool`
   (`pool.stopAndRemove`).
5. **Explicit stop.** `CommandService.stopOts` is queue-only, reached through the RabbitMQ
   `JESOOS_STOP_OTS` command in `handleQueueCommand`. There is no REST path — the REST-triggered stop
   was removed because commands go over the queue, not REST. It cancels timers and removes the stream
   from the pool but, unlike natural completion, does not currently notify aivox.

# Cold start in detail

There is no REST start endpoint. The first HLS request to `/live/<slug>` reaches `StreamingResource` →
`StreamingService.ensureOtsStarted` → `OtsService.ensureStarted`, which reads the definition from
`OtsDefinitionRepository`. An unknown slug is `NOT_OTS` and answers 404. The station is created through
`LiveStreamPool.initializeOtsStation`, `OtsWarden` starts with a waiting melody from
`WaitingAudioProvider`, and exactly one REST trigger goes to jesoos — `sendJesoosOtsCommand(slug,
"start")` — which builds the agenda and starts emitting. A second listener arriving during STARTING or
ACTIVE simply attaches, since the `starting` claim prevents a duplicate trigger.

# Scheduling

**Every scene is scheduled once, upfront.** `scheduleStream` walks all `LiveScene`s and arms a Vert.x
timer for every `PENDING` entry in a single pass at stream start. There is no ticker and no per-scene
replanning: the plan is computed once and never revisited. Radio, by contrast, schedules scene by scene
as each begins, so each replan sees current reality — a deliberate difference, not an oversight.

Lead time is `jesoos.aivox-delay-seconds`, default 60: a timer fires that far *before* the entry's
`scheduledEmissionTime`, giving jesoos time to generate intro text, run TTS and get the message to aivox
before the audio is due. Radio uses the same lead.

**Timer keys must include the scene.** `otsTimers` is `slug → ("sceneId:sequenceNumber" → timerId)`.
`sequenceNumber` restarts at 0 in every scene, and because OTS arms all scenes in one pass a bare
sequence number **collides across scenes**: the last scene written wins the key, and the first scene's
fire callback then cancels *that* timer through `removeTimer`. The symptom is brutal and silent — the
final scene's entry sits at `SCHEDULED` forever while its timer is already dead, the DJ never says
goodbye, and nothing is logged. Never key OTS timers by sequence number alone.

**Two skip paths, both currently silent.** An entry whose window has already fully passed is `SKIPPED`
at schedule time, and `now >= scene.getEndTime()` skips it at fire time. Neither publishes a metric, so
from outside a legitimately skipped entry is indistinguishable from one whose timer was lost. If you add
skip observability, carry a reason code — that ambiguity has cost real debugging time.

# Drift is never corrected

A chat or DJ song request (`PlaySongForOtsToolHandler`) builds its own `SongQueueMessageDTO` and goes
straight to aivox via `internalRestCall.addSongToQueue` at `GENTLE_INTERRUPT` or `HARD_INTERRUPT`
priority. It never touches the agenda, the timeline or the timers. Current behaviour, all of it
deliberate:

* nothing shifts — every entry still fires at its originally planned moment;
* nothing is skipped — the displaced entry still generates its intro text and **still pays for TTS**,
  even though the audio lands later than planned;
* the extra duration is absorbed entirely by aivox's queue depth, so real playback slides later while
  jesoos keeps emitting to the original plan.

The estimate written at build time is never updated, so the fire-time deadline check compares the plan
against itself and effectively never trips on insert-induced drift. Known gap: to actually save the TTS
spend, the injected duration would have to be recorded against the stream and the affected entries'
estimates pushed forward, so the existing deadline check starts firing on its own before TTS generation.
Not implemented.

# The silence watchdog does not stop an OTS

`MetricPublisher.checkSilenceRisk` publishes a `silence_risk` warning and nothing more. It once
self-stopped a lingering OTS; that was removed because emission cadence is the wrong signal here. On a
correctly chained timeline the next emission lands *exactly* on `nextExpectedEmitAt`
(`emit + contentDuration`), so every entry runs with precisely `SILENCE_GRACE_SECONDS + 60` = 180 seconds
of slack, never more, no matter how healthy the stream. And `trackEmission` is only reached on the emit
**success** path in `QueueSupplier`, so a single failed intro generation never advances the clock and
the stream is condemned against a stale expectation.

The result was healthy streams torn down mid-run: `stopOts` cancels every remaining timer and removes
the stream from the pool, so the agenda 404s while aivox happily plays its buffered backlog for another
ten minutes. Do not reintroduce a cadence-based auto-stop.

# Teardown must wait for real audio, not the plan

`TimelineBuilder` budgets a flat 10 seconds per intro, but real TTS length varies widely — 8 to 36
seconds observed. For any entry but the last, the overrun pushes into the next slot and aivox's queue
absorbs it; the **final** entry has nothing after it, so a teardown timed on `scene.getEndTime()` lands
mid-song.

This was observed live: the closing entry was planned at 190 seconds (a 180-second song plus the
10-second budget), but its Google intro came out at 25 seconds, so `finishOts` fired at the planned
18:32:36 while the audio ran to 18:32:51. aivox obeyed `JESOOS_OTS_FINISHED` immediately
(`ots_stop_command_sent`, reason `command_received_via_queue`) and the goodbye was cut off 15 seconds
early.

`checkOtsFinished` therefore takes `max(plannedDeadline, trackedEnd + aivox-delay-seconds)`:
`trackEmission` records the *actual* song plus intro seconds at emit time, and emission runs one lead
ahead of playback, so playout ends a lead after the tracked instant. Never finish earlier than planned —
only later.

# Backpressure is a no-op for an OTS

`CommandService.backpressure` feeds `StaggeredSongScheduler.skipCounters`, which is only ever read inside
radio's own fire-time check. OTS entries run through `OtsStreamScheduler` and never consult it, so the
signal was silently swallowed while reporting `backpressure_ok`. It now short-circuits with a
`backpressure_ignored_ots` warning instead of a misleading success. aivox does not distinguish OTS from
radio stations — its `QueueBackpressureChecker` scans every online station — so an OTS *will* keep
receiving this call; the honest answer just lives on the jesoos side.

# Completion and run types

Two paths reach `OtsService.teardown`, and aivox is the sole writer of `OtsRunStatus`: jesoos sends
`CommandType.JESOOS_OTS_FINISHED` when the agenda is exhausted, and `OtsWarden.complete()` or
`failStart()` fires when the queue drains or never fills — in both aivox cases only with no listeners
for the completion grace period. jesoos-driven completion arrives at `CommandConsumer` and delegates
entirely to `OtsService.completeOts(slug)`, which looks up the type and calls the same `teardown`.

The aivox-side detection is an **idle-timeout heuristic**: after at least one real song, a queue that
stays drained for the grace period (180 seconds) marks the run done. It is a guard, intended to be
replaced by the explicit jesoos end-signal rather than to be the primary mechanism.

aivox does not persist staleness. The `OtsDefinition` row is marked stale or deleted elsewhere, by a
datanest or metriq cron, and once the row is gone the slug simply reads as `NOT_OTS`.

For a `REPEATABLE` definition the waiting melody is only the cold-start placeholder — no jesoos entries
yet, so melody, then entries arrive and the real stream begins — which is why the next listener hit on
the URL is a legitimate fresh run rather than a resumption.

| Type | Teardown |
|---|---|
| `ONE_SHOT`, also null or unresolved | status `DONE`, slug added to `endedSlugs`; further requests get `OtsAccess.ENDED` and an ended playlist with `#EXT-X-ENDLIST`; a new definition is needed to run again |
| `REPEATABLE` | status `PENDING`, not added to `endedSlugs`; the next URL hit is a fresh cold start with a new agenda |

# Restart reconciliation

In-memory state — `pool.isActive`, `endedSlugs`, the `starting` claim — is lost when the pod restarts,
while `OtsRunStatus` persists, so a crash mid-run leaves a row stuck in `STREAMING`. Before handling
`JESOOS_START_OTS`, `coldStart` reconciles rather than resurrects: a `ONE_SHOT` row is finalized to
`DONE` and answers `ENDED`, and a `REPEATABLE` row is reset to `PENDING` and started fresh. The status
write in `startOts` is part of the reactive chain rather than fire-and-forget, to avoid racing a stale
row.

# Metrics layers

OTS mirrors the radio metric contract, including the mandatory trace propagation rule, tagged by
`streamSlug` — the OTS's own slug — instead of a brand slug.

**Command layer** (`CommandService.startOts`, `ProcessType.FLOW`): `ots_start_received` then
`ots_start_ok` or `ots_start_failed`. Both the REST path (`CommandResource.handleOtsStart`) and the
RabbitMQ `JESOOS_START_OTS` path go through `startOts`. Do not call
`OneTimeStreamService.startFromDefinition` or `.start` directly from a new entry point or these metrics
are silently lost again — that was the actual bug: the RabbitMQ path used to bypass `startOts`, so OTS
runs started over RabbitMQ never appeared in metriq at all.

**Build layer** (`AgendaService.buildOtsAgendaFromScenes`, `ProcessType.INDEPENDENT`):
`scene_content_gap` (warning, `fitSeconds > 360`) per scene, `agenda_build_completed` (information, with
`elapsedMs`, `elapsedSec`, `scenes`) on success, and `agenda_empty_or_failed` (error) on failure — the
same codes and thresholds as the radio build path, so metriq dashboards need no OTS-specific queries.

**Scheduling layer** (`OtsStreamScheduler.scheduleSceneSongs`, `ProcessType.FLOW`): `entries_scheduled`
per scene, mirroring radio's payload of `scene`, `entries` and `currentTime`. Because OTS arms every
scene in one pass, these land as a burst within about a millisecond of each other, one event per scene.

`ots_entry_failed` (error) when an entry's emit chain fails. Payload matches radio's `entry_failed`
shape — `seq`, `scene`, `song`, `promptId`, `errorType`, `error`, `rootCause`, `stackTrace` — plus
`sceneId`, `agentId`, `generated` and `mixingStrategy`. Mutiny's `CompositeException` (the usual
wrapper around `The mapper returned null`) has no useful `getCause()`; the event unwraps
`getCauses()` into `causes` so each inner class, message and stack snippet is visible. The top-level
`error` string alone is not enough to locate which mapper returned null.

**Backpressure:** `backpressure_ignored_ots` (warning) instead of `backpressure_ok` when the slug
resolves to an OTS.

**Cron layer:** `silence_risk` (warning, `ProcessType.CRON`). Note the process type — these do **not**
appear in a FLOW trace view, which is exactly why an OTS teardown once looked causeless for hours. When
an OTS misbehaves, read the CRON stream too.

**Absence of an event is not proof of absence.** Metric publishing is fire-and-forget:
`publishMetric` swallows failures into a logged error, so a genuinely dropped event is invisible in
metriq and visible only in jesoos's log. Read paths differ too — `/metriq/snapshot` is a capped buffer
and will silently omit older events that `/metriq/{slug}/traces` still returns. Before concluding "X
never ran", query the per-slug endpoint and cross-check the agenda's `statusHistory`, which is
authoritative state rather than telemetry.

# Rules for working here

This is settled behaviour — refine, don't redesign.

1. **Never reintroduce a single "brand slug" parameter doing double duty** for routing and for a
   brand-specific lookup such as catalog scope or codec and bitrate defaults. An owner-scoped OTS has no
   brand, so any path assuming one will throw or silently misbehave. Grep `getMasterBrand(` and
   `getMasterBrandId(` before adding a new one, and null-check. DJ status is no longer one of these
   lookups, but the principle still applies to sourcing and defaults.
2. **Keep this and aivox's OTS and radio scope docs coherent** — they are two ends of one contract.
3. **The RabbitMQ message shape is a cross-service contract** (`SongQueueMessageDTO`, `CommandDTO` in
   2next); coordinate before changing field names or semantics.
4. **Never key per-entry state by `sequenceNumber` alone** — it restarts per scene and OTS holds all
   scenes at once. Timer maps, dedup sets and caches all need the scene id in the key.
5. **Don't add a cadence-based auto-stop.** An OTS emits ahead of playback and legitimately goes quiet
   between entries; only the agenda's own completion or an explicit `JESOOS_STOP_OTS` may tear one down.
6. **An OTS lives only in memory.** `OneTimeStreamPool` and the Vert.x timers have no persistence, so
   anything that rebuilds the CDI context destroys a running OTS with no metric and no command — the
   agenda simply 404s while aivox plays out its buffer. In dev that includes **Quarkus hot reload**,
   which is why `quarkus.live-reload.enabled=false` is set in `application.properties`: recompiling
   while a stream is live would silently kill it and look like a product bug. Restart jesoos
   deliberately, between runs, never during one.

# Key files

| Area | File |
|---|---|
| OTS lifecycle (create, start, delete) | `service/OneTimeStreamService` |
| OTS emission scheduling | `live/OtsStreamScheduler` |
| In-memory OTS registry | `live/OneTimeStreamPool` |
| Stream model | `model/stream/OneTimeStream`, `model/stream/AbstractStream` |
| REST | `rest/OtsResource` |
| Definition (datanest-owned data) | `repository/OtsDefinitionRepository`, 2next `OtsDefinition` |
| Shared with radio | `agenda/AgendaService.buildOtsAgenda`, `agenda/TimelineBuilder`, `live/SongEmitter`, `live/JingleSongEmitter`, `live/GeneratedContentEmitter`, `messaging/QueueSupplier` |
