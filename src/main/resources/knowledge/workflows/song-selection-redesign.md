---
type: Workflow
title: Song selection redesign (proposal, not implemented)
description: A reviewed but unimplemented plan to replace song selection with play-recency rotation and an adjacency guard. Nothing here describes current behaviour.
tags: [proposal, song-selection, rotation, adjacency, not-implemented, design]
audience: [developer]
---

# Status: proposal only

**Nothing in this concept is implemented.** Current behaviour is described in the song selection
concept, and the two disagree deliberately. Never answer a question about how selection works today
from this document.

The proposal replaces the song-selection half only — `ScheduleSongSupplier`, the bucket SQL and the
reuse ladder. Timeline building, mixing, emission and metrics stay as they are, and one-time streams are
untouched.

# What it would change

The hard invariant is that the same song never plays twice in a row — not within a scene, not across a
scene boundary, not inside a two-song crossfade entry, and not as a result of priority placement. That
is enforced by a final adjacency guard pass over the whole flattened cross-scene timeline, swapping on
violation and logging `adjacency_unavoidable` when the catalog holds one song.

Rotation becomes least-recently-played: a single candidate query per scene ordered by
`last_time_played_by_brand ASC NULLS FIRST, reg_date DESC`, oversampled three times, then boost-weighted
random selection from that stale window. Catalog age is only a tiebreak among never-played songs; after
a first play it is pure play recency.

Play state persists across builds and across midnight, written in `SongEmitter.send` after a successful
queue send, for both DJ-on and DJ-off paths, skipping `SourceType.STREAM` and OTS. The columns already
exist, so no schema change is needed.

The reuse ladder becomes a defined last rung rather than an emergency reset — the silent
`usedIds().clear()` retry disappears.

For `QUERY` scenes the filter becomes a *preference* rather than a constraint: matched songs first, then
widening to any fresh song as the normal path. `STATIC_LIST` keeps its literal pinned order with no
rotation and no boost. A `new`-labelled contribution would be **guaranteed** force-placed on the next
build and then cleared, which is stronger than today's pool-order float.

Cross-midnight time arithmetic is explicitly out of scope.

# Deltas against shipped behaviour

| Topic | Today | Proposed |
|---|---|---|
| `RANDOM` sourcing | three buckets, newest/oldest/random, merged | one recency-ordered query plus boost-weighted pick |
| Pool exhaustion | clear `usedIds` once and retry | defined reuse rung, no reset |
| Play memory | not persisted at emission | `last_time_played_by_brand` written on emit |
| Adjacency | within-scene only, cross-scene gap unaddressed | explicit cross-scene and crossfade guard |
| `new` label | affects pool order only | guaranteed placement |
| `QUERY` widening | fallback when matches are short | the normal path |
