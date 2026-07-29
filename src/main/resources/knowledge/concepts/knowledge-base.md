---
type: Concept
title: The knowledge base itself
description: How this OKF bundle is laid out, loaded, searched and scoped by audience, and how to add a concept to it.
tags: [knowledge, okf, bundle, loader, search, audience, meta, how-to-add]
audience: [developer]
---

# What this is

Platform-wide Mixpla knowledge, stored as an **Open Knowledge Format (OKF v0.2)** bundle and served to
agents through one shared tool. It is not Ask-specific — `service/ask` is the first consumer, and any
other agent in jesoos can inject `KnowledgeBase`.

The bundle lives at `src/main/resources/knowledge/` in jesoos. The spec is OKF v0.2 from Google Cloud
(`GoogleCloudPlatform/knowledge-catalog`): markdown files with YAML frontmatter.

**This bundle is the source of truth for platform behaviour.** The per-project workflow documents that
used to sit next to the code were folded into it and removed, so a fact lives here or nowhere.

# Bundle layout

```
resources/knowledge/
  index.md              # root listing, carries okf_version
  platform.md           # what Mixpla is
  concepts/             # audiences, boost, data access, metrics, engineering conventions, this file
  services/             # jesoos, aivox, metriq, datanest, nivaro, spectra, 2next
  workflows/            # radio (scenes, agenda, selection, timeline, TTS, generated, emission),
                        # streaming (pipeline, playout), OTS, chat (public, internals, ads,
                        # summaries, requests), submissions, sharing, billing, Ask
  frontends/            # Mixdeck, 42next
```

One concept per file, and the file path is the concept's identity. `index.md` and `log.md` are reserved
filenames and are never concepts.

# Frontmatter

Adoption is minimal — `type` is the only key OKF requires:

```yaml
---
type: Service                 # REQUIRED
title: jesoos
description: One-line summary.
tags: [service, jesoos]
audience: [developer]         # producer-defined
---
```

Producer-defined keys are allowed and ignored rather than rejected. The provenance, trust and lifecycle
families (`sources`, `generated`, `verified`, `status`, `stale_after`) are not used yet; adding them later
is additive.

# Loading

`OkfBundleLoader` walks the bundle through its `index.md` files rather than scanning directories. The
spec already makes an index the directory listing, and named-resource reads behave identically from a
source tree or a packaged jar, which directory scanning does not. Links ending in `/` recurse and links
ending in `.md` are concepts.

A concept missing its frontmatter block is skipped with a warning, never fatally.

# Search

`KnowledgeBase` is `@ApplicationScoped`, loads once at startup, and scores concepts per query term:

| Field | Weight |
|---|---|
| `title` | 5 |
| `tags` | 4 |
| `description` | 3 |
| `type` | 2 |
| body | 1 |

It returns the top 6 hits by default. Each hit carries `title`, `type`, `path`, `description`, `tags` and
the best-matching body section capped at 1200 characters — a real section rather than a blind substring
window.

# Audience scoping

`search` takes the caller's `Set<Audience>` and drops concepts the caller may not see, so gating lives in
the corpus instead of in prompts. `Audience` is `user`, `artist`, `owner`, `developer`: `user` is always
present and the rest are additive, derived from the labels on the caller's Listener row through
`Audience.fromLabels`, while `Audience.primary` picks the most specific one for tone.

A concept **without** an `audience` key is visible to everyone. With one, it is returned only when it
intersects the caller's audiences. Internals — agenda build, selection, emission, the streaming pipeline,
playout, OTS internals, chat internals, RLS, metrics, the service concepts and this file — are
`[developer]`. Operational behaviour such as scenes, boost, DJ and TTS, generated content, sharing, team
visibility, subscriptions, promo codes, OTS and brand radio is `[owner, developer]`. Listener- and
artist-facing concepts are open or `[artist, …]`.

Labels are read-only from chat: `owner` and `developer` are assigned in datanest, and
`ListenerDataToolHandler` refuses any label but `artist` even if a model asks for it.

# Two content rules

**Proposals are labelled, never implied.** The song selection redesign concept is `[developer]` and says
in its own body that nothing is implemented, because it contradicts shipped selection behaviour.

**Where sources disagreed, the newer source or the code won, and the conflict is stated.** The OTS
RabbitMQ binding follows aivox's OTS scope document over its streaming workflow; share acceptance follows
datanest's sharing workflow over brand team visibility; `ApprovalStatus.ACCEPTED` is 500, verified against
the 2next enum rather than the 505 in older jesoos prose; and airplay of a received song follows jesoos's
own query, which needs only an accepted share, over datanest's claim that a brand association is required
for playback.

# The tool

`search_platform_knowledge` is `SearchPlatformKnowledgeTool` plus
`SearchPlatformKnowledgeToolHandler`, both in `service/knowledge`. The handler takes `KnowledgeBase` as a
parameter, matching how other jesoos tool handlers receive services, and audiences are passed alongside
it.

# Adding knowledge

1. Add `resources/knowledge/<area>/<concept>.md` with `type`, plus a title, description and tags.
2. Add `audience` when the concept is not for everyone — omitting it means open to all.
3. Link it from that directory's `index.md`. **Unlinked files are not loaded.**
4. Restart to pick it up, since the bundle loads at startup.

Because the bundle replaced the code-adjacent workflow documents, a behaviour change is only documented
once the matching concept is updated in the same change.

# Key files

| Area | File |
|---|---|
| Concept model and frontmatter parsing | `service/knowledge/OkfConcept` |
| Audience model and label mapping | `service/knowledge/Audience` |
| Bundle walk | `service/knowledge/OkfBundleLoader` |
| Load and search | `service/knowledge/KnowledgeBase` |
| Tool | `service/knowledge/SearchPlatformKnowledgeTool(Handler)` |
| Corpus | `resources/knowledge/**` |
