---
type: Concept
title: Metrics
description: The metric event contract every service publishes to metriq, the event and process types, and the codes that trace a stream.
tags: [metrics, metriq, observability, traceid, silence-risk, events]
audience: [developer]
---

# Metrics

Metrics complement logs, they do not replace them: important events are published as metrics over
RabbitMQ to metriq, which renders them in its own frontend, while meaningful logging continues through
JBoss logging.

`MetricPublisher` sends a `MetricEventDTO` on the `metrics` channel carrying the app id, brand name,
event type, process type, trace id, code and a payload. Publishing is fire-and-forget — a metric must
never be able to break the audio path.

`MetricEventType` is `FATAL_ERROR`, `ERROR`, `COMMAND`, `INFORMATION`, `IMPORTANT_INFORMATION`,
`SECURITY`, `WARNING` and `DEBUG`. `ProcessType` is `FLOW`, `CRON` or `INDEPENDENT`.

# Codes worth knowing

| Code | Published when |
|---|---|
| `agenda_build_completed` | every agenda build finishes |
| `scene_content_gap` | a scene underfills by more than 360 seconds |
| `scene_started` | the active scene changes |
| `entries_scheduled` | a scene's entries are handed to timers |
| `entry_emitting_started`, `entry_emitted` | an entry starts and completes emission |
| `entry_failed`, `cascade_entry_failed` | emission failures |
| `dj_boost_applied` | live boost forced an intro |
| `intro_tts_audio_generated` | spoken intro audio was produced |
| `silence_risk` | the watchdog suspects dead air |
| `song_played`, `queue_dequeue` | aivox playout progress |
| `chat_summary_created`, `chat_summary_failed` | chat summarization |
| `ots_start_received`, `ots_start_ok`, `ots_start_failed` | OTS command handling |
| `ots_entry_failed` | OTS emission failure; payload includes root cause, stack snippet and unwrapped CompositeException causes |
| `backpressure_ignored_ots` | backpressure was skipped on an OTS |

# Silence watchdog

A scheduled 60-second check publishes `silence_risk` once a stream has been quiet past the grace period
of 120 seconds. It warns only — it never stops a stream, and the automatic stop it once had was removed
deliberately.

# Traces

A trace id threads a build through its emissions, so a scene can be followed end to end. `silence_risk`
is a cron process and so does not appear inside a flow trace. metriq's snapshot endpoint is capped,
while per-slug trace queries are not.
