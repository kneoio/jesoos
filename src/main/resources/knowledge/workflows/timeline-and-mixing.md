---
type: Workflow
title: Timeline and mixing types
description: How TimelineBuilder lays out emission entries and which MixingType each transition gets, including the intro downgrade rule.
tags: [timeline, mixingtype, timelinebuilder, mixingtypeshuffler, crossfade, jingle]
audience: [developer]
---

# Timeline and mixing types

`TimelineBuilder` walks the scene's song pool and emits `TimelineEntry` objects;
`MixingTypeShuffler` picks the `MixingType` for each step from the remaining songs, the scene's
talkativity, whether intros are allowed, and anti-repetition rules — no three or more identical mix
types in a row, no three or more consecutive two-song entries, no three or more consecutive intro
entries. Generated scenes get a leading generated slot.

`scheduledEmissionTime` advances by `entryDuration − crossfadeOverlap`. If the accumulated overshoot
exceeds `INTRO_TRIM_OVERSHOOT_THRESHOLD_SECONDS` (30 seconds), the last entry is downgraded with
`INTRO_DOWNGRADE` rather than letting the scene run long.

# MixingType families

The value travels to aivox as the DTO field `mergingMethod`, so the set is a cross-service contract —
jesoos may only emit types aivox dispatches on.

| Family | Types |
|---|---|
| No TTS | `SONG_ONLY`, `SONG_CROSSFADE_SONG` (and `_VAR_1`), `FILLER_JINGLE`, `NOT_MIXED` |
| With spoken intro | `INTRO_SONG`, `LISTENER_INTRO_SONG`, `JINGLE_INTRO_SONG`, `SONG_INTRO_SONG`, `INTRO_SONG_INTRO_SONG` |
| Generated block | `JINGLE_GENERATED_JINGLE`, `JINGLE_GENERATED_JINGLE_WITH_BACKGROUND`, `INTRO_JINGLE_GENERATED_JINGLE_WITH_BACKGROUND` |

Two of them — `SONG_INTRO_SONG` and `INTRO_SONG_INTRO_SONG` — produce **two** mixed fragments in
aivox and must stay together as an ordered pair.

The type chosen at build time is not final: at emission it can be upgraded by DJ boost or warm-up, or
downgraded when the DJ is switched off.

# OTS restriction

On the OTS path aivox only supports `INTRO_SONG`, `LISTENER_INTRO_SONG` and `SONG_CROSSFADE_SONG`.
Anything else sent with an `otsSlugName` will not be mixed.
