---
type: Workflow
title: Song selection
description: How ScheduleSongSupplier fills a scene's pool — sourcing modes, boost-weighted SQL, the four-rung fill ladder and shared-fragment eligibility.
tags: [song-selection, schedulesongsupplier, boost, shared-fragments, sourcing, rotation]
audience: [developer]
---

# Song selection

`ScheduleSongSupplier` produces a `SongPool`: a list of `SoundFragment` plus a `sharedInfo` map of
`SharedMeta{sharerName, contributorEmail, priority}`.

| Mode | Behaviour |
|---|---|
| `RANDOM` | parallel fetch of newest (~30%), oldest (~40%) and random (the rest) plus shared fragments; merged first-wins, then shuffled |
| `QUERY` | filtered with `excludeIds`, quantity-limited, shuffled |
| `STATIC_LIST` | id-based, pinned order, no quantity limit |
| `GENERATED` | empty pool; content is produced at emission |

# The four-rung fill ladder

When a scene cannot be filled from matching unused songs, the ladder descends one rung at a time:

1. criteria-matched songs not yet used in this build;
2. any unused song — the filter is dropped via `widenToFill` → `getAnySongs`;
3. reuse of an already-used song, never adjacent, in least-recently-played order;
4. adjacent reuse, reachable only when the catalog holds a single song.

# Catalog boost in SQL

Boost is applied at build time in `SoundFragmentBrandRepository` and
`SharedSoundFragmentRepository`. The enum is `Boost`: `SUPER_BOOST(2)`, `BOOST(1)`, `NOTHING(0)`,
`QUARANTINE(-1)`.

Deterministic queries order by `COALESCE(boost, 0) DESC`. Random selection weights the draw:

```sql
RANDOM() * CASE boost WHEN 2 THEN 4.0 WHEN 1 THEN 2.0 WHEN -1 THEN 0.05 ELSE 1.0 END
```

`QUARANTINE` is excluded from deterministic queries entirely and reduced to 0.05× in random ones.

# Shared fragments

A shared fragment is eligible when it targets this brand, its share status is `ApprovalStatus.ACCEPTED`,
it is not archived, and its boost is above `QUARANTINE`. Selection is roughly 40% newest and 60%
weighted-random; on an id collision the brand's own copy wins.

Criteria matching on rung 1 works for `RANDOM` only — `QUERY` and `STATIC_LIST` cannot criteria-match
shared fragments, because the shared query carries no genre or label filter. Widening on rung 2 goes
through `getAnySongs` for every path.

The `new` label floats a contribution to the front of the pool on the next build
(`floatPriorityToFront`) and is then cleared. It affects **pool order only** — it does not guarantee
an intro. Contributor email is sent only when `notify_on_play` is true, and only after the queue send
in `SongEmitter.send` succeeds, not after an intro alone.

# Note on status codes

`ApprovalStatus` is `PENDING(506)`, `ACCEPTED(500)`, `REJECTED(501)`. Code compares through the enum,
never a literal; older prose mentioning `status = 505` for accepted shares is stale.
