---
type: Workflow
title: DJ intros and TTS
description: The DJ toggle and live boost, how a spoken intro's text and audio are produced at emission, prompt and language resolution, and the draft contract.
tags: [dj, tts, intro, prompt, draft, language, boost, elevenlabs, chat-summary]
audience: [owner, developer]
---

# The split that matters

**What** to say and **whether** to say it is decided at build time and baked into the timeline. The
actual **text and audio** are produced at emission. Talkativity governs how many entries are flagged
`hasIntro`; the DJ toggle governs whether any of them are ever voiced.

# DJ state and live boost

Two independent per-brand runtime flags live in memory in `DjStateService`, not in the agenda.

**DJ enabled** (`isDjEnabled`) is the master gate on TTS and defaults to **off** for cost. With it off
the emitters send songs and jingles only. It is toggled by the `enableDj` and `disableDj` commands
(`CommandService`, reached from `CommandResource` REST or RabbitMQ) and cleared when the brand leaves the
pool (`BrandPool.onRemoved` → `remove`).

**Live boost** is a short decrementing counter that forces intros onto otherwise-silent entries,
independent of talkativity. `activateLiveBoost(brand, entries, type)` sets a
`LiveBoostState(remaining, type)`.

It is evaluated in `StaggeredSongScheduler` at **fire time only**, so the consecutive-intro count
reflects the real play order. It applies only when the entry has no intro, active intro prompts exist,
the DJ is enabled, and fewer than two intros have run consecutively. That count is a single persistent
per-brand tally (`getConsecutiveIntroCount` / `recordIntroEmission`): every emitted entry increments it
when it carries an intro, native or boosted, and resets it to zero otherwise. There is no separate
schedule-time counter — boost is decided in one place.

`consumeLiveBoostEntry` decrements and auto-removes the state at zero or below. `Boost.BOOST` forces a
plain intro type (`INTRO_SONG` or `SONG_INTRO_SONG`); `Boost.SUPER_BOOST` also sets a jingle
(`JINGLE_INTRO_SONG`). `assignBoostPrompt` then picks a random active intro prompt, and each boosted
entry publishes a `dj_boost_applied` **warning** metric.

Activation points in `CommandService`: `startBrand` grants three `SUPER_BOOST` entries so a fresh
station opens lively, and `enableDj` grants three `BOOST` entries as warm-up right after the DJ is
switched on.

DJ-enabled decides whether TTS can happen at all; boost only guarantees a few intros fire where the
shuffler would have stayed silent.

# Which agent speaks

The agenda stores only the agent **id** — `LiveScene.agentId`, snapshotted from the brand at build. The
`AiAgent` behind it, and with it the voice, TTS engine, gain, LLM and manner, is loaded fresh from the
database on **every** emission, so no voice data is ever baked into the agenda.

Both schedulers resolve the id from the scene (`StaggeredSongScheduler` falls back to the stream's
`aiAgentId` only when the scene has none). The scene is the authority because it is re-snapshotted from
the brand on every agenda rebuild, whereas the stream field is refreshed only by `BrandPool.applyBrandAgent`
— called from `FLOW_RESTART` and from `DailyAgendaRebuildService`. A stale stream field used to keep the
previous DJ's voice on air after the agenda had already moved to the new one.

Because the agent is resolved this late, **replacing the DJ needs no agenda rebuild**: `FLOW_RESTART`
patches the live stream and its scenes in place and the next entry speaks in the new voice. Two things
still carry the old voice regardless — the entries already handed to aivox (emission runs
`aivox-delay-seconds` ahead of playback, on top of aivox's own queue) and any generated block cached
before the change, which the voice-aware reuse key now re-renders.

# Generation chain

TTS runs inside the emitters, only when `djStateService.isDjEnabled(brand)` and the entry has an intro:

```
resolveForLanguage(promptId, lang)            (or the CustomAction path)
→ DraftFactory builds the draft               (song facts, sharerName, optional fresh chat summary)
→ LLM spoken text                             (Anthropic or Groq per agent.llmType)
→ TTS engine per agent Voice.engineType       (ElevenLabs | Modelslab | GCP | Fish Audio)
→ mp3 in {uploads}/intro-tts/temp
→ ffprobe → IntroAudioResult(filePath, durationSeconds, gain, engineType)
```

The system prompt is `prompts/introSystemPrompt.hbs` for the regular flow and
`prompts/introActionSystemPrompt.hbs` for a `CustomAction`, Handlebars-rendered with `langInstruction`
and `manner`. The language is BCP-47 locked in the system prompt; non-Latin song and artist names are
transliterated rather than refused; emoji are stripped; and obvious error or "technical difficulty"
output is discarded in favour of the language fallback.

Action intros render `CustomAction.instruction` through Handlebars with context variables before the LLM
call, and may email a debug copy to the owner.

On any LLM or text failure, a per-language canned line from `tts-fallbacks.json` is used. Failing soft
here is intentional — silence on air is worse.

The resulting path and duration go into the DTO `filePaths` map keyed by `IntroKey.*`, and aivox overlays
the intro onto the song according to `mergingMethod`. jesoos never mixes audio.

# Language selection

`AiHelperUtils.selectLanguageByWeight(agent)` does a **weighted-random** pick over the agent's
`preferredLang` list, where each `LanguagePreference` carries a weight. One preference means that
language; none means `EN_US`.

The pick is weighted rather than fixed on purpose: a DJ can speak two or more languages and is meant to
deliver content in different languages across the broadcast, reflecting multilingual audiences. The
weights let a station bias the mix — say 70% local and 30% English — while still varying per intro.

# Prompt resolution

A `DjPrompt` is a **master** prompt (`masterId`) that may have per-language variant children
(`PromptService.resolveForLanguage`). If the master's own `languageTag` equals the chosen language it is
used with `fallBacked = false`. Otherwise `findByMasterAndLanguage(masterId, language)` is tried; if that
finds nothing, the master is used with `fallBacked = true`, and the flag propagates to
`IntroAudioResult`.

# Draft versus prompt

The draft is *facts*, deterministic; the prompt is the *instruction*. `DraftFactory.createDraft`
assembles the context through Groovy templates — song genres and labels, station profile, brand
listeners, chat summary, sharer name, time context, and for generated content weather or news from
external APIs. Drafts are authored in **English** regardless of output language.

The emitter sends `prompt.getPrompt()` plus `"Draft input:\n" + draft` to the LLM under the shared system
prompt.

The debug draft endpoint reports an unknown Groovy variable as HTTP 400 JSON with its property name and
script line, so the editor can point directly to the invalid template reference.

So: draft is facts, prompt is voice, language is weighted per emission. The scheduler does none of this —
it only decides when an entry fires.

# The chat summary contract

The `chatSummary` variable carries ready-made on-air context about the listeners currently in chat — who
they are, what they asked, their `artist` and `owner` labels — so the DJ sounds like the same persona
they were just talking to. It is deliberately the **only** chat variable exposed: non-empty means fresh,
un-aired and worth voicing; empty means say nothing about chat. Busy chat is already ranked and capped
in the BRAND summary (priority tiers, then best-effort interestingness, at most three listeners) — see
`workflows/chat-summaries.md` — so the draft should not try to thin it further.

`DraftFactory` blanks it unless `BrandChatContext.usable()`, and marks it aired only when the rendered
draft contains the literal **`Chat summary`** section. Omitting or probability-gating that section
leaves the summary for a later intro; prefer an emptiness guard (`if (chatSummary)`) so usable chat is
not delayed by chance. Never emit a bare `Chat summary:` label with nothing after it, or the LLM will
invent listeners. Keep that exact label: `introSystemPrompt.hbs` matches on it to avoid mistaking a
chat-mentioned song for the upcoming track.

# Key files

| Area | File |
|---|---|
| TTS | `live/IntroTtsGenerator`, `live/scripting/DraftFactory` |
| Prompt and language | `service/PromptService`, `util/AiHelperUtils` |
| DJ and live boost state | `live/DjStateService`, `live/LiveBoostState` |
| Commands | `service/CommandService`, `rest/CommandResource`, `messaging/CommandConsumer` |
