---
type: Concept
title: Mixpla
description: One system for AI-powered streams — brand radio and one-time streams — formerly the KneoBroadcaster monolith.
tags: [platform, mixpla, architecture, microservices, streams]
---

# Mixpla

Mixpla is one system for AI-powered **streams**. Brand radio and one-time streams (OTS) are kinds of
stream; radio is not the whole product. It was formerly the KneoBroadcaster monolith; the split into
microservices is a deployment and scalability choice, not a product boundary.

Anyone can sign up and run their own streams — it is self-service, with no approval step. What a
stream is is in the streams concept, what the product is *for* is in the positioning concept, and what
a new user actually does is in the getting-started workflow.

The platform runs as Docker Compose on a single production node (clankino). Services talk over
RabbitMQ rather than synchronous REST, so the stack can be split horizontally later without
rewiring call paths. aivox is the first service that would scale out.

# Parts

* Services: jesoos, aivox, metriq, datanest, nivaro, spectra.
* Shared library: 2next, holding cross-service contracts.
* Frontends: Mixdeck (owner/user) and 42next (admin).
