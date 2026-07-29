---
type: Workflow
title: Song selection
description: How ScheduleSongSupplier fills a scene's pool — sourcing modes, boost-weighted SQL, the four-rung fill ladder, shared fragments and contributor notification.
tags: [song-selection, schedulesongsupplier, boost, shared-fragments, sourcing, rotation, contributor]
audience: [developer]
---

# Song selection

`ScheduleSongSupplier` produces a `SongPool`: a `List<SoundFragment>` plus a `sharedInfo` map of id →
`SharedMeta{sharerName, contributorEmail, priority}`.

| Mode | Behaviour |
|---|---|
| `RANDOM` (default) | parallel fetch of newest (~30%), oldest (~40%) and random (the rest) plus shared fragments, merged first-wins and shuffled |
| `QUERY` | filtered honouring `excludeIds`, quantity-limited, then shuffled |
| `STATIC_LIST` | id-based explicit curation: every pinned fragment is returned in pinned order, with no quantity limit |
| `GENERATED` | an empty pool — content is produced later by the emitter |

# The four-rung fill ladder

Each rung is used only when the one above is empty:

1. criteria-matched and unused;
2. **any** unused song — the filter is dropped rather than a song replayed (`widenToFill` →
   `ScheduleSongSupplier.getAnySongs`);
3. reuse, but **never adjacent**, in pool order so the least-recently-played returns first;
4. adjacent — unreachable unless the pool holds a single song.

Dropping a scene's filter is preferred to repeating one of its songs. That ordering is the entire
point of the ladder; do not reorder the rungs.

# Catalog boost in SQL

Every brand-to-fragment link carries a `boost` column — `mixpla__brand_sound_fragments.boost`, and
`ssf.boost` for shared songs — reusing the `Boost` enum: `SUPER_BOOST(2)`, `BOOST(1)`, `NOTHING(0)`,
`QUARANTINE(-1)`. It shapes which songs enter the agenda, entirely in SQL, in
`SoundFragmentBrandRepository` and `SharedSoundFragmentRepository`.

Deterministic newest, oldest and filter queries order by `COALESCE(boost,0) DESC, …`, so boosted songs
float to the top of the pool. The random query weights the draw:

```sql
RANDOM() * CASE boost WHEN 2 THEN 4.0 WHEN 1 THEN 2.0 WHEN -1 THEN 0.05 ELSE 1.0 END DESC
```

`QUARANTINE` is filtered out of deterministic queries entirely (`COALESCE(boost,0) > -1`) and weighted
down to 0.05× in random ones — suppressed without being deleted.

This is catalog boost, not the live boost that forces intros at emission. Same enum, different axis.

# Shared sound fragments

Other users and brands can share songs into a brand's pool through the `shared_sound_fragments` join
(`SharedSoundFragmentService`, `SharedSoundFragmentRepository`).

Eligibility is `target_brand_id = brand`, share status `ApprovalStatus.ACCEPTED`, `archived = 0`, and
catalog boost above `QUARANTINE`. Selection is 40% newest and 60% weighted-random using the same
`ssf.boost` weighting, merged first-wins and shuffled, then folded into the brand pool with
`putIfAbsent` — **the brand's own copy wins** on an id collision. Shared songs are ordinary
non-`STREAM` sources, so their ids join the cross-scene `usedIds` exclusion set like any other.

Criteria matching on rung 1 is `RANDOM`-only (`getSongsRandomly`): `QUERY` and `STATIC_LIST` cannot
criteria-match a shared song, because `SharedSoundFragmentRepository` narrows by **type only** and has
no genre or label conditions. Known gap — giving `buildQuery` the same conditions as
`SoundFragmentQueryBuilder` would close it. Widening on rung 2 works for every path through
`getAnySongs`, so a `QUERY` scene whose filter matches too few songs *will* be filled with received
songs, and their `sharedInfo` entry survives `widenToFill` so credit is preserved.

# Sharer identity

Each shared song carries a `sharerName` (`source_user_name`) that flows `SharedSongEntry` →
`SongPool.sharedInfo` → `SongEntry.sharerName`. Shared songs are the **only** sourcing path that
populates `sharedInfo`, and it is fed to the DJ draft so an intro can credit or dedicate to the sharer.

# Priority contributions and the `new` label

A shared fragment can carry a `new` label in `__labels`, joined through
`mixpla__sound_fragment_labels`. datanest attaches it and then publishes `REBUILD_AGENDA{brandId}` —
the same command the nightly rebuild and catalog-boost changes use. On the next build,
`ScheduleSongSupplier.floatPriorityToFront` pulls the fragment to the head of the pool ahead of the
normal shuffle and clears the label immediately, so a later rebuild does not re-float it.

Whether that entry gets a spoken intro is left entirely to the normal cadence and talkativity strategy
in `TimelineBuilder`: priority affects **pool order only**, never `hasIntro`.

The contributor address (`ssf.source_user_email`, threaded via `SongEntry.contributorEmail`) is only
populated when `ssf.notify_on_play = true`, gated in `SharedSoundFragmentRepository.fromRow`, so a
contributor who did not opt in never gets an email regardless of what happens downstream.

When populated, the email is sent only once the song is guaranteed to air imminently: `SongEmitter.send`
must generate the intro **and** successfully hand the entry to aivox's queue
(`queueSupplier.sendSongsToQueue`) before calling `IntroTtsGenerator.notifyContributorPlaying`. Intro
generation alone is not enough, because the queue send can still fail or be dropped afterwards — which
would leave the contributor waiting on an email for a song that never aired.

The mail (`MailService.sendContributionPlayingSoonAsync`) links to the station at
`{jesoos.streamer}/{stream.slugName}` and deliberately omits the intro text, so the payoff is clicking
through and listening live rather than reading it in the inbox. This is a one-shot signal, distinct
from both catalog boost and live boost: it does not bias selection probability or force intros, it just
guarantees one specific contribution plays next.

# Note on status codes

`ApprovalStatus` is `PENDING(506)`, `ACCEPTED(500)`, `REJECTED(501)`, and code compares through the
enum rather than a literal. Older prose citing `status = 505` for accepted shares is stale.

# Key files

| Area | File |
|---|---|
| Song sourcing | `agenda/ScheduleSongSupplier` |
| Per-song boost (SQL) | `repository/soundfragment/SoundFragmentBrandRepository`, `SharedSoundFragmentRepository` |
| Shared fragments | `service/soundfragment/SharedSoundFragmentService`, `repository/soundfragment/SharedSoundFragmentRepository`, `model/stream/SharedSongEntry` |
