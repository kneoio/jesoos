---
type: Workflow
title: One-time stream internals
description: Routing identity, RabbitMQ binding, cold start, scheduling differences, teardown timing and restart reconciliation for OTS.
tags: [ots, routing, rabbitmq, coldstart, teardown, otsruntype, scheduler]
audience: [developer]
---

# Routing identity

An OTS is always addressed by `stream.getSlugName()` — that slug is both the RabbitMQ routing key and
the `otsSlugName` tag on the message. The historical bug here was conflating routing with the DJ
check and using the master brand slug for both. The fix passes a `boolean djOn` into `SongEmitter` and
`JingleSongEmitter`: radio resolves it from `DjStateService`, OTS always passes `true`.

# RabbitMQ binding

An OTS binds its **own** routing key exactly like a radio station:
`initializeOtsStation` → `queueBind(queueName, STREAMING_EXCHANGE, otsSlugName)`, and
`removeOtsStation` unbinds it. jesoos publishes with `routingKey = streamSlug`, which is the brand
slug for radio and the OTS slug for an OTS.

Earlier prose claiming an OTS has no binding and shares the master brand's channel describes the bug,
not the behaviour: with no binding the messages were silently unroutable. A definition with
`brandId == null` is an owner-scoped OTS, not a defect.

# Cold start

There is no REST start endpoint. The first HLS request to `/live/<slug>` reaches
`StreamingResource` → `StreamingService.ensureOtsStarted` → `OtsService.ensureStarted`, which reads
the definition from `OtsDefinitionRepository` (`mixpla__ots_definitions`). An unknown slug is
`NOT_OTS` and answers 404. `OtsService.coldStart` refuses only when there is **neither** a brand
**nor** an agent — an owner-scoped OTS starts on its agent alone, using an in-memory `Brand` stand-in
that carries defaults and is never persisted.

The station is created through `LiveStreamPool.initializeOtsStation`, `OtsWarden` starts with a
waiting melody from `WaitingAudioProvider`, and exactly one REST trigger goes to jesoos —
`sendJesoosOtsCommand(slug, "start")` — which builds the agenda and starts emitting with
`otsSlugName` set. A second listener arriving during STARTING or ACTIVE simply attaches; the
`starting` claim prevents a duplicate trigger.

# Scheduling

`OtsStreamScheduler` schedules **all** scenes upfront in a single pass, unlike radio's per-scene
replanning. Lead time is `jesoos.aivox-delay-seconds`, default 60. Timer keys must be
`sceneId:sequenceNumber` — a sequence number alone collides across scenes, which was a production bug.

Both skip paths, past-window at schedule time and past `endTime` at fire time, are silent and publish
no metric.

A song injected from the event chat (`PlaySongForOtsToolHandler`) goes straight to aivox through
`internalRestCall.addSongToQueue` at interrupt priority. It does **not** shift the agenda or its
timers, so TTS is still paid for displaced entries, and the injected duration is not recorded back
into the estimates.

Teardown waits until `max(plannedDeadline, trackedEnd + aivox-delay-seconds)`, because real TTS runs
past the 10-second budget and a tighter deadline cut off the closing goodbye.

# Completion and run types

Two paths reach `OtsService.teardown`, and aivox is the sole writer of `OtsRunStatus`: jesoos sends
`CommandType.JESOOS_OTS_FINISHED` when the agenda is exhausted, and `OtsWarden.complete()` or
`failStart()` fires when the queue drains or never fills. `JESOOS_STOP_OTS` is queue-only and, unlike
natural completion, does **not** notify aivox.

Behaviour then branches on `OtsDefinition.type`:

| Type | Teardown |
|---|---|
| `ONE_SHOT` (also null or unresolved) | status `DONE`, slug added to `endedSlugs`; further requests get `OtsAccess.ENDED` and an ended playlist with `#EXT-X-ENDLIST`; a new definition is needed to run again |
| `REPEATABLE` | status `PENDING`, not added to `endedSlugs`; the next URL hit is a fresh cold start with a new agenda |

# Restart reconciliation

In-memory state (`pool.isActive`, `endedSlugs`, the `starting` claim) is lost when the pod restarts,
while `OtsRunStatus` persists — so a crash mid-run leaves a row stuck in `STREAMING`. Before handling
`JESOOS_START_OTS`, `coldStart` reconciles rather than resurrects: a `ONE_SHOT` row is finalized to
`DONE` and answers `ENDED`, a `REPEATABLE` row is reset to `PENDING` and started fresh. The status
write in `startOts` is part of the reactive chain, not fire-and-forget, to avoid racing a stale row.

OTS state is in-memory, so Quarkus hot reload kills live streams — run with
`quarkus.live-reload.enabled=false`.
