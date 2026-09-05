---
type: Service
title: jesoos
description: Content delivery service that builds agendas, runs public brand/OTS chat and Ask chat, and sends sequential messages to aivox.
tags: [service, jesoos, content-delivery, agenda, chat]
audience: [developer]
---

# jesoos

Content delivery service (Quarkus, reactive/Vert.x). It builds the agenda from scripts and scenes,
runs the public brand and OTS chat as well as the internal Ask chat, and sends sequential messages
to aivox over RabbitMQ.

# Responsibilities

* Build the agenda (scripts and scenes) and emit a live timeline to aivox.
* Host listener-facing brand/OTS chat and the internal Ask chat.
* Transcribe listener recordings with Google STT (`GCPSTTClient`, same GCP credentials as TTS).
* Publish metric events to metriq.

jesoos runs as a trusted system user and skips Row-Level Security for performance.
