# Mixpla platform knowledge

## What is Mixpla
Mixpla is one system for AI-powered radio and event streams. It was formerly the KneoBroadcaster monolith.
The split into microservices is a deployment and scalability choice.

## Services
- **jesoos** — content delivery: builds agendas (scripts & scenes), public brand/OTS chat, and this Ask chat; sends sequential messages to aivox over RabbitMQ.
- **aivox** — streaming: mixes audio, consumes messages from jesoos, generates and streams HLS/ICY audio. Primary candidate for many pods.
- **metriq** — metrics collector: consumes RabbitMQ metric messages for dashboards; also shared-data maintenance crons. Frontend at metriq/frontend.
- **datanest** — CRUD backend for Mixdeck (user) and 42next (admin) SPAs. Row-Level Security (RLS) is a datanest concern; every query is user-scoped.
- **nivaro** — finance/payments. Owns all payment-related data, isolated deliberately from the rest of Mixpla.

## Shared core (2next)
Cross-service contracts live in the shared `2next` library: domain model (Brand, Script, Scene, SoundFragment, AiAgent, Voice, Listener, Event, UserAd, …), enums, and queue DTOs (SongQueueMessageDTO, CommandDTO, MetricEventDTO).

## Messaging
Services talk over RabbitMQ, not REST (prefer async for K8s horizontal scaling).
- Streaming / entities: jesoos → aivox, SongQueueMessageDTO per timeline entry (channel streaming, routing key = brand slug).
- Metrics: every service → metriq, MetricEventDTO.
- Commands: CommandDTO between services.

## Brand radio (continuous)
jesoos builds an agenda from scripts/scenes, emits a live timeline to aivox. Involves mixing types, talkativity, TTS, generated content, Catalog vs Live Boost, shared fragments. See RADIO_WORKFLOW in jesoos.

## OTS (one-time stream)
Temporary event streams (QR/URL access). Brand-scoped or owner-scoped catalogs. Distinct routing identity from continuous brand radio. Event chat is anonymous guest mode on the public chat WebSocket (slug is the access token). See OTS_WORKFLOW.

## Public brand chat
Listener-facing WebSocket DJ chat per brand (and OTS branch). Auth via email OTP; tools for catalog search, play-with-intro, upload, ads, events, listener memory. Not this Ask chat.

## Ask Mixpla (this chat)
Internal platform-knowledge assistant. No brand context. Separate WebSocket and agent. Email OTP auth without listener/station registration. ChatType ASK, scope key mixpla.

## Auth model (Ask)
Token on WebSocket connect (same session-token store as public chat). Sign-in upgrades the session; no brand listener upsert. Logoff clears Ask history only.

## Frontends
- **Mixdeck** — owner/user SPA (talks to datanest).
- **42next** — admin SPA (talks to datanest).
