---
type: Workflow
title: Streaming pipeline
description: The aivox side — consuming a queue message, mixing it with FFmpeg, slicing it into HLS segments and handing it to the playlist.
tags: [aivox, mixer, ffmpeg, fragmentslicer, hls, codecs, pipeline]
audience: [developer]
---

# Streaming pipeline

jesoos decides *what* plays and *when*; aivox decides *how it sounds* and *how it is served*. The
contract between them is `SongQueueMessageDTO`, defined in 2next.

```
RabbitMQ "streaming" (routingKey = stream slug)
  → QueueConsumer → QueueService.addToQueue(SongQueueMessageDTO)
      • otsSlugName set  → addOtsToQueue  → the OTS stream, not the brand radio
      • otsSlugName absent → the continuous radio stream
      • mergingMethod == null → error metric, message dropped
      • switch(MixingType) → Mixer.handleXxx
            routeSingle → one MixedFragment
            routeDouble → two MixedFragments, inserted group-to-front
  → FragmentSlicer.slice → LiveSoundFragment
  → PlaylistManager.addLiveFragmentToQueue
```

# Message shape

Per timeline entry: `mergingMethod` (the `MixingType`), `songs` (a `SongKey` map of id and duration),
`filePaths` (an `IntroKey` map of TTS path and gain), `priority`, `sceneDeadlineTimestamp`, and an
optional `otsSlugName`. The DTO field is named `brandSlug`, but for an OTS it carries the OTS slug —
the name is a cross-service contract, not a statement about scope.

# Mixing

Songs are fetched by id through `SoundFragmentService.getById` into a temp file. Intros pass through
`IntroGainProcessor.apply(path, gain, engineType)` before mixing. Concatenation and crossfade run
through `AudioConcatenator` (FFmpeg); intro ducking is done in **raw PCM** (`mixSongPlusIntro`) over
the tail of the song. Everything runs at 44100 Hz stereo s16.

A generated block (`buildJingleBlock`) is jingle-intro plus TTS plus optional background at about 0.3
volume plus jingle-outro, silence-stripped and typed `NEWS`.

For the double shapes, the first fragment is marked `withAtomicGroupEnd(false)` so the pair cannot be
split by an interrupt.

# Output units

A `MixedFragment` is a temp WAV (`SourceType.TEMPORARY_MIX`) — either one half of a crossfade pair or
an atomic group. `FragmentSlicer` cuts it into per-codec, per-bitrate HLS segments, producing a
`LiveSoundFragment`: segments keyed codec → bitrate, plus `SongMetadata`, `priority`, `mergingMethod`
and bundle.

Codec bitrates come from `getCodecBitrates`: OPUS at one rate, AAC at two, MP3 at 2.5×. Every enabled
codec is segmented.

# FFmpeg

FFmpeg startup is expensive, so both jesoos and aivox reuse a shared or pooled executor instead of
spawning per call. This is a hard performance rule on the audio path, not a preference.
