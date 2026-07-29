---
type: Workflow
title: Ask Mixpla
description: The internal platform-knowledge chat presented as Mixplaclone — its own WebSocket, agent and tools, isolated from listener chat and extractable as a service.
tags: [ask, chat, knowledge, auth, otp, mixplaclone, streaming, isolation]
audience: [user, artist, owner, developer]
---

# Ask Mixpla

The internal platform-knowledge assistant, presented as **Mixplaclone**. It has no brand context and
runs on its own WebSocket and agent, deliberately **isolated** from the public brand and OTS listener
chat. It shares the agent mechanics — langgraph4j, tool handlers, the LLM client — but has a separate
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
injection. `loadContext` loads the authenticated user's Listener profile into volatile context when one
exists.

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
| Summarization | yes, for `PUBLIC` | no |

Database rows still use `brand_name = 'mixpla'` as the scope column, matching the existing schema, with
type `ASK`.

# Authentication

The same email-OTP and session-token mechanism as public chat (`PublicChatSessionManager`,
`KeycloakAuthService`), but with no station listener upsert.

A caller with `userId == 0` is anonymous, their prompt is truncated at the `!! AUTHENTICATED ONLY`
marker so there is no topic or tools section, and they are driven through email OTP before any platform
question is answered. Anonymous tools are `start_auth` and `verify_code` only; authenticated tools are
`search_platform_knowledge`, `listener_data` and `logoff`.

`loadContext` injects the listener profile by `userId` into volatile LLM context when a Listener row
exists, so Mixplaclone knows whom it is speaking to, and `listener_data` can read or write that profile
through the shared tool and handler with no brand registration on Ask login.

`loadContext` also resolves the caller's **audience** from Listener labels (`Audience.fromLabels`) into
`AskState.AUDIENCES`. That drives two things: `{{audience}}` in the prompt, which sets tone and answer
depth, and the audience filter passed to `search_platform_knowledge`. No Listener row means `user`.
Labels are read-only from chat — `owner` and `developer` are datanest-assigned and the handler refuses
them.

`AskVerifyCodeToolHandler` stores the session token only, without
`ChatAuthService.registerListener`, and `AskLogoffToolHandler` clears the Ask history keys and downgrades
the Ask WebSocket session.

On connect, `?token=` is optional and an invalid or missing token falls back to anonymous, the same
recovery pattern as public chat. An anonymous session is workstation-scoped: the frontend keeps a stable
`anonId` in local storage and passes it as the WebSocket `connectionId`.

# Tools

| Tool | Purpose |
|---|---|
| `start_auth` / `verify_code` / `logoff` | sign-in and sign-out, with Ask-specific verify and logoff handlers |
| `search_platform_knowledge` | weighted search over the shared OKF knowledge bundle, scoped to the caller's audience |
| `listener_data` | read and write the listener profile so the assistant knows whom it is speaking to |

There are no catalog, play, upload, ad or community tools.

# Isolation and extractability

The package root is `com.semantyca.jesoos.service.ask` plus `ws.AskChatController`. Shared as libraries
only: `service.chat.llm.*`, `service.knowledge.*`, `BaseToolHandler`, `ToolNodeResult`, `StartAuthTool`
and `StartAuthToolHandler.execute`, `ListenerDataTool` and its handler, the tool schema classes,
`PublicChatSessionManager`, `ChatRepository` and `ChatMessageDTO`.

Extracting it to a new service means lifting `service.ask`, `AskChatController` and the Ask prompt, and
taking `service.knowledge` plus `resources/knowledge/**` along — or pointing at wherever the bundle is
served from — while keeping the LLM and session dependencies.

# Key files

| Area | File |
|---|---|
| WebSocket | `ws/AskChatController` |
| Orchestration | `AskChatService`, `AskAgent` |
| Auth handlers | `tools/auth/AskVerifyCodeToolHandler`, `AskLogoffToolHandler` |
| Knowledge | `service/knowledge/*` (shared), `resources/knowledge/**` |
| Prompt | `resources/prompts/askPrompt.hbs` |
