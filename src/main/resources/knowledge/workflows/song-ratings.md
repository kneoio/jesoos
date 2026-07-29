---
type: Workflow
title: Liking and disliking songs
description: How a listener's like or dislike is confirmed only after the song really played, logged per user and brand, and what it does not yet affect.
tags: [rating, like, dislike, listener, feedback, song-rated, command]
---

# Liking what you hear

A signed-in listener can like or dislike a song on the station they are listening to. It is a
per-listener signal on a per-station basis: the same track can be liked on one station and disliked on
another, and the most recent decision is the one that counts.

A rating only becomes real once the song has **actually played** for that listener. A like given for
something upcoming that then gets cancelled before playback never counts, so what is stored is only
confirmed decisions rather than intentions.

Ratings are personal feedback and are not announced on air.

# Implementation

aivox owns the confirmation, since it is the side that knows what really played, and it sends
`CommandType.SONG_RATED` over the command queue with `brandSlug`, `jesoosToken`, `soundFragmentId` and
`rating`.

`CommandService.handleSongRated` validates it: any missing field, a `rating` other than `+1` or `-1`, an
invalid token, an anonymous or unresolved user, or an unknown brand slug all publish a skip metric and
stop there. A valid one resolves the user from the token through `ChatAuthService`, resolves the brand,
appends the row and publishes a `song_rated` information metric.

Storage is an append-only log, `mixpla__sound_fragment_ratings_log`, holding `created_at`, `user_id`,
`sound_fragment_id`, `brand_id` and `rating`. The latest row per user and fragment is the current state
— nothing is ever updated in place, so the history of a listener changing their mind is preserved.

**Nothing in jesoos reads this table yet.** Ratings do not currently influence song selection, catalog
boost or the agenda; they are captured for future curation and for reporting. Do not describe them as
affecting rotation today.

# Key files

`repository/SoundFragmentRatingLogRepository`, `service/CommandService.handleSongRated`.
