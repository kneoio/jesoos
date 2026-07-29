---
type: Concept
title: Engineering conventions
description: The rules every Mixpla service follows — reactive stack, FFmpeg reuse, messaging over REST, observability, and the shared 2next change policy.
tags: [conventions, reactive, quarkus, ffmpeg, rabbitmq, 2next, database]
audience: [developer]
---

# One system, several services

Mixpla is one system; the split into services is a deployment and scalability choice, inherited from the
former `KneoBroadcaster` monolith. Any service may run as many pods, aivox first among them, which is
why synchronous REST between services is discouraged.

# Stack

Reactive first: the Quarkus reactive stack with Mutiny `Uni`/`Multi` and Vert.x. The event loop is never
blocked — blocking work is offloaded to a worker pool. Performance matters most on the audio path in
jesoos and aivox, so expensive re-initialization on hot paths is avoided.

FFmpeg startup is expensive, so both services reuse a shared or pooled executor rather than spawning per
call.

Keeping libraries current is encouraged. Database schema changes are allowed in principle but only after
explicit approval — propose the DDL and wait.

# Messaging over REST

Services talk over RabbitMQ on three logical channels, with DTOs shared from 2next: `streaming`
(jesoos → aivox, one `SongQueueMessageDTO` per timeline entry, routing key = stream slug), `metrics`
(every service → metriq, `MetricEventDTO`) and commands (`CommandDTO`). New inter-service calls go
through the queues; the REST calls that remain are legacy and should be migrated when touched.

# Observability

Important events are published as metrics *and* logged — metrics complement logs rather than replacing
them. Logging uses `org.jboss.logging.Logger`, the Quarkus-native API.

# Data access

Row-level security is a datanest concern: it is the CRUD backend for the Mixdeck and 42next SPAs and
every query is user-scoped. jesoos and aivox run as a trusted system user and skip it for performance.

# The shared 2next artifact

2next is the single dependency every service builds on, consumed as the Maven artifact
`com.semantyca:2next`. It owns the cross-service contracts: the domain model (`Brand`, `Script`, `Scene`,
`SoundFragment`, `SharedSoundFragment`, `AiAgent`, `Voice`, `Listener`, `UserAd` and the rest), the enums
(`MixingType`, `Boost`, `SceneType`, `WayOfSourcing`, `ApprovalStatus`, `StreamStatus`, `StreamPriority`,
`TTSEngineType`, `SubmissionPolicy` and others), and the queue DTOs with their `SongKey` and `IntroKey`
livestream keys.

A change to 2next is a change to every service's contract at once, so it requires explicit approval,
a version bump in both `pom.xml` and `EnvConst`, and every dependent service then upgrading.

# Documentation

Each project's `CLAUDE.md` is an index, not a manual. Each complex subsystem gets an authoritative
`<AREA>_WORKFLOW.md` next to its code, read before editing that area and updated when behaviour changes.
Cross-service domain terms are defined once in the shared glossary and never redefined per service.
