# Prompt: Mixpla Ask chat (Vue) in Mixdeck

Give this prompt to the Mixdeck agent. Backend (jesoos Ask WS) is ready — see `ASK_WORKFLOW.md`.

### Goal
Add an internal **Ask Mixpla** chat UI in Mixdeck: ChatGPT-style conversation layout, but **visual design must match Mixdeck / Mixpla** (existing colors, typography, spacing, buttons, inputs, dark/light if Mixdeck has it). Do **not** invent a new purple/ChatGPT skin.

### Entry point
On the **Welcome page**, add a clear CTA button (e.g. “Ask Mixpla” / “Chat with Mixpla”) that opens the Ask chat (route or full-page/panel). Reuse Mixdeck button styles.

### UI (ChatGPT-like behavior, Mixdeck look)
Vue SFC(s), fit Mixdeck patterns (composition API, existing router/store/i18n if used).

- Full chat surface: message list + sticky composer at bottom
- User bubbles right / assistant left (or Mixdeck’s existing chat pattern if one exists — prefer consistency with Mixdeck)
- Streaming: append assistant text as `CHUNK` arrives; show a subtle typing/processing state while `PROCESSING` with non-empty content; clear when `PROCESSING` has empty content (`processingDone`)
- Empty state: short welcome line + optional suggested prompts
- Composer: textarea, Enter to send, Shift+Enter newline, disabled while a reply is in flight
- History: load on open via `getHistory`
- Auth UX: anonymous can chat for sign-in tools; after login, handle `session_token`; support logoff if backend sends it
- Errors: show `ERROR` messages inline, Mixdeck toast if that’s the app pattern
- Responsive: usable on desktop; mobile ok

### Backend (jesoos — already ready)
**WebSocket:** `ws(s)://{jesoos-host}/jesoos/ws/ask?token={optionalSessionToken}&anonId={optionalAnonId}`

- No `brandSlug`. Platform-scoped Ask only.
- Keep a stable `anonId` (16-char base64url or UUID) in localStorage for anonymous sessions.
- Pass Mixdeck session `token` when the user is logged in.

**Client → server**

```json
{ "action": "sendMessage", "content": "...", "username": "optional for anon" }
{ "action": "getHistory", "limit": 50 }
```

**Server → client (handle all)**

| Shape | Meaning |
|--------|---------|
| `{ "type": "session_token", "token", "userName" }` | Auth session established |
| `{ "type": "message", "data": { "type": "USER"\|"BOT", "id", "username", "content", "timestamp", "connectionId" } }` | Full message (user echo / final bot) |
| `{ "type": "CHUNK", "content", "username", "connectionId" }` | Stream fragment — append to current assistant bubble |
| `{ "type": "PROCESSING", "content", ... }` | Non-empty = thinking; empty `content` = done |
| `{ "type": "ERROR", "message", ... }` | Error |
| `{ "type": "COMMAND", "content", "payload", ... }` | Optional side effects (e.g. auth) — wire if Mixdeck already handles similar for public chat |

Mirror any existing Mixdeck public-chat WS client if present, but point it at **`/jesoos/ws/ask`** and do **not** send brand/OTS params.

### Constraints
- Match Mixdeck design system only; no new design language
- Minimal scope: welcome CTA + Ask chat page/component + WS client
- No backend changes in Mixdeck for chat logic; jesoos owns the agent
- i18n: follow Mixdeck conventions if the app is localized

### Done when
1. Welcome page has a Mixdeck-styled button to Ask chat
2. Chat opens, connects to Ask WS, sends/receives messages with streaming
3. Looks like Mixdeck, behaves like a simple ChatGPT thread
