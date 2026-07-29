# Mixpla Knowledge Base Workflow — Essential Guide

Platform-wide Mixpla knowledge, stored as an **Open Knowledge Format (OKF v0.2)** bundle and served
to agents through one shared tool. Not Ask-specific: `service/ask` is the first consumer, any other
agent in jesoos can inject `KnowledgeBase`.

> Bundle: `src/main/resources/knowledge/`
> Spec: OKF v0.2 (Google Cloud, `GoogleCloudPlatform/knowledge-catalog`) — markdown + YAML frontmatter.

---

## 1. Bundle layout

```
resources/knowledge/
  index.md              # root listing, carries okf_version
  platform.md           # what Mixpla is
  concepts/             # audiences, boost, data access, metrics, engineering conventions
  services/             # jesoos, aivox, metriq, datanest, nivaro, spectra, 2next
  workflows/            # radio (scenes, agenda, selection, timeline, TTS, emission),
                        # streaming (pipeline, playout), OTS, chat (public, internals, ads,
                        # summaries, requests), submissions, sharing, billing, Ask
  frontends/            # Mixdeck, 42next
```

41 concepts as of the platform-doc consolidation. The bundle is the **developer source of truth** for
platform behaviour; the code-adjacent `<AREA>_WORKFLOW.md` files remain in place per the documentation
convention and must not drift from it.

One concept per file; the file path is the concept's identity. `index.md` and `log.md` are reserved
filenames, never concepts.

## 2. Frontmatter

Minimal adoption — `type` is the only key OKF requires:

```yaml
---
type: Service                 # REQUIRED
title: jesoos
description: One-line summary.
tags: [service, jesoos]
audience: [developer]         # producer-defined, see §5
---
```

Producer-defined keys are allowed and ignored rather than rejected. Provenance/trust/lifecycle
families (`sources`, `generated`, `verified`, `status`, `stale_after`) are **not** used yet; adding
them later is additive.

## 3. Loading

`OkfBundleLoader` walks the bundle through its `index.md` files rather than scanning directories —
the spec already makes an index the directory listing, and named-resource reads behave the same from
a source tree or a packaged jar. Links ending in `/` recurse; links ending in `.md` are concepts.

A concept missing its frontmatter block is skipped with a warning, never fatal.

## 4. Search

`KnowledgeBase` (`@ApplicationScoped`) loads once at startup and scores concepts per query term:

| Field | Weight |
|---|---|
| `title` | 5 |
| `tags` | 4 |
| `description` | 3 |
| `type` | 2 |
| body | 1 |

Top 6 hits by default. Each hit returns `title`, `type`, `path`, `description`, `tags` and the
best-matching body section (capped at 1200 chars), not a blind substring window.

## 5. Audience scoping

`search` takes the caller's `Set<Audience>` and drops concepts the caller may not see, so gating
lives in the corpus instead of in prompts. `Audience` is `user`, `artist`, `owner`, `developer`;
`user` is always present and the rest are additive, derived from the labels on the caller's Listener
row (`Audience.fromLabels`). `Audience.primary` picks the most specific one for tone.

A concept **without** an `audience` key is visible to everyone. With one, it is returned only when it
intersects the caller's audiences. Current visibility: 6 concepts to `user`, 8 to `artist`, 19 to
`owner`, all 41 to `developer`. Internals (agenda build, selection, emission, streaming pipeline,
playout, OTS internals, chat internals, RLS, metrics, service concepts) are `[developer]`; operational
behaviour (scenes, boost, DJ/TTS, generated content, sharing, team visibility, subscriptions, promo
codes, OTS, brand radio) is `[owner, developer]`; listener- and artist-facing concepts are open or
`[artist, …]`.

Two content rules matter for correctness:

- **Proposals are labelled, never implied.** `song-selection-redesign.md` is `[developer]` and says in
  its own body that nothing is implemented, because it contradicts shipped selection behaviour.
- **Where source docs disagree, the newer/majority doc wins and the conflict is stated.** OTS RabbitMQ
  binding follows aivox `OTS_SCOPE.md` over `STREAMING_WORKFLOW.md`; share acceptance follows datanest
  `SHARING_WORKFLOW.md` over `BRAND_TEAM_VISIBILITY.md`; `ApprovalStatus.ACCEPTED` is 500, verified
  against the 2next enum, not the 505 in older jesoos prose.

Labels are read-only from chat — `owner` and `developer` are assigned in datanest, and
`ListenerDataToolHandler` refuses any label but `artist` even if a model asks for it.

## 6. Tool

`search_platform_knowledge` — `SearchPlatformKnowledgeTool` + `SearchPlatformKnowledgeToolHandler`,
both in this package. The handler takes `KnowledgeBase` as a parameter, matching how other jesoos
tool handlers receive services. Audiences are passed in alongside it.

## 7. Adding knowledge

1. Add `resources/knowledge/<area>/<concept>.md` with `type` (plus title/description/tags).
2. Add `audience` if the concept is not for everyone — omitting it means open to all.
3. Link it from that directory's `index.md` — **unlinked files are not loaded.**
4. Restart to pick it up (bundle loads at startup).

## Key files

| Area | File |
|---|---|
| Concept model / frontmatter parse | `OkfConcept` |
| Audience model / label mapping | `Audience` |
| Bundle walk | `OkfBundleLoader` |
| Load + search | `KnowledgeBase` |
| Tool | `SearchPlatformKnowledgeTool(Handler)` |
| Corpus | `resources/knowledge/**` |
