# Workflows

* [Getting started](getting-started.md) - Signing up and creating your own station; it is self-service.
* [Messaging](messaging.md) - How services talk to each other over RabbitMQ.
* [Brand radio](brand-radio.md) - Continuous brand radio, from agenda to live emission.
* [Scripts and scenes](scenes-and-scripts.md) - How a station's programming is authored.
* [Agenda build](agenda-build.md) - The jesoos pipeline that turns a script into a StreamAgenda.
* [Song selection](song-selection.md) - Filling a scene's pool, boost weighting and the fill ladder.
* [Song selection redesign](song-selection-redesign.md) - Proposal only, not implemented.
* [Timeline and mixing types](timeline-and-mixing.md) - Entry layout and the MixingType contract.
* [DJ intros and TTS](dj-intros-and-tts.md) - The DJ toggle and how a spoken intro is produced.
* [Generated content](generated-content.md) - Ads, news and weather assembled at emission.
* [Emission](emission.md) - Tickers, scheduling, entry state machine and backpressure.
* [Streaming pipeline](streaming-pipeline.md) - The aivox side: mixing and segmenting audio.
* [Playout and interrupts](playout-and-interrupts.md) - Queue tiers, HLS serving and interrupts.
* [One-time stream](one-time-stream.md) - A single ephemeral stream on its own link.
* [One-time stream internals](ots-internals.md) - OTS routing, cold start, teardown and reconciliation.
* [Public brand chat](public-chat.md) - Listener-facing WebSocket DJ chat per brand.
* [Requesting a song](song-requests.md) - What happens when a listener asks for a track.
* [Liking and disliking songs](song-ratings.md) - Confirmed listener ratings per station.
* [Listener chat internals](chat-internals.md) - The chat agent, tools and auth gating.
* [Ads in chat](chat-ads.md) - Listener-created advertisements and per-station flags.
* [Chat summaries](chat-summaries.md) - Condensing conversations for on-air use and continuity.
* [Artist submissions](artist-submissions.md) - How a track gets on air via chat upload or Suno import.
* [Song submission intake](song-submission.md) - The chat and public web-form entry points.
* [Sharing and approvals](sharing-and-approvals.md) - Offering a song to a station and reviewing it.
* [Brand team visibility](brand-team-visibility.md) - Why a station's team sees all its songs.
* [Subscriptions](subscriptions.md) - Free and Pro plans, entitlements and billing behaviour.
* [Promo codes](promo-codes.md) - Time-boxed Pro grants without payment.
* [Ask chat](ask-chat.md) - Internal platform-knowledge chat.
