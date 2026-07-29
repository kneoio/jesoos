# Ask Mixpla Workflow — Essential Guide

Internal Mixpla platform-knowledge chat. **Isolated** from public brand / OTS listener chat
(`service/chat`). Same agent mechanics (langgraph4j, tool handlers, LLM client); separate
controller, service, agent, prompt, tools, and history keys so this package can be extracted later.

> Prompt: `src/main/resources/prompts/askPrompt.hbs`
> Knowledge: OKF bundle in `src/main/resources/knowledge/` via `service/knowledge` — see
> `KNOWLEDGE_WORKFLOW.md`. Platform-wide, not Ask-owned.
> Do **not** route Ask turns through `ChatService` / `ChatAgent` / `PublicChatController`.

---

## 1. Request flow

```
AskChatController (WS /jesoos/ws/ask)
  → AskChatService.processUserMessage / generateBotResponse
      → AskAgent.run(state)   // loadContext → llm ⇄ tool
```

`AskAgent` mirrors `ChatAgent`: max 8 tool iterations. No brand queue injection.
`loadContext` loads the authenticated user's Listener profile into volatile context (when present).

**Streaming (WS SSE-imitation):** `AskAgent` llm node uses `ChatLlmClient.streamMessage` (Anthropic
provider SSE). Each text delta is pushed as a WS `CHUNK`. Tool rounds still emit `PROCESSING` status;
no text `CHUNK`s while the model is choosing a tool. After the turn, `AskChatService` sends
`PROCESSING` done + final `BOT` (does not re-send the full text as one `CHUNK` if already streamed).

**Models:** same Anthropic stack via `BrandLlmProviderResolver` with a fixed synthetic slug
`mixpla` (provider is platform-wide; brand param is vestigial).

---

## 2. Scope & persistence

| | Public / OTS chat | Ask Mixpla |
|---|---|---|
| WS | `/jesoos/ws/chat` | `/jesoos/ws/ask` |
| Brand / event slug | required | none — fixed scope `mixpla` |
| `ChatType` | `PUBLIC` / `OTS` | `ASK` |
| In-memory history keys | `conn_*` / `user_*` | `ask_conn_*` / `ask_user_*` |
| Listener registration | yes (brand) | **never** |
| Summarization | yes (PUBLIC) | no |

DB rows still use `brand_name='mixpla'` as the scope column (existing schema); type is `ASK`.

---

## 3. Authentication

Same email-OTP + session-token mechanism as public chat (`PublicChatSessionManager`,
`KeycloakAuthService`), but **no** station listener upsert.

- `userId == 0` ⇒ anonymous. Prompt truncated at `!! AUTHENTICATED ONLY` (no topic/tools section);
  anonymous must be driven through email OTP before any platform answers.
- Anonymous tools: `start_auth`, `verify_code` only.
- Authenticated tools: `search_platform_knowledge`, `listener_data`, `logoff`.
- `loadContext` injects listener profile (by `userId`) into volatile LLM context when a Listener
  row exists — so Mixplaclone knows whom it is speaking to. `listener_data` can get/set that profile
  (shared `ListenerDataTool` / handler; no brand registration on Ask login).
- `loadContext` also resolves the caller's **audience** from Listener labels (`Audience.fromLabels`)
  into `AskState.AUDIENCES`. It drives two things: `{{audience}}` in the prompt (tone / answer depth)
  and the audience filter passed to `search_platform_knowledge`. No Listener row ⇒ `user`.
  Labels are read-only from chat: `owner` / `developer` are datanest-assigned and the handler refuses
  them. See `KNOWLEDGE_WORKFLOW.md` §5.
- `AskVerifyCodeToolHandler` stores session token only (no `ChatAuthService.registerListener`).
- `AskLogoffToolHandler` clears ask history keys and downgrades the Ask WS session.

Connect: `?token=` optional; invalid/missing → anonymous (same recovery pattern as public chat).
Anonymous chat is workstation-scoped via FE `anonId` (localStorage) → WS `connectionId`.

---

## 4. Tools

| Tool | Purpose |
|---|---|
| `start_auth` / `verify_code` / `logoff` | sign-in / sign-out (Ask handlers for verify/logoff) |
| `search_platform_knowledge` | weighted search over the shared OKF knowledge bundle, scoped to the caller's audience (`service/knowledge`) |
| `listener_data` | get/set listener profile (who the bot is speaking to) |

No catalog, play, upload, ad, or community tools.

---

## 5. Isolation / extractability

Package root: `com.semantyca.jesoos.service.ask` + `ws.AskChatController`.

Shared as libraries only: `service.chat.llm.*`, `service.knowledge.*` (OKF bundle + search tool),
`BaseToolHandler`, `ToolNodeResult`, `StartAuthTool` / `StartAuthToolHandler.execute`,
`ListenerDataTool(Handler)`, tool schema classes, `PublicChatSessionManager`,
`ChatRepository` (persistence), `ChatMessageDTO`.

When extracting to a new service: lift `service.ask` + `AskChatController` + the ask prompt, and
take `service.knowledge` + `resources/knowledge/**` along (or point at wherever the bundle is
served from); keep LLM/session deps.

---

## Key files

| Area | File |
|---|---|
| WS | `ws/AskChatController` |
| Orchestration | `AskChatService`, `AskAgent` |
| Auth handlers | `tools/auth/AskVerifyCodeToolHandler`, `AskLogoffToolHandler` |
| Knowledge | `service/knowledge/*` (shared), `resources/knowledge/**` |
| Prompt | `resources/prompts/askPrompt.hbs` |
