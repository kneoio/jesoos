---
type: Workflow
title: DJ intros and TTS
description: The DJ toggle, how a spoken intro is generated from prompt to audio, language selection, and the fallback path when generation fails.
tags: [dj, tts, intro, prompt, draft, elevenlabs, llm, language]
audience: [owner, developer]
---

# DJ intros and TTS

Whether a station speaks at all is a single in-memory switch per brand held by `DjStateService`, and
it defaults to **off**. It is the master gate on TTS: with the DJ off, entries that were built with
intros are downgraded to silent transitions. The switch is flipped by the `enableDj` and `disableDj`
commands. Talkativity, by contrast, only decides how often intros appear while the DJ is on.

# Generation chain

Generation happens at emission time, and only when the DJ is enabled and the entry is flagged
`hasIntro`:

```
PromptService.resolveForLanguage
→ DraftFactory.createDraft            (Groovy templates, English facts)
→ LLM  (Anthropic or Groq, per agent.llmType)
→ TTS  (ElevenLabs | Modelslab | GCP | Fish Audio, per Voice.engineType)
→ mp3 in {uploads}/intro-tts/temp
→ IntroAudioResult → DTO filePaths keyed by IntroKey.*
```

The system prompts are `introSystemPrompt.hbs` and `introActionSystemPrompt.hbs`. When generation
fails, per-language canned lines from `tts-fallbacks.json` are used, so a failure costs personality
rather than silence.

# Draft versus prompt

A draft is the *facts* the DJ has to work with; the prompt is the *instruction* on how to say it.
`DraftFactory.createDraft` builds the draft from Groovy templates and includes a chat summary from
`ChatSummaryService` when `BrandChatContext.usable()` — that is how listener conversation reaches the
air. The draft is marked as aired when it is rendered, not when it is emitted.

# Language

`AiHelperUtils.selectLanguageByWeight(agent)` draws from the agent's preferred languages, defaulting
to `EN_US`. `PromptService.resolveForLanguage` resolves the master `DjPrompt` plus its per-language
variant. The chosen language is locked in the system prompt rather than requested in the user text.

# Custom actions

A `CustomAction` is rendered with Handlebars against the same context and can optionally email a
debug copy of the result to the station owner.

# Metrics

Successful audio generation publishes `intro_tts_audio_generated`; forced intros publish
`dj_boost_applied` at warning level.
