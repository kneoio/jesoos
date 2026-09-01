---
type: Workflow
title: Ask Mixpla
description: The internal platform-knowledge chat presented as Mixplaclone — its own WebSocket, agent and tools, isolated from listener chat and extractable as a service.
tags: [ask, chat, knowledge, auth, oidc, mixplaclone, streaming, isolation]
audience: [user, artist, owner, developer]
---

# Ask Mixpla

The internal platform-knowledge assistant, presented as **Mixplaclone**. It lives inside the
protected area, has no brand context and runs on its own WebSocket and agent, deliberately
**isolated** from the in-player brand chat and from the public help chat. It shares the agent mechanics — langgraph4j, tool handlers, the LLM client — but has a separate
controller, service, agent, prompt, tools and history keys so the package can be extracted into its own
service later. Ask turns are never routed through `ChatService`, `ChatAgent` or `PublicChatController`.

The prompt is `resources/prompts/askPrompt.hbs`, and the knowledge corpus is the platform-wide OKF
bundle described in the knowledge base concept.

# Request flow

```
AskChatController (WebSocket /jesoos/ws/ask)
→ AskChatService.processUserMessage / generateBotResponse
→ AskAgent.run(state)     // loadContext → llm ⇄ tool
```

`AskAgent` mirrors `ChatAgent` with a maximum of 8 tool iterations, and there is no brand queue
injection. `loadContext` loads the user's Listener profile into volatile context when one exists.

Models are the same Anthropic stack through `BrandLlmProviderResolver` with a fixed synthetic slug
`mixpla`, since the provider is platform-wide and the brand parameter is vestigial.

# Streaming

The `llm` node uses `ChatLlmClient.streamMessage`, the provider's native SSE, and pushes each text delta
to the client as a WebSocket `CHUNK`. Tool rounds still emit `PROCESSING` status and produce no text
chunks while the model is choosing a tool. After the turn `AskChatService` sends `PROCESSING` done plus
the final `BOT` message, and does not re-send the full text as a single chunk when it was already
streamed.

# Scope and persistence

| | Public / OTS chat | Ask Mixpla |
|---|---|---|
| WebSocket | `/jesoos/ws/chat` | `/jesoos/ws/ask` |
| Brand or event slug | required | none, fixed scope `mixpla` |
| `ChatType` | `PUBLIC` / `OTS` | `ASK` |
| In-memory history keys | `conn_*` / `user_*` | `ask_conn_*` / `ask_user_*` |
| Listener registration | yes, per brand | **never** |
| Summarization | yes, for `PUBLIC` | yes, `ASK` prompt |

Ask history is per user and long-lived, so it is not replayed in full: past ten turns the older part of
the conversation is represented by its latest `ASK` summary instead. See the chat summaries concept for
the prompt split.

The public help chat is a third, separate chat — see the help chat concept.

Database rows still use `brand_name = 'mixpla'` as the scope column, matching the existing schema, with
type `ASK`.

# Authentication

**Keycloak OIDC only.** Ask sits behind the protected area, so the surrounding app has already signed
the caller in; there is no in-chat sign-in, no email OTP and no anonymous mode. The client passes its
OIDC access token as `?token=` on the Ask WebSocket. `AskAuthService` calls Keycloak `userinfo`,
resolves email → local `IUser`, and the connection is accepted. A missing or unresolvable token is
rejected with **401** — the socket is never opened. There is no station listener upsert.

Because every caller is authenticated, the prompt has no anonymous branch and the tool set is fixed:
`search_platform_knowledge` and `listener_data`. There is no `start_auth`, `verify_code` or `logoff`
tool — signing out is the surrounding app's job.

`loadContext` injects the listener profile by `userId` into volatile LLM context when a Listener row
exists, so Mixplaclone knows whom it is speaking to, and `listener_data` can read or write that profile
through the shared tool and handler with no brand registration.

`loadContext` also resolves the caller's **audience** from Listener labels (`Audience.fromLabels`) into
`AskState.AUDIENCES`. That drives two things: `{{audience}}` in the prompt, which sets tone and answer
depth, and the audience filter passed to `search_platform_knowledge`. No Listener row means `user`.
Labels are read-only from chat — `owner` and `developer` are datanest-assigned and the handler refuses
them.

On connect the server sends a `session_token` message carrying `userName` and the caller's Listener
`labels` — the badges the UI shows. It no longer carries a `token` field: the OIDC access token is the
only credential and the client already has it.

# Tools

| Tool | Purpose |
|---|---|
| `search_platform_knowledge` | weighted search over the shared OKF knowledge bundle, scoped to the caller's audience |
| `listener_data` | read and write the listener profile so the assistant knows whom it is speaking to. `get` returns name, nicknames, labels and custom user_data only — no `listener_id` or `user_id`. Mixplaclone must not echo internal identifiers in Mixdeck |

There are no auth, catalog, play, upload, ad or community tools.

# Isolation and extractability

The package root is `com.semantyca.jesoos.service.ask` plus `ws.AskChatController`. Shared as libraries
only: `service.chat.llm.*`, `service.knowledge.*`, `BaseToolHandler`, `ToolNodeResult`,
`ListenerDataTool` and its handler, the tool schema classes, `ChatRepository` and `ChatMessageDTO`.

Extracting it to a new service means lifting `service.ask`, `AskChatController` and the Ask prompt, and
taking `service.knowledge` plus `resources/knowledge/**` along — or pointing at wherever the bundle is
served from — while keeping the LLM and Keycloak dependencies.

# Key files

| Area | File |
|---|---|
| WebSocket | `ws/AskChatController` |
| Orchestration | `AskChatService`, `AskAgent` |
| Connect auth | `AskAuthService` (OIDC userinfo only) |
| Knowledge | `service/knowledge/*` (shared), `resources/knowledge/**` |
| Prompt | `resources/prompts/askPrompt.hbs` |
