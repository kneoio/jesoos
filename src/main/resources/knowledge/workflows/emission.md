---
type: Workflow
title: Emission
description: How a built agenda reaches aivox — scene selection, staggered entry scheduling, the entry state machine, lead time, backpressure and the silence watchdog.
tags: [emission, ticker, scheduler, timelineentrystatus, backpressure, queuesupplier, silence]
audience: [developer]
---

# Emission

```
BrandPool.getRadioStream(brand)        // build + store; forceIntroOnFirstEntry (warm-up)
  → the pool holds RadioStream{agenda}

AgendaTicker  @Scheduled 60s           // WHICH scene is live now
  • findActiveOneTime — window [start, start+dur), latest wins, finished scenes skipped
  • else findLoopingScene — absolute [start, end) window, latest started, cross-midnight aware
  → ScenePool.setActiveScene(brand, scene)   (+ scene_started metric, TriggerContext)

SceneTicker   @Scheduled 15s           // arm the entries of the active scene
  → StaggeredSongScheduler.scheduleSceneSongs(brand, scene)

StaggeredSongScheduler                 // WHEN each entry fires
  • per PENDING entry: skip if fully in the past; apply the DJ boost intro if enabled
  • schedule a Vert.x timer at (emissionTime − aivoxDelaySeconds lead)
  • at fire: deadline and backpressure checks → EMITTING → emitTimelineEntry
        generated → skip if DJ off, else GeneratedContentEmitter
        jingle    → JingleSongEmitter
        else      → SongEmitter
  → QueueSupplier.sendSongsToQueue → RabbitMQ "streaming" (routingKey = brandSlug) → aivox
```

`TriggerContext` is how the ticker classifies an activation relative to its start: `ON_TIME` or `LATE`.

# Invariants

**Two tickers, two jobs.** `AgendaTicker` at 60 seconds only selects the active scene; `SceneTicker` at
15 seconds plus `StaggeredSongScheduler` only schedule and emit its entries. They are never merged or
crossed.

One-time scenes preempt the loop at runtime too — `findActiveOneTime` runs before `findLoopingScene`,
bounded by the scene's own window so stale instances cannot latch.

**Status is a state machine.** `TimelineEntryStatus` moves `SCHEDULED → PENDING → … → EMITTING →
COMPLETED | FAILED | SKIPPED`, transitioned with `compareAndSet` so it stays idempotent under the
15-second re-tick. Status is never reset ad hoc.

Entries fire `aivoxDelaySeconds` early so aivox has time to mix; entries past the scene `endTime` are
`SKIPPED`, not emitted late.

Backpressure (`backpressure(brand)`) queues skip counts consumed at fire time, and a failed entry
triggers the next immediately (`triggerNextEntry`). The skip counter is **radio-only**: OTS entries never
pass through this scheduler, so the command short-circuits for an OTS slug.

`cancelBrandTimers` runs on scene change, removal and shutdown — always cancel when deactivating.

Every stage emits metrics carrying the propagated `traceId` / `emissionTraceId`, and trace propagation
must be preserved end to end.

# Metrics and the silence watchdog

`scene_started`, `entries_scheduled`, `entry_emitting_started`, `entry_emitted`, `entry_failed` and
`cascade_entry_failed` trace the path.

`trackEmission(brand, durationSeconds)` records when the last emitted content should finish, and a
`@Scheduled` 60-second `checkSilenceRisk` publishes a `silence_risk` warning when a brand is overdue past
`SILENCE_GRACE_SECONDS` (120). This is the primary "is the station actually on air?" signal, so
`trackEmission` calls must stay in step with real emissions.

It only warns and never stops a stream. Note that `trackEmission` sits on the emit **success** path only,
so a failed entry leaves the expectation stale and the warning then fires against an out-of-date
timestamp. Call `clearTracking(brand)` when a stream genuinely stops, or the slug stays flagged forever.
An earlier OTS auto-stop built on this signal was removed, because cadence is the wrong basis for a
teardown decision.

# Key files

| Area | File |
|---|---|
| Scene selection (live) | `live/AgendaTicker`, `live/ScenePool`, `live/BrandPool` |
| Entry scheduling | `live/SceneTicker`, `live/StaggeredSongScheduler` |
| Emitters | `live/SongEmitter`, `live/JingleSongEmitter`, `live/GeneratedContentEmitter` |
| Publish to aivox | `messaging/QueueSupplier`, `messaging/CommandPublisher` |
| Metrics | `messaging/MetricPublisher` |
