---
type: Workflow
title: Playout and interrupts
description: How aivox queues mixed audio per codec, serves the HLS playlist and ICY mirror, and injects hard or gentle interrupts.
tags: [playlistmanager, radiostreamer, hls, icy, interrupts, priority, sliding-window]
audience: [developer]
---

# Queueing

`PlaylistManager` is per brand and holds two tiers per codec: a `prioritizedQueues` deque and a
`regularQueues` queue, plus a "recently obtained" list used for snapshots and feed exclusion.

Routing is by priority, and **lower numbers are more urgent** (matching jesoos `StreamPriority`).
Anything above `PRIORITIZED` goes to the regular tier; `PRIORITIZED_FRONT` is inserted with
`addFirst`. A double fragment is added by `addGroupToFront` in reverse, so the front order ends up
`[first, second, …]`.

Dequeue drains the prioritized tier before the regular one and publishes a `queue_dequeue` metric.

The self-managed fallback (`feedFragments` via `getBrandSongs`) exists but its scheduler trigger is
**disabled** (`&& false`), so a station depends entirely on jesoos content today. Where it does run,
normal jesoos content evicts self-managed filler, which sits at priority 11.

# Serving

`RadioStreamer` is per brand and per codec; `OpusStreamer` is the fMP4 subclass and `IcyStreamer` is
an optional slave that mirrors HLS playout order, interrupts included.

`feedSegments`, driven by `SegmentFeederTimer`, does three things in order: drain the
`superInterruptQueue`; drip `SEGMENTS_TO_DRIP_PER_FEED_CALL` segments from `pendingQueue` into
`liveSegments` while respecting the buffer cap, activating the `gentleInterruptQueue` at an atomic
group end; then refill from `PlaylistManager`. A persistently null refill is starvation and is logged
and metered.

`generatePlaylist` emits standard HLS plus two custom tags — `#EXT-X-CUSTOM-BRAND` with the DJ name
and status, and `#EXT-X-CUSTOM-SONG` with title, artist, genres and labels — which is how a player
shows what is on air. `getSegment` serves `.ts` as `brand_bitrate_sequence`, and `SliderTimer` trims
the window to `maxVisibleSegments`.

# Interrupts

`interceptNow` is the hard path: clear what is pending and play immediately. `interceptAfterSong` is
the gentle path and waits for the end of the current atomic group. Greetings and DJ injections use
these. An atomic group must never be split, and a group-to-front pair must never be reordered.

# Feedback

When a song ends aivox publishes a `song_played` metric, and any pending listener rating is sent back
as a `SONG_RATED` command through `CommandPublisher`.

# Terms

A **sliding window** is the bounded set of segments currently advertised in the playlist, capped at
`maxVisibleSegments`, with older segments sliding out. An **atomic group** is a multi-fragment unit such
as song-intro-song whose end boundary must not be split by an interrupt, marked by `isAtomicGroupEnd`.
The **prioritized** tier is DJ- and jesoos-driven and front-insertable; the **regular** tier is the
filler and self-managed baseline. `superInterruptQueue` is the hard path — clear what is pending and
play now — and `gentleInterruptQueue` the soft one, playing after the current song ends.

# Key files

`service/playlist/PlaylistManager`, `PlaylistState`, `streaming/RadioStreamer`, `OpusStreamer`,
`IcyStreamer`, `streaming/HlsSegment`, `SegmentFeederTimer`, `SliderTimer`, `rest/StreamingResource`.
