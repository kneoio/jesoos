---
type: Workflow
title: Song selection redesign (proposal, not implemented)
description: A reviewed but unimplemented plan to replace song selection with play-recency rotation and an adjacency guard, including per-sourcing behaviour, persistence, deletions and OTS isolation.
tags: [proposal, song-selection, rotation, adjacency, not-implemented, design, oversample]
audience: [developer]
---

# Status: proposal only

**Nothing in this concept is implemented.** Current behaviour is described in the song selection
concept, and the two disagree deliberately. Never answer a question about how selection works today
from this document.

The proposal replaces the song-selection half of the radio build only — `ScheduleSongSupplier`, the SQL
bucket queries and the reuse ladder in `AbstractAgendaService`. Timeline building, mixing, emission and
metrics stay unchanged, and one-time streams are untouched.

The goal is radio selection that rotates fairly and never feels repetitive: songs cycle by how long ago
they aired, respect boost and the `new` tag, differ by sourcing mode, and never play the same track twice
in a row.

# Requirements

**R0 — never the same song twice in a row.** The hard invariant, above all others: the worst on-air
failure is a song playing back to back with itself. It must hold at every seam — within a scene's reuse
rung, **across a scene boundary** so the last song of scene *N* differs from the first of scene *N+1*,
**inside a two-song crossfade entry**, and **after priority force-placement**,
`repositionPastPrioritySongs` or `replacePrioritySong`.

**R1 — rotation.** A song that just played does not return until the rest of the eligible pool has had
its turn; least-recently-played returns first.

**R12 — new catalog additions get a head start.** "Old versus new" has two axes: play recency
(`last_time_played_by_brand`) and catalog age (`reg_date`, when the song entered the brand). Rotation is
driven by play recency; catalog age only breaks the tie **within the never-played NULL block**, so the
newest additions air before the old never-played back catalogue (`reg_date DESC`). After a first spin
catalog age no longer matters. It is a stable tiebreak, not a persistent freshness bias.

**R2 — cross-build and cross-midnight memory.** Rotation survives rebuilds, restarts and the day
boundary, which is what the persistence section provides.

**R3 — no duplication within a build, across scenes too.** One build never schedules the same song twice
while unplayed songs remain. Reuse is a defined last rung, ordered by recency and never adjacent — not an
emergency "reset everything".

**R4 — catalog boost respected.** `SUPER_BOOST` about 4×, `BOOST` about 2×, normal 1×, `QUARANTINE`
suppressed. Boost biases *probability* and never breaks rotation into determinism.

**R5 — the `new` label is temporary one-shot priority.** A shared fragment tagged `new` (attached in
datanest, arriving via `sharedInfo.priority`) must be **guaranteed** to play on the next build, then
cleared exactly once so a later build does not re-float it. It jumps the rotation queue by design,
one-song-for-one-song so no schedule time shifts. The contributor email flow depends on that guarantee,
so "eligible" is not enough — it must be force-placed.

**R6 — talkativity unchanged.** It governs intros in `TimelineBuilder`, not song identity. Selection only
guarantees enough songs to fill the budget.

**R7 — cross-midnight time math stays as it is.** Out of scope, see below.

**R8 — fast rebuild.** One query per scene, no per-song round trips.

**R9 — no fallbacks and no legacy.** No silent reset-and-retry paths; reuse is the single defined rung.

**R10 — OTS untouched and isolated.**

**R11 — sourcing mode drives behaviour** rather than one-size-fits-all.

# The selection model

One idea: a recency gate, then a boost-weighted random pick.

```
1. Candidate query (single SQL):
     WHERE brand matches, archived = 0, boost > -1        -- quarantine excluded
           id NOT IN (usedIds)                            -- cross-scene dedup, R3
           [+ genre/label/search conditions if QUERY sourcing]
     ORDER BY last_time_played_by_brand ASC NULLS FIRST,  -- R1: stale and never-played first
              reg_date DESC                               -- R12: newest additions lead the NULL block
     LIMIT K = ceil(needed * OVERSAMPLE)                  -- OVERSAMPLE = 3

2. Boost-weighted random pick of `needed` from that stale window:
     weight = CASE boost WHEN 2 THEN 4 WHEN 1 THEN 2 ELSE 1 END

3. Priority "new"-label shared songs are force-placed at the head, bypassing the gate, then their
   label is cleared, one-shot.

4. If the query returns fewer than `needed`, take from the same recency order again,
   least-recently-played first, never adjacent. That is the defined last rung, not a reset.
```

A subtlety the model introduces: a `new` song has never played, so its `last_time_played_by_brand` is
NULL and NULLS-FIRST already sorts it to the very front of the candidate window — it is always
*eligible*. But eligibility is not a guarantee, because the boost-weighted pick could still skip it. So
priority is force-included ahead of the random pick, exactly as `floatPriorityToFront` does today.
Recency ordering makes new songs surface naturally on later builds too, but the one-shot guarantee stays
explicit.

`OVERSAMPLE` is the one tuning knob and the only documented lever: 1 is pure least-recently-played
determinism, higher gives more variety among stale songs. It starts at **3**.

# The adjacency guard

Selection, widening and priority all operate per scene, so none of them can see a scene boundary. R0 is
therefore enforced by one final pass over the concatenated cross-scene timeline, after everything else:

```
flatten all scenes' entries in emission order → a sequence of songs
for each adjacent pair (prev_trailing_song, next_leading_song):
    if same id:
        replace next_leading_song with the next recency-ordered candidate
        from that scene's remaining pool whose id differs from prev_trailing_song
        and from the song that would follow it        -- don't create a new adjacency
    if no such candidate exists (single-song catalog):
        log adjacency_unavoidable and leave it
also check that the two songs inside every 2-song entry differ, swapping the second if not
```

This is the only place adjacency is guaranteed and it is the last thing to touch the timeline, so a
forced `new` song or a boundary join cannot slip an adjacency past it. If a brand truly owns one song,
adjacency is physically unavoidable and is logged rather than hidden.

# Per-sourcing behaviour

| Sourcing | Rotation | Boost weight | Query conditions | Notes |
|---|---|---|---|---|
| `RANDOM` / default | yes | yes | type only | the candidate query verbatim; the common case |
| `QUERY` | yes | yes | filter is a **preference, not a constraint** | matched songs are a priority head, recency-ordered and each used once, then the pool widens with **any** song rather than replaying a match |
| `STATIC_LIST` | no, exempt | no | explicit ids, pinned order | an author's fixed curation, returned literally; rotation and boost must not reorder it, and no play-count filtering |
| `GENERATED` | n/a | n/a | none | empty pool, content produced at emit, untouched |

`QUERY` deserves the emphasis: such a scene must never replay one of its own matched songs while any
unplayed song exists in the brand, matched or not. The filter therefore only *ranks*, never *restricts* —
matched songs on rung 1, any song with the filter dropped on rung 2 filling the entire remainder, and
recency-ordered reuse on rung 3 only when the whole catalog is spent. Avoiding repetition is treated as
more important than matching the search: even when the filter matches enough songs, fresh unmatched songs
are preferred over replaying matched ones. That is the existing `widenToFill` philosophy made the
explicit default rather than a short-match fallback.

Ladder interaction, now recency-aware: `RANDOM` short of budget widens with any song in recency order
(`getAnySongs` reworked); `QUERY` widening is the normal path; `STATIC_LIST` does not rotate but still
widens when the pinned list is shorter than the budget, and that widening fill *is* recency-ordered even
though the pinned head is not; shared `new`-label priority floats to the head on `RANDOM` as today and
arrives via widening on `QUERY`, since the shared catalog query is type-only and that known genre and
label gap is out of scope here.

`STATIC_LIST` staying literal is a hard requirement — it is the one mode where a listener wants the exact
same songs in the exact same order.

# Play persistence

The columns already exist on `mixpla__brand_sound_fragments` (`played_by_brand_count`,
`last_time_played_by_brand`), so there is **no DDL and no schema change** — they simply start being
written.

The write goes in `SongEmitter.send` after `queueSupplier.sendSongsToQueue` succeeds, the same success
point where contributor notification already runs, on both the DJ-on and DJ-off branches, per song in the
entry, skipping `SourceType.STREAM`:

```sql
UPDATE ... SET played_by_brand_count = COALESCE(played_by_brand_count,0) + 1,
               last_time_played_by_brand = now()
WHERE brand_id = ? AND sound_fragment_id = ?
```

It is fire-and-forget with a logged failure, like play history today: a missed write only makes that song
look slightly staler than it is, self-healing on the next play and never fatal. It needs `brandId` at
emit time — `SongEmitter` holds the `ILiveStream` and radio streams expose it. OTS streams do not record
through this path, so the write is gated on radio and brand scope to keep OTS clean.

This is the only emit-path change in the whole redesign.

# What gets deleted

* `ScheduleSongSupplier.getSongsRandomly`'s three-bucket newest/oldest/random merge.
* `SoundFragmentBrandRepository.findByFilter` and `findByFilterOldest`, the two dead-column sorts —
  keeping one recency-ordered query instead, either by reworking `findByFilterRandom` into the candidate
  query or adding a single `findCandidatesByRecency`.
* The exhaustion-reset branch in `RadioAgendaService.buildAgenda` (`state.usedIds().clear()`), replaced
  by the recency reuse rung.
* The misleading names, in favour of names that say what they do (`findRotationCandidates` and so on).

`widenToFill` and `getAnySongs` keep the concept of dropping the filter, but now share the same
recency-ordered query with the filter conditions removed. The rungs stay: matched, then widened, then
recency reuse.

# OTS isolation — shared base, radio overrides

The chosen approach keeps the shared base and has radio override the selection strategy only.

`AbstractAgendaService` keeps `fetchSongsForSceneWithDuration` and `selectDistinctSongsToFillDuration` as
the OTS path, unchanged, so `OtsAgendaService` keeps calling exactly what it calls today, byte for byte,
at zero risk. `RadioAgendaService` gets a new selection strategy — a new method or a small collaborator —
using the recency and boost query plus the persistence above, and stops calling the old bucket path.

Concretely, either extract selection behind one seam such as a `SongSelectionStrategy` with radio and
legacy implementations, or simply give `RadioAgendaService` its own supplier method and leave OTS on the
old one. The old bucket SQL that OTS still needs stays; only radio's queries are new.

The acceptance check for isolation is a test guard: OTS build output — song identity for a fixed catalog
— must be identical before and after.

The accepted trade-off is that the old bucket code lives on solely for OTS rather than being deleted
outright. That is the cost of not touching OTS, and it is revisited only if OTS is redesigned too.

# Cross-midnight is out of scope, decided

Selection changes *which songs* fill entries and does not touch the scheduling time arithmetic, which is
deliberately not being rewritten.

The worked example that prompted the question, a station started at 23:30: the build lays out a full
24-hour day from the loop's 00:00 anchor using time-of-day slots rather than "now";
`TimelineBuilder` schedules the loop's entries from today 00:00 to tomorrow 00:00; since it is 23:30
about 23.5 hours of entries are already past, so `StaggeredSongScheduler` skips them and only the
23:30-to-00:00 tail airs; then at 00:00 the daily rebuild replaces it with a fresh, correct full day.

This is accepted as-is. It wastefully builds a day of past entries for that one rare late start, and that
waste is explicitly fine — not worth the complexity of an absolute-datetime rewrite. Known pre-existing
quirks left untouched: the "more than 12 hours means shift back a day" heuristic in `TimelineBuilder` and
overnight-loop chopping at the rebuild boundary. The redesign must only not make any of this worse, and
it doesn't, because it changes song identity rather than entry timing.

If cross-midnight ever becomes a real problem rather than a rare late start, the fix would be to build
against absolute datetime like OTS's `buildOtsTimeline` with `explicitSceneStart` — a separate,
separately-approved scheduling change.

# Settled decisions

`OVERSAMPLE = 3` as the single constant and only tuning knob. The OTS seam is a separate radio method:
radio gets its own `selectSongsForScene(...)` using the new query while OTS keeps calling
`fetchSongsForSceneWithDuration` unchanged, with no new interface — two implementations that never swap
at runtime are not worth the layer. Play memory is persisted on emit with no DDL, rotation is by recency
with no dayparts, catalog age is a tiebreak, and cross-midnight stays out of scope.

# Deltas against shipped behaviour

| Topic | Today | Proposed |
|---|---|---|
| `RANDOM` sourcing | three buckets, newest/oldest/random, merged | one recency-ordered query plus a boost-weighted pick |
| Pool exhaustion | clear `usedIds` once and retry | defined reuse rung, no reset |
| Play memory | not persisted at emission | `last_time_played_by_brand` written on emit |
| Adjacency | within-scene only, cross-scene gap unaddressed | explicit cross-scene and crossfade guard |
| `new` label | affects pool order only | guaranteed placement |
| `QUERY` widening | fallback when matches are short | the normal path |

When this ships, fold the final behaviour into the song selection and agenda build concepts and delete
this one.
