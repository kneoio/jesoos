---
type: Library
title: 2next
description: Shared library holding the cross-service contracts every Mixpla service builds on.
tags: [library, 2next, contracts, domain-model, dtos]
audience: [developer]
---

# 2next

The shared codebase every service depends on, consumed as the Maven artifact `com.semantyca:2next`.
Core packages are `com.semantyca.core.*`, `com.semantyca.mixpla.*` and
`com.semantyca.officeframe.*`.

# What it shares

* Domain model: Brand, Script, Scene, ScenePrompt, PlaylistRequest, SoundFragment,
  BrandSoundFragment, SharedSoundFragment, AiAgent, Voice, CustomAction, DjPrompt, Listener, Event,
  UserAd.
* Enums and constants: MixingType, MergingTypeMeta, Boost, SceneType, WayOfSourcing, SourceType,
  PlaylistItemType, ContentStatus, StreamStatus, StreamPriority, TTSEngineType, LlmType,
  SubmissionPolicy.
* Queue DTOs: SongQueueMessageDTO, CommandDTO, MetricEventDTO, plus the SongKey and IntroKey
  livestream keys.
* Shared clients and utilities: LLM/text clients, messaging base classes, template engines.

Because these are shared, a change in 2next changes every service's contract at once.
