# jesoos — CLAUDE.md

## Agent Scope

You are the dedicated agent for **jesoos**. Primary focus must remain on jesoos.
You may modify **jesoos**; do not modify `aivox`, `metriq`, `datanest`, `nivaro`, or `2next`
(see Service Boundaries in the SHARED block).

## Documentation Map

- `service/chat/CHAT_WORKFLOW.md` — public chat / LLM agent flow (auth, tools, OTS, ads).
- `service/ask/ASK_WORKFLOW.md` — internal Mixpla Ask chat (platform knowledge; isolated from listener chat).
- `service/agenda/RADIO_WORKFLOW.md` — continuous brand radio: agenda build → live emission,
  scenes, timeline, mixing, talkativity, TTS, generated content, Catalog vs Live Boost, shared
  fragments, commands, metrics.
- `service/live/OTS_WORKFLOW.md` — one-time-stream (OTS) lifecycle: brand-scoped vs owner-scoped,
  routing identity, DJ-toggle handling. Coherent with aivox's `OTS_SCOPE.md`/`RADIO_SCOPE.md`.

## jesoos Notes

- Prompt definitions are currently hardcoded and stored in `src/main/resources/prompts`.


## Behavior Rules

- Keep answers brief
- Prefer yes/no answers when possible
- NO proactive behavior!!!!!!!!
- NO improvisation — implement EXACTLY what was asked, nothing more, nothing less
- Be concise, do not over-investigate unless explicitly asked
- For status checks: run one command, report result, stop
- Do not continue digging without permission
- Ask before running more than 2 commands for a task
- Show only relevant changes
- Do not explain obvious things
- Do not suggest next steps unless asked
- Never modify unrelated files
- Never refactor unless requested
- NEVER push code without explicit permission

---

## Documentation Convention (all Mixpla services)

- Each project's `CLAUDE.md` is an **index**, not a manual — keep detail out of it.
- Each complex subsystem gets an authoritative `<AREA>_WORKFLOW.md` **next to its code**; read it
  before editing that area and update it when behaviour changes. New docs get a line in the
  project's Documentation Map (in the project-specific header above).
- Cross-service domain terms are defined **once** in the shared `2next/mixpla` glossary, never
  redefined per service.

---

## Engineering Conventions (all Mixpla services)

- **Reactive first.** Use the Quarkus reactive stack (Mutiny `Uni`/`Multi`, Vert.x). Never block the
  event loop; offload blocking work to a worker pool.
- **Performance is a priority**, especially the audio path in `jesoos` and `aivox`. Avoid expensive
  re-initialization on hot paths.
- **Shared / reused FFmpeg.** FFmpeg startup is expensive — reuse a single shared/pooled FFmpeg
  (executor) instance instead of spawning/initializing per call. `jesoos` and `aivox` follow the same
  approach for audio processing.
- **Keep libraries current.** Upgrading a dependency to use its latest features is encouraged (shared
  `2next` bumps still follow the 2Next Change Policy above).
- **Database changes need approval.** Schema / DDL changes are fine in general, but only **after
  Aida's explicit permission**. Propose the change (show the `ALTER`s) and wait for approval — never
  alter the database structure on your own initiative.
- **Consistent design patterns.** Match the structure the surrounding code already uses; don't invent
  per-feature variations for the same kind of work.
- **Observability = metrics + logs.** Publish **important events as metrics** (RabbitMQ → metriq),
  which metriq renders in its own frontend (`metriq/frontend`). Metrics **complement** logs, they do
  not replace them — keep meaningful logs too. Use **JBoss logging** (`org.jboss.logging.Logger`),
  Quarkus-native, as the standard logging API.
- **Data access / RLS.** Row-Level Security is a **datanest** concern (CRUD backend for the
  Mixdeck/42next SPAs — every query is user-scoped). Backend services (`jesoos`, `aivox`) run as a
  trusted system user and **skip RLS for performance**.

---

## Inter-service Messaging (all Mixpla services)

Services talk over **RabbitMQ**, not REST. Three logical channels, DTOs shared from `2next`
(`com.semantyca.mixpla.dto.queue.*`):

- **Streaming / entities** — `jesoos → aivox`, one `SongQueueMessageDTO` per timeline entry
  (channel `streaming`, routing key = brand slug).
- **Metrics** — every service → `metriq`, `MetricEventDTO` (channel `metrics`).
- **Commands** — control messages between services, `CommandDTO` (command channels).

**Prefer async messaging over REST.** The platform targets **Kubernetes-native horizontal scaling** —
any service may run as **many pods** (aivox is the first/primary candidate). Synchronous REST between
services does not scale cleanly across pods, so **new inter-service calls should go through the queues,
not REST**. Some REST calls still exist (legacy) and should be migrated to messaging when touched.

---

## Project Purpose (whole Mixpla platform)

Mixpla is **one system**; the split into microservices is a deployment/scalability choice
(it was formerly the `KneoBroadcaster` monolith). The services:

- **aivox** — streaming service (Quarkus, reactive/Vert.x): mixes audio, consumes messages from
  `jesoos`, generates and streams HLS/ICY audio.
- **jesoos** — content delivery service (Quarkus, reactive/Vert.x): builds the agenda (scripts &
  scenes), sends sequential messages to `aivox` over RabbitMQ.
- **metriq** — metric collector service (Quarkus, reactive/Vert.x): consumes RabbitMQ metric
  messages from `aivox`/`jesoos` for dashboards and stats; also runs shared-data maintenance crons.
- **datanest** — CRUD backend service; works with the `Mixdeck` (user) and `42next` (admin) SPAs.
- **nivaro** — finance/payments service (Quarkus, reactive/Vert.x). Owns **all payment-related
  data**; kept in its own service/store deliberately, to isolate financial data — one of the reasons
  it is separated from the rest of Mixpla.

---

## 2Next Core System (the shared codebase)

Core packages:
- `com.semantyca.core.*`
- `com.semantyca.mixpla.*`
- `com.semantyca.officeframe.*`

The shared **`2next`** repo — consumed as the Maven artifact `com.semantyca:2next` (GitHub Packages).

### What 2Next shares

`2next` is the single dependency every service builds on. It owns the **cross-service contracts**:
- **Domain model** — `Brand`, `Script`, `Scene`, `ScenePrompt`, `PlaylistRequest`, `SoundFragment`,
  `BrandSoundFragment`, `SharedSoundFragment`, `AiAgent`, `Voice`, `CustomAction`, `DjPrompt`,
  `Listener`, `Event`, `UserAd`, …
- **Enums / constants** — `MixingType`, `MergingTypeMeta`, `Boost`, `SceneType`, `WayOfSourcing`,
  `SourceType`, `PlaylistItemType`, `ContentStatus`, `StreamStatus`, `StreamPriority`,
  `TTSEngineType`, `LlmType`, `SubmissionPolicy`, …
- **Queue DTOs** — `SongQueueMessageDTO`, `CommandDTO`, `MetricEventDTO`, and the `SongKey`/`IntroKey`
  livestream keys (the RabbitMQ message shapes between services).
- **Shared clients / utilities** — LLM/text clients, messaging base classes, template engines.

Because these are shared, a change here is a change to **every** service's contract at once.

### 2Next Change Policy

- Changing `2next` is **encouraged** when it keeps the codebase robust — **one codebase, one model**,
  no per-service divergence or duplication of shared concepts. Prefer fixing/extending the shared
  model over working around it locally.
- **Aida's (the user's) explicit approval is MANDATORY before any `2next` change.** Never modify
  `2next` on your own initiative — propose the change and wait for approval.
- Before pushing an approved `2next` change:
  - bump the version in `pom.xml`
  - bump the version in `com.semantyca.core.server.EnvConst`
  - state that **all dependent services** must then upgrade their dependency version.

---

## Service Boundaries (general)

- You may modify **only your own service** (named in the project-specific header above).
- All other services — including `2next` — have their own owner/agent. Do **not** modify them; for
  those, only **describe** the required changes.
- Never implement cross-service changes unless **explicitly requested**.
