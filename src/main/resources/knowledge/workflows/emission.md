---
type: Workflow
title: Emission
description: How a built agenda becomes live messages — the two tickers, staggered scheduling, entry state machine, lead time and backpressure.
tags: [emission, ticker, scheduler, timelineentrystatus, backpressure, queuesupplier]
audience: [developer]
---

# Emission

```
BrandPool.getRadioStream → RadioStream{agenda}
AgendaTicker  @60s → pick the active scene (ONE_TIME window first, else LOOP)
SceneTicker   @15s → StaggeredSongScheduler.scheduleSceneSongs
StaggeredSongScheduler → Vert.x timer at (emissionTime − aivoxDelaySeconds)
  → EMITTING → SongEmitter / JingleSongEmitter / GeneratedContentEmitter
  → QueueSupplier → RabbitMQ "streaming", routingKey = brandSlug
```

# Invariants

The two tickers are two different jobs at two different cadences and must never be merged: one picks
the scene, the other schedules the entries inside it.

`TimelineEntryStatus` moves `SCHEDULED → PENDING → … → EMITTING → COMPLETED | FAILED | SKIPPED`, and
every transition goes through `compareAndSet` — an entry is claimed once.

Entries fire **early** by the configured lead time so aivox has time to mix; an entry whose scene
`endTime` has already passed is marked `SKIPPED` rather than emitted late.

Backpressure (`backpressure(brand)`) applies to radio only — the OTS path short-circuits it and
publishes `backpressure_ignored_ots` at warning level.

`cancelBrandTimers` runs on scene change, stream removal and shutdown, so timers never outlive the
agenda that created them.

# Metrics

`scene_started`, `entries_scheduled`, `entry_emitting_started`, `entry_emitted`, `entry_failed` and
`cascade_entry_failed` trace the path. A `@Scheduled` 60-second `checkSilenceRisk` watchdog publishes
`silence_risk` after `SILENCE_GRACE_SECONDS` (120) — it warns only and never stops the stream.
