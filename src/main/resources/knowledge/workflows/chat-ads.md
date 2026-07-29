---
type: Workflow
title: Ads in chat
description: How a listener creates an advertisement through conversation, the two ad types, and the feature flags that enable them per station.
tags: [ads, advertisement, classified, store-promo, adgraph, feature-flags]
audience: [owner, developer]
---

# Creating an ad by talking

A listener can create an advertisement in the station chat. Unlike the song request flow, this is a
code-enforced state machine: `AdSessionManager` plus `AdGraph` drive it, and while a session is open the
intent router sends messages to `AdContinuationHandler` without going through the LLM at all.

The finished ad is saved as a `UserAd`.

# Two types

`CLASSIFIED` is a listener's own small ad and collects contact details. `STORE_PROMO` is a shop
promotion and does not collect contacts. The fields differ accordingly.

# Per-station switches

Both are controlled by `Brand.chatFeatureFlags`: `CREATE_AD` is on by default, `STORE_PROMO` is off by
default. A station owner who does not want listeners producing ads turns the flag off.

# Reaching the air

An ad becomes audio through a generated scene rather than through the chat: the text is voiced and
assembled with jingles at emission time and emitted at front-of-queue priority, and each play is
recorded in `PlayHistory`.
