---
type: Workflow
title: Chat summaries
description: How listener conversations are condensed — one summary for on-air use and one for chat continuity — with the privacy rules and freshness limits.
tags: [summary, chat, on-air, privacy, continuity, scheduled]
audience: [owner, developer]
---

# Two kinds of summary

`ChatSummaryService` produces two different things from the same conversations.

A **brand** summary is what lets the DJ mention on air what listeners have been talking about. It is
picked up by `DraftFactory` when a draft is built for a spoken intro.

A **user** summary gives continuity within one listener's own conversation, covering their last five
unsummarized messages.

The input includes listener profiles and their labels, and the transcript distinguishes `HOST (you)`
from `LISTENER`.

# Privacy

A brand summary is going to be spoken on a public stream, so it must not carry personal detail — no
phone numbers, no email addresses, nothing that identifies a listener beyond what they would happily
hear announced.

# When it runs

A scheduled job every 5 minutes summarizes a brand once it has at least 20 unsummarized messages, or
when the oldest is at least 10 minutes old. Brands are processed sequentially rather than fanned out.

Failure is loud: nothing is persisted on failure, and `chat_summary_failed` is published alongside
`chat_summary_created` for successes.

# Freshness and retention

A brand summary older than 60 minutes is not used on air, and `aired_at` makes it single-use — marked
when the draft is rendered rather than when the audio is emitted. Summaries are purged nightly after 7
days. Event stream conversations are never summarized.
