---
type: Workflow
title: Inter-service messaging
description: Services talk over RabbitMQ on three logical channels, with DTOs shared from 2next.
tags: [messaging, rabbitmq, queues, dto, architecture]
audience: [developer]
---

# Inter-service messaging

Services talk over RabbitMQ, not REST. Async messaging is preferred because production may scale
services horizontally later and synchronous REST between instances does not scale cleanly. Some REST
calls still exist for legacy reasons and are migrated to messaging when touched.

# Channels

| Channel | Direction | Message |
|---|---|---|
| Streaming / entities | jesoos to aivox | One `SongQueueMessageDTO` per timeline entry, routing key is the stream slug — the brand slug for radio, the OTS slug for a one-time stream |
| Metrics | every service to metriq | `MetricEventDTO` |
| Commands | between services | `CommandDTO` |

The DTOs live in `com.semantyca.mixpla.dto.queue.*` in the shared 2next library.
