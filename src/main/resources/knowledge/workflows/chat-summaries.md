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

`ChatSummaryService` produces three LLM-generated types from the same conversations:

* **BRAND** (`chat_type = 'PUBLIC'`, brand-wide) — consumed on air by `DraftFactory` when a draft is
  built for a spoken intro.
* **USER** (per listener and chat type) — consumed by chat itself for conversation continuity via
  `ChatService` → `getLatestUserSummary`. It keeps the last five messages unsummarized.
* **ASK** (per user) — the same per-user mechanism for Ask Mixpla, under its own `SummaryType` so it
  can never be mistaken for on-air material.

`summarizeUserMessages` picks both the prompt and the `SummaryType` from the `ChatType`. `PUBLIC` gets
the listener prompt above with the profile block and type `USER`; `ASK` gets type `ASK` and
`generateAskSummary` — a support-conversation prompt with no DJ persona, no on-air phrasing and no
listener profiles, capturing what the person was trying to understand, how deep an answer suited them
and what was left unanswered. `AskChatService` reads it back once the live conversation passes ten
turns, replaying only the recent window plus the summary, so a long-running Ask thread stops growing
the request. Without the split, Ask conversations were summarized with the on-air prompt and the
result was never read by anyone.

`HELP` is never summarized — the caller is anonymous, the conversation lasts one visit and there is no
continuity to preserve. `getActiveUsers` excludes it, and excludes `user_id = 0`, or the help chat would
be picked up as a phantom "user 0" session.

# Busy chat is ranked, not a roll call

Many listeners may speak in the same unsummarized batch. There is **no per-listener queue** — BRAND
still produces **one** summary for the next intro. It must **rank and cap**: song requests /
dedications / on-air asks first, then labelled listeners (`artist`, `owner`) or clear identity, then
vivid personal detail — and drop the rest. Within a tier the LLM makes a **best-effort** pick of the
most interesting / on-air-worthy posts (specific, warm, surprising); that is not human editorial
judgment and not perfect fairness. At most **three** listeners, one short bullet each, plus an
optional room-mood line. Never fill the quota with weak material; if nothing is speakable, output
nothing useful rather than inventing a full room.

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

Each LLM call takes at most `BRAND_SUMMARY_THRESHOLD` (20) oldest unsummarized messages. A backlog
(failed ticks, a busy room) drains over later ticks instead of one oversized prompt that hits the
provider token-per-minute cap.

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
**un-aired** BRAND summaries. `DraftFactory` marks a summary aired only when the **rendered draft
includes** the `Chat summary` section — handing `chatSummary` to the script alone is not enough. A
script that omits or probability-gates the section leaves the row un-aired for a later intro.

Drafts should still prefer an emptiness guard (`if (chatSummary)`) over a coin flip, so a usable
summary is not delayed forever by chance. Never emit a bare `Chat summary:` label with nothing after
it, or the LLM will invent listeners.

# Retention

`deleteOldSummarizedMessages` runs nightly at 03:00 with `MESSAGE_RETENTION_DAYS = 7`. OTS chat is never
summarized.

That query only reaps rows carrying a `summarized_at`, so anything never summarized would be kept
forever. The same nightly job therefore also runs `deleteOldMessagesByType(HELP, HELP_RETENTION_DAYS)`
(2 days), deleting help rows by age alone.

# Key files

`maintenance/ChatSummaryService`, `repository/ChatSummaryRepository`, `model/chat/ChatSummary`.
