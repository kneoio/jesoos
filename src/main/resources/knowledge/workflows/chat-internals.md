---
type: Workflow
title: Listener chat internals
description: The langgraph4j agent behind the public chat — request routing, model selection, prompt caching, auth gating and the full tool set.
tags: [chat, agent, langgraph4j, tools, auth, anthropic, prompt-caching]
audience: [developer]
---

# Request flow

```
PublicChatController (WebSocket)
→ ChatService.generateBotResponse
→ PublicChatIntentRouter.decide
     an active ad session → AdContinuationHandler, no LLM call
     otherwise → generateBotResponseCore
→ BrandStaticData → mainPrompt.hbs
→ ChatAgent.run  (langgraph4j: loadContext → llm ⇄ tool, MAX_TOOL_ITERATIONS = 8)
```

Models are Anthropic only, resolved by `BrandLlmProviderResolver`: the main chat turn uses Sonnet and
subsequent tool iterations use the cheaper Haiku. The static body of the prompt is cached, while the
live context and listener context go in a volatile block so caching is not defeated by per-request data.

# Authentication gating

A caller with `user.getId() == 0` is anonymous. Their prompt is truncated at the
`!! AUTHENTICATED ONLY` marker, and the tool list is restricted in code as well — the gate is not
prompt-only.

| State | Tools |
|---|---|
| Anonymous | `inform_owner`, `start_auth`, `verify_code` |
| Authenticated | the above plus `search_brand_sound_fragments`, `get_brand_catalog_summary`, `listener_data`, `find_community_member`, `upload_song`, `assess_track`, `import_from_suno`, `play_song_with_intro`, `create_ad` (only when the brand enables ads), `manage_events`, `send_ui_command`, `logoff` |

Sign-in is email, then `start_auth`, then the code, then `verify_code`, which upgrades the session and
sends a deferred session token — after which the agent immediately performs whatever task triggered the
sign-in rather than making the listener ask again.

# Song requests and uploads

The three-turn song request protocol is enforced by the **prompt**, not by code — unlike the ad
sub-graph, which is a code-enforced state machine.

An upload requires the brand's submission policy not to be `NOT_ALLOWED`, an authenticated listener
holding the `artist` label, and `send_ui_command(show_upload_button)` before the file can be attached.
The client then sends `I uploaded a file: <filename>` and the agent calls `upload_song` with the temp
filename, title, artist and genre.

`import_from_suno` downloads `https://cdn1.suno.ai/<id>.mp3`, scrapes `https://suno.com/song/<id>` for
metadata, maps the genre tags through `AiHelperService.resolveGenreNamesToIds`, and confirms with the
listener before handing off to `upload_song`.

# Listener memory and community

`listener_data set` stores durable fields: preferred name, city, country, favourite genre and artists,
language, company, profession, community group, interests and notes. `find_community_member` allows warm
recognition but is privacy-limited to first names. `manage_events` lists or upserts events only on
explicit request.

The `owner` label is surfaced in listener context and shapes tone, but there is **no** `isOwner` code
gate and no owner-only tools in the listener chat yet — that was deliberately deferred.

# OTS mode

The same controller, service and agent serve an event stream, branching on the slug: a slug that is not
a brand is looked up through `OtsDefinitionRepository.findBySlugName` and marks the session as OTS.
There is no authentication — the URL is the gate. The prompt is `otsPrompt.hbs` with the event name and
context taken from the definition's chat context. Only two tools are offered
(`search_brand_sound_fragments` and `play_song_with_intro`), served by OTS-specific handlers that route
through `OneTimeStreamPool` under the OTS slug. History is persisted under `ChatType.OTS` and purged on
teardown.
