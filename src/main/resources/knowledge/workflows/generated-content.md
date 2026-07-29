---
type: Workflow
title: Generated content
description: Scenes with no songs — ads, news and weather assembled at emission time, persisted with an expiry so they can be reused.
tags: [generated, ads, news, weather, jingle, expiry, prioritized-front]
audience: [owner, developer]
---

# Generated content

A scene sourced as `GENERATED` carries no songs. It is built with a single leading slot flagged
`generated = true` and an empty pool, and the audio is produced by `GeneratedContentEmitter` when the
slot fires.

# What gets generated

The emitter decides between an advertisement and news from the prompt type and whether the title
contains "ad", then delegates to `GeneratedUserAdService`, `GeneratedNewsService` or
`GeneratedWeatherService`.

The block is assembled according to the scene's own `MixingType`: a jingle intro, the generated
speech, an optional background bed, and a jingle outro. Assembly **fails loud** if the required
jingles or background are missing rather than quietly emitting bare speech.

# Reuse and expiry

Generated audio is persisted as a `SoundFragment` with an `expires_at` timestamp, so a block can be
reused instead of paying the generation cost twice. metriq's `ArchivedCleanupService` prunes expired
rows.

# Priority

Generated entries are emitted at `PRIORITIZED_FRONT`, so they reach the front of aivox's queue rather
than waiting behind buffered music. Advertisement plays are recorded in `PlayHistory`.
