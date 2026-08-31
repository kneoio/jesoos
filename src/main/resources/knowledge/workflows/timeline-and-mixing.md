---
type: Workflow
title: Timeline and mixing types
description: How TimelineBuilder lays out emission entries, which MixingType each transition gets, and where the build-time choice is re-decided at emission.
tags: [timeline, mixingtype, timelinebuilder, mixingtypeshuffler, crossfade, jingle, downgrade]
audience: [developer]
---

# Timeline building

`TimelineBuilder` walks the song pool, asking `MixingTypeShuffler.selectStrategy` at each step. The
strategy depends on the remaining songs, the scene's talkativity, whether intros are allowed, and
anti-repetition counters — no three or more identical mix types, and no three or more consecutive
two-song or intro entries. Generated scenes get a leading generated slot before any songs.

`scheduledEmissionTime` advances by `entryDuration − crossfadeOverlap`, because crossfades overlap and
wall-clock time is therefore shorter than the sum of the durations.

Overshoot beyond `INTRO_TRIM_OVERSHOOT_THRESHOLD_SECONDS` (30 seconds) downgrades the last entry's mix
type via `INTRO_DOWNGRADE`, dropping its intro rather than letting the scene run long. The builder then
sets `fitSeconds` and `timelineBuild = true` on the scene.

# MixingType families

`MixingType` is the recipe aivox uses to stitch one entry, travelling in the DTO as `mergingMethod`, so
the set is a cross-service contract — jesoos may only emit what aivox dispatches on.

| Family | Types |
|---|---|
| No TTS | `SONG_ONLY`, `SONG_CROSSFADE_SONG` (and `_VAR_1`), `FILLER_JINGLE`, `NOT_MIXED` |
| With spoken intro | `INTRO_SONG`, `LISTENER_INTRO_SONG`, `JINGLE_INTRO_SONG`, `SONG_INTRO_SONG`, `INTRO_SONG_INTRO_SONG` |
| Generated | `JINGLE_GENERATED_JINGLE`, `JINGLE_GENERATED_JINGLE_WITH_BACKGROUND`, `INTRO_JINGLE_GENERATED_JINGLE_WITH_BACKGROUND` |

The generated set is **open** — more types may be added.

`SONG_INTRO_SONG` and `INTRO_SONG_INTRO_SONG` produce **two** mixed fragments in aivox and must stay
together as an ordered pair.

# The build-time choice is not final

Do not assume the type chosen at build survives to air. It is re-decided at emission in several places:

* DJ boost and warm-up **upgrade** a silent entry to an intro type.
* With the DJ offline, `SongEmitter` downgrades to a no-intro type (`getNoIntroMergingTypes`), and
  `JingleSongEmitter` sends `FILLER_JINGLE` — or `JINGLE_INTRO_SONG` when the DJ is on and the entry
  has an intro.
* A song with no prompt and no custom action is not an error: emitters skip TTS and send a no-intro
  type (`SONG_ONLY` / `SONG_CROSSFADE_SONG` / `FILLER_JINGLE`).
* `introAtIndex` and `needsIntroAtIndex` encode that `SONG_INTRO_SONG` introduces only the **second**
  song.

# OTS restriction

On the OTS path aivox supports only `INTRO_SONG`, `LISTENER_INTRO_SONG` and `SONG_CROSSFADE_SONG`.
Anything else sent with an `otsSlugName` will not be mixed. `selectOtsStrategy` therefore never emits
jingles.

A **LOOP** scene uses a definition-level talkativity override when one exists (`scene_talkativities`,
same lookup as `scene_durations`), then the same coin-flip as radio (`Math.random() < talkativity`,
plus the two-consecutive-intro cap unless talkativity is 1.0), just with those OTS-safe types.
Song fill uses that same resolved value so the pool is sized for the expected intro mix. No
override means 1.0. `ONE_TIME` scenes still always intro when they have active intro content.

OTS still honors `allowIntros`. A scene with no active intro prompt or custom action is mixed as
`SONG_ONLY` (one song) or `SONG_CROSSFADE_SONG` (two). The same fallback applies at emission if an
intro type was chosen but the song has nothing to say.

# Key files

`agenda/TimelineBuilder`, `agenda/MixingTypeShuffler`, `agenda/MixingStrategy`.
