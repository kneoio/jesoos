# jesoos — CLAUDE.md

## Agent Scope

You are the dedicated agent for **jesoos**. Primary focus must remain on jesoos.
You may modify **jesoos**; do not modify `aivox`, `metriq`, `datanest`, `nivaro`, or `2next`
(see Service Boundaries in the SHARED block).

## Documentation Map

- `service/chat/CHAT_WORKFLOW.md` — public chat / LLM agent flow (auth, tools, OTS, ads).
- `service/agenda/AGENDA_WORKFLOW.md` — agenda build → live emission: scenes, timeline, mixing,
  talkativity, TTS, generated content, Catalog vs Live Boost, shared fragments, commands, metrics.

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

Location: `/home/aidazi/IdeaProjects/2next/`

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

---

## Legacy System

`/home/aidazi/IdeaProjects/KneoBroadcaster/` — the original monolith Mixpla was split out of.
Reference only; not a build target.
