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
| DJ intros (TTS) | Follow the master brand's live `isDjEnabled` toggle | **Always off** — there is no brand DJ toggle to check (see §3) |
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

**A previous bug conflated this with the DJ-toggle check.** `SongEmitter`/`JingleSongEmitter`
also call `djStateService.isDjEnabled(...)` to decide whether to generate a TTS intro — that
toggle is genuinely per-**brand** (`CommandService.enableDj`/`disableDj`, REST/RabbitMQ), not
per-OTS-slug; nobody ever calls `enableDj(theOtsSlug)`. `OtsStreamScheduler` used to read
`stream.getMasterBrand().getSlugName()` once and pass that single value for *both* routing and
the DJ check — which NPE'd the moment `masterBrand` was null (owner-scoped OTS), and would have
been the wrong value for routing even when it wasn't null. The fix threads **two** separate,
explicitly-named values from `scheduleStream` down through `scheduleSceneSongs` →
`scheduleEntry` → `emitEntry` → `SongEmitter.send`/`JingleSongEmitter.send`:
- `streamSlug` — always `stream.getSlugName()`, never null. Routing + tagging.
- `djBrandSlug` — `stream.getMasterBrand().getSlugName()` if a master brand exists, else `null`.
  DJ-toggle check only; `SongEmitter`/`JingleSongEmitter` treat `null` as "no brand to check →
  no intros" rather than calling into `DjStateService` with a null key.

Don't re-merge these into one parameter — that's exactly the confusion that caused the bug.

## 4. Lifecycle

1. **Create (dormant).** `OneTimeStreamService.run(brandSlugName, scriptId, vars, user)` (legacy,
   brand-required REST path) or `startFromDefinition(slugName)` (datanest-backed, supports both
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
4. **Complete → teardown (intended; not fully wired yet).** When an OTS finishes, everything it
   created should be cleaned up — removed from the pool, timers cancelled, streams shut down. The
   teardown methods exist (`OtsStreamScheduler.cancelOtsTimers`, `OneTimeStreamService.delete` →
   `pool.stopAndRemove`; aivox `stopAndRemoveStation` → `shutdown`), but **automatic
   completion-triggered cleanup is a TODO** — today teardown runs only via an explicit
   delete/stop, not when the last scene ends.

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

## 6. Rules for agents working here

Same spirit as `../agenda/RADIO_WORKFLOW.md` §6 — this is settled behavior, refine don't
redesign. In particular:
1. **Never reintroduce a single "brand slug" parameter that does double duty** for routing and
   for a brand-specific lookup (DJ toggle, catalog scope). Owner-scoped OTS has no brand — any
   code path that assumes one will NPE or silently misbehave for it. Grep `getMasterBrand(`/
   `getMasterBrandId(` before adding a new one; null-check.
2. **Keep this doc and aivox's `OTS_SCOPE.md`/`RADIO_SCOPE.md` coherent.** They describe the two
   ends of the same contract (jesoos decides *when*/*what* to emit; aivox decides *how* to serve
   the station). A routing-identity or scope-semantics change on one side is a change to both.
3. **RabbitMQ message shape is a cross-service contract** (`SongQueueMessageDTO`, `CommandDTO` in
   2next) — coordinate before changing field names or semantics, same rule as radio.

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
