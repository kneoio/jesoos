---
type: Frontend
title: Mixdeck
description: The Mixpla web app where anyone signs up, runs streams (brand radio and OTS), manages catalog and programming, and talks to Mixplaclone.
tags: [frontend, mixdeck, spa, owner, signup, station-management, streams]
---

# Mixdeck

Mixdeck is the Mixpla web application — the place a user signs up and runs their own streams. It is
**self-service**: creating an account and creating a brand need no approval from anyone.

The **Streams** page lists every stream an owner has — continuous brand radio and one-time streams —
at a glance. From Mixdeck a user creates and configures brands, uploads and organises the catalog,
authors scripts and scenes, starts and stops radio, runs one-time streams, reviews tracks other people
have submitted to them, and manages their subscription.

It also hosts the Ask chat UI for Mixplaclone.

Technically it is a Vue single-page application talking to datanest, where row-level security keeps
every query scoped to the signed-in user.
