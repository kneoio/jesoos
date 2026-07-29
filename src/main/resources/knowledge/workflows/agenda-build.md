---
type: Workflow
title: Agenda build
description: Internal jesoos pipeline that turns a script into a StreamAgenda — slot expansion, per-scene song fetch, timeline build and prompt assignment.
tags: [agenda, build, agendaservice, livescene, timeline, slots, fitseconds, traceid]
audience: [developer]
---

# Agenda build

`AgendaService` turns a script into a `StreamAgenda`: an ordered set of `LiveScene`s plus timezone and
build timestamp, held per brand in `BrandPool`.

Entry points:

* `getStreamAgenda(brand, user)` and `getStreamAgenda(brand, scriptId, user)` — the daily or on-start
  build.
* `buildOtsAgenda(brand, scriptId, startTime, user)` — the one-time-stream build: sequential, fixed
  per-scene durations, no loop baseline.

# Pipeline

```
scriptService.getById → sceneService.getAllWithPromptIds
→ orderedSceneSet (sort by seqNum, then id)
→ filter isActiveOnWeekday (ISO 1..7; empty set = every day)
→ expand into time slots:
     • each scene startTime → a SceneTimeSlot
     • no start times at all → the LOOP scene becomes the 00:00 baseline
→ sort slots by start, compute the gap to the next slot (wraps past midnight)
→ ExpandedSlot list:
     • LOOP slot → fills its whole gap
     • ONE_TIME  → fixed 60s, remaining gap back-filled by the current LOOP scene
→ per ExpandedSlot, a sequential Uni chain carrying a usedIds exclusion set:
     fetchSongsForSceneWithDuration  (retry once with exclusions cleared if the pool is too short)
   → convertToSongEntries
   → TimelineBuilder.buildTimeline
   → assignPromptsToTimeline
   → LiveScene → schedule.addScene
```

# Rules baked in here

The loop is the baseline and one-time scenes preempt it; a gap is always filled by the current loop
scene and never left empty.

Cross-scene de-duplication runs through `usedIds`, honoured by `RANDOM` **and** `QUERY`, with
`SourceType.STREAM` sources exempt. If the catalog cannot fill a scene the exclusion set is reset once
rather than emitting silence.

**Non-repetition outranks matching the scene's criteria.** Slots are filled along the four-rung ladder
described in the song selection concept, and the rung order must not be reordered — dropping a scene's
filter is deliberately preferred to replaying one of its songs. Matched songs sit at the head of the
pool and are always consumed first.

The song-count heuristic is `max(10, ceil(effectiveDuration / 150))`, and generated scenes subtract
`AVERAGE_GENERATED_CONTENT_DURATION_SECONDS` from the budget first. That is a target for *fetching*
only: `selectDistinctSongsToFillDuration` then consumes just as many as the budget needs, and
un-consumed songs are never marked used, so they stay available to later scenes.

# Runtime shapes

`LiveScene` carries the timeline, content status, trace id, agent id, the one-time flag and
`fitSeconds`. A `TimelineEntry` is one emission unit: one or two songs plus a `MixingType`, a
`scheduledEmissionTime`, an estimated duration, intro and jingle flags, and a `TimelineEntryStatus`.
A `PromptEntry` is the per-song intro assignment — a prompt id **or** a `CustomAction`, plus a language
code; empty means no intro.

`fitSeconds` is `sceneDuration − actualContentDuration`: positive is an underfill gap, a large negative
means overshoot that was trimmed.

# Prompt and language assignment

`assignPromptsToTimeline` uses the brand's `AiAgent`, which is mandatory — any `agent == null` guard in
the code is defensive legacy, not an optional path. The pool is the scene's active `introPrompts` plus
`actions`. For each entry marked `hasIntro`, at the intro-bearing song index (`introAtIndex`), it
assigns a random prompt or action and a weight-selected language onto the `PromptEntry`.

# Metrics

`agenda_build_completed` is published on every build, and `scene_content_gap` when `fitSeconds > 360`.
One `buildTraceId` threads the whole build.

# Key files

| Area | File |
|---|---|
| Build orchestration | `agenda/AgendaService` |
| Song sourcing | `agenda/ScheduleSongSupplier` |
| Timeline and mixing | `agenda/TimelineBuilder`, `agenda/MixingTypeShuffler`, `agenda/MixingStrategy` |
| Prompt and language resolution | `service/PromptService`, `util/AiHelperUtils` |
| Daily rebuild | `maintenance/DailyAgendaRebuildService` |
| Models | `model/stream/StreamAgenda`, `LiveScene`, `TimelineEntry`, `SongEntry`, `PromptEntry` |
