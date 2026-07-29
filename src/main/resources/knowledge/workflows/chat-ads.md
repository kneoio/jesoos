---
type: Workflow
title: Ads in chat
description: How a listener creates an advertisement through conversation — the state machine, the two ad types, per-station feature flags, type classification and the fields collected.
tags: [ads, advertisement, classified, store-promo, adgraph, feature-flags, userad]
audience: [owner, developer]
---

# Creating an ad by talking

A listener can create an advertisement in the station chat, authenticated only. Unlike the song request
flow, this is a code-enforced state machine: `AdSessionManager` plus `AdGraph` drive it, and while a
session is open the intent router sends messages to `AdContinuationHandler` without an LLM call at all.

An anonymous caller is invited to sign in first. `inform_owner` is never used as an ad fallback.

# Per-station switches

Gating is per brand through `Brand.chatFeatureFlags`, an extensible flag map read by
`CreateAdToolHandler.resolveAdType`:

| Flag | Default | Covers |
|---|---|---|
| `CREATE_AD` | on | personal and classified ads — a bicycle, a car, a job |
| `STORE_PROMO` | off | store and business promotions — discounts, sales |

With neither flag on, the `create_ad` tool is withheld entirely in `ChatAgent.getToolsForUser` and
`mainPrompt.hbs` tells the DJ to decline conversationally through `{{adEnabled}}`. The tool handler also
rejects defensively if it is somehow still invoked.

# Starting and classifying

`create_ad` starts the interactive flow and returns a `firstQuestion` whose wording depends on which
types are enabled. It is said verbatim — no pre-collecting details and no explaining the process first.

With only one flag enabled the session is fixed to that type (`CLASSIFIED` or `STORE_PROMO`) from the
start and no classification is needed. With both enabled the type is ambiguous at session start, so
`AdGraph`'s `classifyAdType` node classifies it from the listener's first reply with an LLM call and then
locks it in for the rest of the session.

# Fields per type

From `AdGraph.requiredVarsFor`:

* `CLASSIFIED` — `description`, `details`, `contacts`, plus structured user data: category, price,
  location, brand, year, condition, mileage.
* `STORE_PROMO` — `description` and `validity`. No contacts and no category questions.

The result is a `UserAd` with an auto-generated title, its `adType`, and for `STORE_PROMO` the `validity`
stored in `userData`.

# Reaching the air

An ad becomes audio through a generated scene rather than through the chat: the text is voiced and
assembled with jingles at emission time, emitted at front-of-queue priority, and each play is recorded in
`PlayHistory`.

# Key files

`ad/AdSessionManager`, `ad/AdGraph`, `ad/AdContinuationHandler`, `tools/CreateAdToolHandler`.
