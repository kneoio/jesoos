---
type: Frontend
title: Mixdeck
description: The Mixpla web app where anyone signs up, creates their own station, manages its catalog and programming, and talks to Mixplaclone.
tags: [frontend, mixdeck, spa, owner, signup, station-management]
---

# Mixdeck

Mixdeck is the Mixpla web application — the place a user signs up and runs their own station. It is
**self-service**: creating an account and creating a brand need no approval from anyone.

From it a user creates and configures brands, uploads and organises the catalog, authors scripts and
scenes, starts and stops the station, runs one-time streams, reviews tracks other people have submitted
to them, and manages their subscription.

It also hosts the Ask chat UI for Mixplaclone.

Technically it is a Vue single-page application talking to datanest, where row-level security keeps
every query scoped to the signed-in user.
