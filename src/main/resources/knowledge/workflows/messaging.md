---
type: Workflow
title: Inter-service messaging
description: Services talk over RabbitMQ on three logical channels, with DTOs shared from 2next.
tags: [messaging, rabbitmq, queues, dto, architecture]
audience: [developer]
---

# Inter-service messaging

Services talk over RabbitMQ, not REST. Async messaging is preferred because the platform targets
Kubernetes-native horizontal scaling and synchronous REST between services does not scale cleanly
across pods. Some REST calls still exist for legacy reasons and are migrated to messaging when
touched.

# Channels

| Channel | Direction | Message |
|---|---|---|
| Streaming / entities | jesoos to aivox | One `SongQueueMessageDTO` per timeline entry, routing key is the brand slug |
| Metrics | every service to metriq | `MetricEventDTO` |
| Commands | between services | `CommandDTO` |

The DTOs live in `com.semantyca.mixpla.dto.queue.*` in the shared 2next library.
