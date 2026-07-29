---
type: Workflow
title: Chat summaries
description: How listener conversations are condensed — one summary for on-air use and one for chat continuity — with the listener profiles, privacy rules, triggers, freshness limits and metrics.
tags: [summary, chat, on-air, privacy, continuity, scheduled, aired-at, metrics]
audience: [owner, developer]
---

# Not archival

Summarization is how the **air DJ learns what happened in chat**. Chat and air are one persona to the
listener, so a summary has to let the DJ sound like the same person who was just talking to them —
"thanks Mira for reaching me in chat, so we have someone from Michigan tonight…".

`ChatSummaryService` produces two LLM-generated types from the same conversations:

* **BRAND** (`chat_type = 'PUBLIC'`, brand-wide) — consumed on air by `DraftFactory` when a draft is
  built for a spoken intro.
* **USER** (per listener and chat type) — consumed by chat itself for conversation continuity via
  `ChatService` → `getLatestUserSummary`. It keeps the last five messages unsummarized.

# Listener knowledge is part of the input

`buildListenerProfiles` resolves the `Listener` behind every distinct speaker in the batch and passes a
profile block alongside the messages: the whole `userData` map — the free-form key/values the chat bot
collected during the conversation, such as city, country, interests and profession — the preferred or
localized name, and the labels resolved through `ListenerLabelCache` (`artist`, `owner`).

Without this the summary could only say *what* was said and never *who* said it, which is what made chat
and air feel like two different people.

Both prompts therefore **preserve names and identifying detail** and are told never to invent one. The
USER prompt previously forbade names outright and had to be inverted — anonymised summaries destroy the
single-persona illusion.

# Roles are labelled in the transcript

`formatMessagesForSummary` prefixes each line with `HOST (you)` for `MessageType.BOT` or `LISTENER` for
`MessageType.USER`; other types are skipped, and listener profiles resolve from `USER` messages only.

The bot posts under the DJ persona name, so an unlabelled transcript made the host read as a listener —
summaries then described the DJ as "a knowledgeable user" and would have had the DJ greet itself on air.

# Never put private data in a summary

This text is spoken on a public broadcast. The BRAND prompt forbids phone numbers, email addresses,
addresses, full names, prices and payment details even when a listener typed them in chat: an arranged ad
is mentioned as an arranged ad, never read back. Handle-style names containing digits, and anonymous
users, are skipped — they cannot be addressed naturally on air.

# Triggers

A `@Scheduled(every="5m")` job runs per active brand and summarizes when either
`count >= BRAND_SUMMARY_THRESHOLD` (20) or the oldest unsummarized message is at least
`BRAND_SUMMARY_MAX_TAIL_AGE_MINUTES` (10) old.

The age trigger matters: on a count-only rule a quiet station never crosses twenty and the DJ stays blind
indefinitely.

Brands are summarized **sequentially, never fanned out** (`transformToUniAndConcatenate`). Fanning out
means every brand due in the same tick fires a simultaneous LLM call, the provider rate-limits the burst,
and whole batches fail at the same instant. One brand's failure is recovered so it cannot abort the rest
of the sequence.

# Failure is loud and never persisted

A failed or blank generation must not be saved. A placeholder row would mark its messages summarized,
losing them permanently, and would hand the DJ an error string as on-air context. The batch stays
unsummarized and is retried on the next tick.

| code | severity | payload |
|---|---|---|
| `chat_summary_failed` | `ERROR` | summaryType, messageCount, periodStart/End, provider, model, errorType, error |
| `chat_summary_created` | `INFORMATION` | summaryType, messageCount, summaryLength, periodStart/End |

`provider` and `model` are in the payload deliberately: without them a rate limit is indistinguishable
from a bad batch in metriq.

# Freshness and single use

Both guard against the DJ voicing chat that is no longer real.

`getBrandChatContext` returns `BrandChatContext(summaryId, summary, fresh)`, where `fresh` is false past
`BRAND_SUMMARY_MAX_AGE_MINUTES` (60), measured from `period_end`. A dead conversation must never be voiced
as if it were happening now.

`aired_at` (nullable, on `mixpla__chat_summary`) makes it single-use: `getLatestBrandSummary` returns only
**un-aired** BRAND summaries, and `DraftFactory` calls `markBrandSummaryAired` when it hands one to the
script. Each chat moment is therefore offered to air at most once, with no repeated thank-yous.

The caveat is that marking happens at draft **render**, not at emission, so a Groovy script that receives
a summary and randomizes it away still consumes it. Do not add a probability gate around `chatSummary` in
a draft template: `aired_at` already provides the novelty, and a coin flip on top silently discards
summaries.

# Retention

`deleteOldSummarizedMessages` runs nightly at 03:00 with `MESSAGE_RETENTION_DAYS = 7`. OTS chat is never
summarized.

# Key files

`maintenance/ChatSummaryService`, `repository/ChatSummaryRepository`, `model/chat/ChatSummary`.
