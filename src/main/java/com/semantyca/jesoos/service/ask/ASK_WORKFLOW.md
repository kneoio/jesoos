# Ask Mixpla Workflow — Essential Guide

Internal Mixpla platform-knowledge chat. **Isolated** from public brand / OTS listener chat
(`service/chat`). Same agent mechanics (langgraph4j, tool handlers, LLM client); separate
controller, service, agent, prompt, tools, and history keys so this package can be extracted later.

> Prompt: `src/main/resources/prompts/askPrompt.hbs`
> Knowledge corpus: `src/main/resources/ask/platform-knowledge.md`
> Do **not** route Ask turns through `ChatService` / `ChatAgent` / `PublicChatController`.

---

## 1. Request flow

```
AskChatController (WS /jesoos/ws/ask)
  → AskChatService.processUserMessage / generateBotResponse
      → AskAgent.run(state)   // loadContext → llm ⇄ tool
```

`AskAgent` mirrors `ChatAgent`: max 8 tool iterations. No brand queue / listener profile injection.
`loadContext` is a no-op placeholder (keeps the graph shape extractable).

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

- `userId == 0` ⇒ anonymous. Prompt truncated at `!! AUTHENTICATED ONLY`; tools gated in code.
- Anonymous tools: `start_auth`, `verify_code`.
- Authenticated tools: `search_platform_knowledge`, `logoff`.
- `AskVerifyCodeToolHandler` stores session token only (no `ChatAuthService.registerListener`).
- `AskLogoffToolHandler` clears ask history keys and downgrades the Ask WS session.

Connect: `?token=` optional; invalid/missing → anonymous (same recovery pattern as public chat).

---

## 4. Tools

| Tool | Purpose |
|---|---|
| `start_auth` / `verify_code` / `logoff` | sign-in / sign-out (Ask handlers for verify/logoff) |
| `search_platform_knowledge` | keyword search over `platform-knowledge.md` |

No catalog, play, upload, ad, or community tools.

---

## 5. Isolation / extractability

Package root: `com.semantyca.jesoos.service.ask` + `ws.AskChatController`.

Shared as libraries only: `service.chat.llm.*`, `BaseToolHandler`, `ToolNodeResult`,
`StartAuthTool` / `StartAuthToolHandler.execute`, tool schema classes, `PublicChatSessionManager`,
`ChatRepository` (persistence), `ChatMessageDTO`.

When extracting to a new service: lift `service.ask` + `AskChatController` + ask prompt/knowledge
resources; keep LLM/session deps.

---

## Key files

| Area | File |
|---|---|
| WS | `ws/AskChatController` |
| Orchestration | `AskChatService`, `AskAgent` |
| Auth handlers | `tools/auth/AskVerifyCodeToolHandler`, `AskLogoffToolHandler` |
| Knowledge | `tools/SearchPlatformKnowledgeTool(Handler)`, `resources/ask/platform-knowledge.md` |
| Prompt | `resources/prompts/askPrompt.hbs` |
