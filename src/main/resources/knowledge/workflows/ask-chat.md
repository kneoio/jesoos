---
type: Workflow
title: Ask Mixpla
description: Internal platform-knowledge chat with no brand context, isolated from listener chat.
tags: [ask, chat, knowledge, auth, otp, mixplaclone]
---

# Ask Mixpla

The internal platform-knowledge assistant, presented as Mixplaclone. It has no brand context and
runs on its own WebSocket and agent. Persistence uses `ChatType` `ASK` with the scope key `mixpla`.

# Authentication

A token may be supplied on WebSocket connect, using the same session-token store as public chat.
Sign-in is by email OTP and upgrades the session; unlike public chat there is no brand listener
upsert. Anonymous users are driven through sign-in before any platform answers. Logoff clears Ask
history only.

Anonymous sessions are workstation-scoped: the frontend keeps a stable `anonId` locally and passes
it on connect.

# Tools

`search_platform_knowledge` for platform facts, `listener_data` so the assistant knows whom it is
speaking to, plus the sign-in and sign-out tools.
