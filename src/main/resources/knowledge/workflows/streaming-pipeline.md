---
type: Workflow
title: Streaming pipeline
description: The aivox side — consuming a queue message, dispatching it to a mixer by MixingType, mixing with FFmpeg, slicing into HLS segments and handing it to the playlist.
tags: [aivox, mixer, ffmpeg, fragmentslicer, hls, codecs, pipeline, dispatch, livestreampool]
audience: [developer]
---

# Streaming pipeline

jesoos decides *what* plays and *when*; aivox decides *how it sounds* and *how it is served*. The
contract between them is `SongQueueMessageDTO`, defined in 2next. aivox never contains scene or agenda
logic.

```
RabbitMQ "streaming" (routingKey = stream slug)
  → QueueConsumer → QueueService.addToQueue(SongQueueMessageDTO)
      • otsSlugName set  → addOtsToQueue  → the OTS stream, not the brand radio
      • otsSlugName absent → the continuous radio stream
      • mergingMethod == null → error metric, message dropped
      • switch(MixingType) → Mixer.handleXxx
            routeSingle → one MixedFragment
            routeDouble → two MixedFragments, inserted group-to-front
  → FragmentSlicer.slice(mixed, codecBitrates) → LiveSoundFragment
  → PlaylistManager.addLiveFragmentToQueue
```

On the OTS path only `INTRO_SONG`, `LISTENER_INTRO_SONG` and `SONG_CROSSFADE_SONG` are supported; the
radio path takes the full set.

# Message shape

Per timeline entry: `mergingMethod` (the `MixingType`), `songs` (a `SongKey` map of id and duration),
`filePaths` (an `IntroKey` map of TTS path and gain), `priority`, `sceneDeadlineTimestamp`, and an
optional `otsSlugName`. The DTO field is named `brandSlug`, but for an OTS it carries the OTS slug —
the name is a cross-service contract, not a statement about scope.

# Dispatch table

`QueueService.addToQueue` is a switch on `MixingType`. This table **is** the contract with jesoos's
`mergingMethod` and must stay in step with the switch.

| MixingType | Handler | Shape |
|---|---|---|
| `SONG_ONLY` | `handleSongOnlyWithSegments` | single |
| `INTRO_SONG`, `LISTENER_INTRO_SONG` | `handleIntroSong` | single, intro plus song |
| `JINGLE_INTRO_SONG` | `handleJingleIntroSong` | single, jingle plus intro plus song |
| `FILLER_JINGLE` | `handleFillerJingle` | single, jingle plus song |
| `SONG_CROSSFADE_SONG`, `_VAR_1` | `handleConcatenationAndFeed(CROSSFADE)` | single pair |
| `NOT_MIXED` | `handleConcatenationAndFeed(DIRECT_CONCAT)` | single |
| `SONG_INTRO_SONG` | `handleSongIntroSong` | **double**, group-to-front |
| `INTRO_SONG_INTRO_SONG` | `handleIntroSongIntroSong` | **double** |
| `JINGLE_GENERATED_JINGLE[_WITH_BACKGROUND]` | `handleJingleGeneratedJingle[WithBackground]` | single generated block |
| `INTRO_JINGLE_GENERATED_JINGLE_WITH_BACKGROUND` | `handleIntroJingleGeneratedJingleWithBackground` | single, intro plus generated block |
| unknown or null | — | error metric, dropped |

# Mixing

Songs arrive as ids and aivox owns the bytes: `SoundFragmentService.getById` materializes each into a
temp file. Intros pass through `IntroGainProcessor.apply(path, gain, engineType)`, which normalizes the
TTS file's loudness per engine, using the gain and engine from the message's `IntroInfoDTO`.

Concatenation and crossfade run through `AudioConcatenator` (FFmpeg), while intro-over-song ducking is
done in **raw PCM** (`mixSongPlusIntro`): the intro is laid near the song's tail under a smooth duck
curve. Everything runs at 44100 Hz stereo s16.

A generated block (`buildJingleBlock`) is a jingle intro, then the TTS content optionally mixed over a
looped background bed at about 0.3 volume, then a jingle outro, silence-stripped at the joins and
wrapped as a `TEMPORARY_MIX` fragment typed `NEWS`.

For the double shapes the first fragment is marked `withAtomicGroupEnd(false)` so the pair cannot be
split by an interrupt.

# Output units

A `MixedFragment` is a rendered temp WAV (`SourceType.TEMPORARY_MIX`) plus fragment metadata — either
one half of a crossfade pair or an atomic group. `FragmentSlicer` cuts it into per-codec, per-bitrate
HLS segments, producing a `LiveSoundFragment`: segments keyed codec → bitrate, plus `SongMetadata`,
`priority`, `mergingMethod` and bundle.

Codec bitrates come from `getCodecBitrates` — the same perceived quality needs different bitrates, so
OPUS is 1×, AAC 2× and MP3 2.5×. Every enabled codec is segmented.

# Station lifecycle

`LiveStreamPool` holds both kinds of station as `LiveStream` entries, and the message's `otsSlugName`
picks which one an entry feeds.

A **radio** station is started manually by an explicit command — `POST /aivox/command/start?brand=…` →
`CommandService.initializeStream` → `initializeStation` — is always brand-scoped with no fallback and no
default codec or bitrate, and fails outright when the brand is not found. It is infinite by nature and
runs until stopped. There is no owner-scoped radio and none is planned.

An **OTS** is never started that way: it cold-starts on the first HLS request for its slug and tears
down when its run ends. Both kinds bind their own routing key on the shared streaming queue and unbind
it on teardown.

Queueing also differs: radio's `PlaylistManager` carries the self-managed fallback feed on top of the
prioritized and regular tiers both share, while an OTS is only ever fed by jesoos.

# FFmpeg

FFmpeg startup is expensive, so both jesoos and aivox reuse a shared or pooled executor instead of
spawning per call. This is a hard performance rule on the audio path, not a preference.

# Rules for working here

The pipeline is settled — refine, don't redesign.

1. **The dispatch table and the shared DTOs are authoritative.** Adding or removing a `MixingType`
   branch, or changing the `SongQueueMessageDTO`, `SongKey` or `IntroKey` shapes, is a cross-service
   contract change to be coordinated with jesoos and 2next first.
2. **Never split an atomic group or reorder a group-to-front pair.** `isAtomicGroupEnd` and
   `addGroupToFront` exist to keep song-intro-song and crossfade pairs intact.
3. **Priority semantics are load-bearing:** a lower number is more urgent, and prioritized comes before
   regular. Don't invert or "simplify" it.
4. **Segment and timestamp handling is delicate** — the sliding window, sequence numbers and fMP4 `tfdt`
   rewriting. Changes here rebuffer live players, so test against a real client.
5. **Preserve metrics and `traceId` propagation.** Every stage meters (`mixing_begin`, the matching
   `*_completed` and `*_failed`, `queue_dequeue`, `song_played`) using the jesoos-supplied trace id.
6. **Fail loud, drop cleanly.** A null or unknown `mergingMethod` is metered and dropped rather than
   thrown; never let one bad message stall the channel.
7. **aivox mixes and serves and does not decide programming.**

# Control and feedback

aivox receives control over RabbitMQ and REST — start and stop a station and so on — and signals back
to jesoos, including a `stop` when a message targets a brand with no active station, and `SONG_RATED`
for listener ratings. Backpressure and the DJ toggle originate on the jesoos side and reach aivox only
as message `priority` and stream choice; aivox does not gate TTS itself.

# Key files

| Area | File |
|---|---|
| Consume and dispatch | `messaging/QueueConsumer`, `service/QueueService` |
| Mixing | `service/manipulation/mixing/handler/Mixer` and `MixerBase`, `mixing/AudioConcatenator`, `mixing/IntroGainProcessor` |
| Segmentation | `service/manipulation/FragmentSlicer` |
| Queueing | `service/playlist/PlaylistManager`, `PlaylistState` |
| Serving | `streaming/RadioStreamer`, `OpusStreamer`, `IcyStreamer`, `streaming/HlsSegment`, `SegmentFeederTimer`, `SliderTimer`, `rest/StreamingResource` |
| Station lifecycle | `streaming/LiveStreamPool` |
| OTS | `service/OtsService`, `streaming/OtsWarden`, `repository/OtsDefinitionRepository`, `streaming/WaitingAudioProvider` |
| Commands and feedback | `messaging/CommandConsumer`, `messaging/CommandPublisher`, `service/CommandService`, `rest/CommandResource` |
| Metrics | `messaging/MetricPublisher` |
