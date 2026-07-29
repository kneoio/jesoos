---
type: Service
title: aivox
description: Streaming service that mixes audio, consumes messages from jesoos and serves HLS/ICY audio.
tags: [service, aivox, streaming, audio, hls, icy]
audience: [developer]
---

# aivox

Streaming service (Quarkus, reactive/Vert.x). It mixes audio, consumes the messages jesoos sends
over RabbitMQ, and generates and streams HLS/ICY audio.

aivox is the primary candidate for running as many pods, so its inputs arrive as queue messages
rather than synchronous calls. Like jesoos it runs as a trusted system user and skips Row-Level
Security for performance.
