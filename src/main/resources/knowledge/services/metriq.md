---
type: Service
title: metriq
description: Metric collector service that consumes RabbitMQ metric messages for dashboards and runs shared-data maintenance crons.
tags: [service, metriq, metrics, observability, dashboards]
audience: [developer]
---

# metriq

Metric collector service (Quarkus, reactive/Vert.x). It consumes the RabbitMQ metric messages that
aivox and jesoos publish and renders them for dashboards and stats in its own frontend
(`metriq/frontend`). It also runs shared-data maintenance crons.

Metrics complement logs rather than replacing them: important events are published as metrics while
meaningful logs are still kept.
