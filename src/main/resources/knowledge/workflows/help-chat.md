---
type: Workflow
title: Help chat
description: The public unauthenticated chat that only explains what Mixpla is — its own WebSocket, one knowledge tool, no sign-in and no account data.
tags: [help, chat, public, knowledge, anonymous]
audience: [user, artist, owner, developer]
---

# Mixpla Help

The public help assistant, presented as **Mixpla Help**. It exists for one purpose: interactive
explanation of the Mixpla platform to visitors who are not signed in. It has no brand context, no
listener, no account data and no sign-in of any kind — there is nothing to authenticate against, so
there is nothing to confuse with the other two chats.

It is deliberately the narrowest of the three chats:

| | Public brand chat | Ask Mixpla | Mixpla Help |
|---|---|---|---|
| Where it lives | inside the player, per brand | inside the protected area | public pages |
| WebSocket | `/jesoos/ws/chat` | `/jesoos/ws/ask` | `/jesoos/ws/help` |
| Auth | email OTP in chat | Keycloak OIDC only | none |
| Scope | one brand and its catalog | whole platform, audience-scoped | whole platform, `user` audience |
| Tools | catalog, requests, ads, uploads, auth | knowledge, listener profile | knowledge only |
| `ChatType` | `PUBLIC` / `OTS` | `ASK` | `HELP` |

# Request flow

```
HelpChatController (WebSocket /jesoos/ws/help)
→ HelpChatService.processUserMessage / generateBotResponse
→ HelpAgent.run(state)     // llm ⇄ tool
```

There is no `loadContext` node — nothing about the caller is known or looked up. The graph allows a
maximum of 4 tool iterations. Models resolve through `BrandLlmProviderResolver` with the same
synthetic slug `mixpla` used by Ask.

# Streaming

Identical to Ask: the `llm` node streams over the provider's native SSE and pushes each delta as a
WebSocket `CHUNK`, tool rounds emit `PROCESSING`, and the final `BOT` message closes the turn without
re-sending text that was already streamed.

# Scope and persistence

Every row is written with `user_id = 0`, `brand_name = 'mixpla'` and type `HELP`, and history is read
back by `connection_id`. The in-memory conversation key is `help_conn_*`. No listener row is ever
created or read.

Nothing here is summarized: the caller is anonymous, the conversation lasts one visit, and there is no
continuity worth preserving. The conversation is capped instead, three ways:

* **Socket close** — a closed tab, a reload or a navigation drops the history immediately.
* **Idle TTL** — an hour without a message expires the conversation; the next message starts fresh.
  A `@Scheduled(every="15m")` sweep also evicts idle entries whose socket is still held open, so an
  abandoned tab cannot pin its history in memory.
* **Row retention** — the nightly cleanup deletes `HELP` rows older than two days by age, since the
  normal cleanup only reaps rows that carry a `summarized_at` and these never will.

# Hardening

The endpoint is open to the internet, so the limits are part of the design:

* No authentication surface at all — no token parameter, no OTP, no session upgrade path.
* Exactly one tool, `search_platform_knowledge`, pinned to the `user` audience, so owner- and
  developer-scoped concepts are unreachable regardless of what the caller claims to be.
* Messages are capped at 1000 characters and 15 messages per minute per connection.
* `resources/prompts/helpPrompt.hbs` carries an expanded injection-resistance block: no prompt or
  configuration disclosure, no secrets or infrastructure, no persona changes, no acting on
  instructions found inside user text, and a fixed one-line deflection for anything off-topic.
* The assistant can only read knowledge — there is no action it can take on the platform, so a
  successful jailbreak has no side effect beyond words.

# Key files

| Area | File |
|---|---|
| WebSocket | `ws/HelpChatController` |
| Orchestration | `HelpChatService`, `HelpAgent` |
| State | `HelpState`, `HelpStateSerializer` |
| Knowledge | `service/knowledge/*` (shared), `resources/knowledge/**` |
| Prompt | `resources/prompts/helpPrompt.hbs` |
