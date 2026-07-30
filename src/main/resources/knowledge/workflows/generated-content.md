---
type: Workflow
title: Generated content
description: Scenes with no songs — ads, news and weather assembled at emission, persisted with an expiry so they can be reused, and pruned by metriq.
tags: [generated, ads, news, weather, jingle, expiry, prioritized-front, playhistory]
audience: [owner, developer]
---

# Generated content

A scene sourced as `GENERATED` carries no songs. At build the scene contributes a single leading
generated slot (`entry.generated = true`), `ScheduleSongSupplier` returns an empty pool, and
`contentPrompts`, `mixingType` and `mixingArtefacts` are copied onto the `LiveScene`. The audio is
produced by `GeneratedContentEmitter` when the slot fires.

# What gets generated

`contentPrompts[0].promptId` decides between an advertisement and news by prompt type and whether the
title contains "ad", delegating to `GeneratedUserAdService`, `GeneratedNewsService` or
`GeneratedWeatherService`. `generateAudio` yields a `SoundFragment` from LLM text through TTS.

A generated entry can also carry a DJ intro: with `INTRO_JINGLE_GENERATED_JINGLE_WITH_BACKGROUND` the
emitter produces **two** LLM and TTS outputs at emission — the spoken intro through
`IntroTtsGenerator`, and the generated body itself.

# Assembly

Assembly follows `scene.getMixingType()`: `JINGLE_GENERATED_JINGLE` is jingle-wrapped only,
`*_WITH_BACKGROUND` adds a `BACKGROUND_LOOP` bed, and `INTRO_JINGLE_..._WITH_BACKGROUND` also generates
the intro. Jingles and background come from `mixingArtefacts` ids, or by type on the master brand,
picked at random. A null mixing type falls to `sendGeneratedOnly` (`SONG_ONLY`).

It **fails loud** — an error metric and an aborted entry — when no jingles or no background are
available, rather than quietly emitting bare speech.

# Reuse and expiry

Generated content is always persisted as a `SoundFragment` with an `expires_at`. That means it can be
**reused** when the fragment already exists, skipping the LLM and TTS cost when the same item plays
again within its lifetime — a morning news bulletin replayed through the day, for instance.

The reuse key is the synthetic `artist` value from `buildArtistKey`, and it **includes the voice id** of
the reader for that content type (`getVoice(agent)`). Without the voice in the key, changing the DJ mid-day
would keep replaying the cached fragment in the previous voice until midnight; with it, the block is
re-rendered once for the new voice and reused from then on.

jesoos only sets `expires_at`. The pruning cron runs in **metriq**: `ArchivedCleanupService` →
`SoundFragmentRepository.findExpiredFragments` (`expires_at < NOW()`) deletes the storage files and then
the database records. That is out of jesoos's scope.

Once persisted, aivox mixes it exactly like any song — it neither knows nor cares that the fragment was
AI-generated.

# Priority

Generated entries are emitted at `PRIORITIZED_FRONT`, reaching the front of aivox's queue rather than
waiting behind buffered music. Advertisement plays record `PlayHistory`.

# Key files

`live/GeneratedContentEmitter`, `live/generated/GeneratedNewsService`, `GeneratedUserAdService`,
`GeneratedWeatherService`.
