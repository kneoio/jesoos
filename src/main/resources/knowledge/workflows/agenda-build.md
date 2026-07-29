---
type: Workflow
title: Agenda build
description: Internal jesoos pipeline that turns a script into a StreamAgenda — slot expansion, per-scene song fetch, timeline build and prompt assignment.
tags: [agenda, build, agendaservice, livescene, timeline, slots, fitseconds]
audience: [developer]
---

# Agenda build

`AgendaService` turns a script into a `StreamAgenda`: an ordered set of `LiveScene`s plus timezone
and build timestamp, held per brand in `BrandPool`. Radio entry points are
`getStreamAgenda(brand, user)` and `getStreamAgenda(brand, scriptId, user)`; OTS uses
`buildOtsAgenda`, which runs scenes sequentially with fixed durations and no loop baseline.

# Pipeline

```
scriptService.getById → sceneService.getAllWithPromptIds
→ orderedSceneSet (seqNum, id)
→ filter isActiveOnWeekday (ISO 1..7; empty set = every day)
→ expand time slots (each startTime → SceneTimeSlot; no start times → LOOP at 00:00)
→ sort slots, compute gap to next (wraps midnight)
→ ExpandedSlot: LOOP fills the whole gap; ONE_TIME takes a fixed 60s, remainder back-filled by LOOP
→ per slot, sequential Uni with usedIds exclusion:
     fetchSongsForSceneWithDuration  (retry once with exclusions cleared if the pool is too short)
   → convertToSongEntries
   → TimelineBuilder.buildTimeline
   → assignPromptsToTimeline
   → LiveScene → schedule.addScene
```

# Invariants

The loop is the baseline and one-time scenes preempt it, but a gap is never emitted empty.
De-duplication across scenes runs through `usedIds`; `SourceType.STREAM` sources are exempt, and both
`RANDOM` and `QUERY` honour the exclusion.

Song count per slot is a heuristic: `max(10, ceil(effectiveDuration / 150))`. Generated scenes first
subtract `AVERAGE_GENERATED_CONTENT_DURATION_SECONDS` from the effective duration.

# Runtime shapes

`LiveScene` holds the timeline, content status, trace id, agent id, a one-time flag and
`fitSeconds`. A `TimelineEntry` is the emission unit: one or two songs, a `MixingType`, a scheduled
emission time, a duration estimate, intro/jingle flags and a `TimelineEntryStatus`.

`fitSeconds` is `sceneDuration − actualContentDuration`; a positive value is an underfill gap.

# Prompt assignment

`assignPromptsToTimeline` requires the brand's `AiAgent`. For every entry flagged `hasIntro` it picks
a random prompt or action and selects the language by weight from the agent's preferred languages.

# Metrics

`agenda_build_completed` is published on every build. `scene_content_gap` is published when
`fitSeconds` exceeds 360 seconds.
