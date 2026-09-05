---
type: Concept
title: Engineering conventions
description: The rules every Mixpla service follows — reactive stack, FFmpeg reuse, messaging over REST, observability, and the shared 2next change policy.
tags: [conventions, reactive, quarkus, ffmpeg, rabbitmq, 2next, database]
audience: [developer]
---

# One system, several services

Mixpla is one system; the split into services is a deployment and scalability choice, inherited from the
former `KneoBroadcaster` monolith. Production is a single-node Docker Compose stack; inter-service
calls use RabbitMQ (not REST) so aivox and others can scale out later without redesign.

# Stack

Reactive first: the Quarkus reactive stack with Mutiny `Uni`/`Multi` and Vert.x. The event loop is never
blocked — blocking work is offloaded to a worker pool. Performance matters most on the audio path in
jesoos and aivox, so expensive re-initialization on hot paths is avoided.

FFmpeg startup is expensive, so both services reuse a shared or pooled executor rather than spawning per
call.

Keeping libraries current is encouraged. Database schema changes are allowed in principle but only after
explicit approval — propose the DDL and wait. No service owns DDL: migrations live in the `mxpldb` repo
and are run separately from the service that needs them.

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
`TTSEngineType`, `STTEngineType`, `SubmissionPolicy` and others), and the queue DTOs with their `SongKey` and `IntroKey`
livestream keys.

A change to 2next is a change to every service's contract at once, so it requires explicit approval,
a version bump in both `pom.xml` and `EnvConst`, and every dependent service then upgrading.

# Changing the radio pipeline

The build and emission workflow is settled, so work there is refinement rather than redesign. Tuning a
heuristic, clarifying a name, fixing an off-by-one or adding a metric is fine. Changing the
scene-selection model, the entry status state machine, the ticker split, the loop versus one-time
contract, or the RabbitMQ message shape means stopping and asking first. The same applies to altering
what the duration and overhead constants mean, the 15-second and 60-second cadences, the lead-time model,
or the de-duplication policy — if a change feels radical, it is.

Service boundaries hold inside the pipeline too: jesoos builds and schedules, aivox mixes and streams.
Mixing logic is never encoded in jesoos and jesoos never reaches into aivox. Cross-service message shapes
(`SongQueueMessageDTO`, `CommandDTO`) are contracts and need coordination before changing.

Timing stays deterministic and code-owned — no logic where the LLM decides when or in what order things
happen, and randomness stays confined to the existing shuffle points. Failures are loud: an empty agenda
or missing scenes throw or publish an error metric rather than being papered over with defaults.

If you touch a stage, keep its metric codes and severities and keep publishing on the same success and
failure branches; renaming a code or dropping an event silently breaks metriq dashboards. Keep
`traceId` propagation intact rather than minting a fresh id mid-chain.

Match the surrounding style: reactive `Uni` chains, sequential exclusion-set threading, and
`LocalTime`/`ZoneId` brand-timezone arithmetic, with no blocking calls or new scheduling primitives.

Finally, keep the two boosts distinct — they share only the `Boost` enum. Catalog boost is a per-song
database value applied in SQL; live boost is a runtime per-brand counter in `DjStateService`. Name them
explicitly (`liveBoost…` versus the SQL `boost` column), never introduce a bare `boost` identifier, and
never let one's logic leak into the other.

# Documentation

Each project's `CLAUDE.md` is an index, not a manual — it points at this bundle and carries only the
agent's own operating rules. Platform behaviour is documented **once**, as a concept in this bundle,
which every service shares; the per-service `<AREA>_WORKFLOW.md` files that used to sit next to the code
were folded in and deleted, and no service keeps a second copy. Read the relevant concept before editing
an area and update it when behaviour changes. Cross-service domain terms are defined once and never
redefined per service.
