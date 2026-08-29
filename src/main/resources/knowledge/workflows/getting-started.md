---
type: Workflow
title: Getting started — anyone can run a station
description: Mixpla is self-service: a normal user signs up in Mixdeck, creates their own brand, fills it with music and puts it on air. No approval and no team involvement.
tags: [signup, register, onboarding, create-brand, create-station, self-service, mixdeck, free, pro, agenda, schedule, start, link]
---

# Anyone can create a station

Mixpla is **self-service**. Anyone can sign up in Mixdeck, create their own brand — a brand *is* a
station — fill it with music, and put it on air. There is no waiting list, no approval step, and
nobody from a Mixpla team has to enable anything first.

**Do not tell a user to contact a team to get an account or a station.** That is wrong. The only
things that limit what someone can do are their subscription plan and their own catalog.

# What a new user does

1. **Sign up in Mixdeck**, the Mixpla web app.
2. **Create a brand.** Give it a name and slug, a timezone and country, and pick the AI DJ agent that
   will host it — its voice, languages and personality.
3. **Add music.** Upload your own tracks into the brand's catalog. Every upload is analysed
   automatically, so each track arrives with its BPM, key, loudness, moods and genres already on it.
4. **Say how the station should behave.** A script holds scenes, and a scene says how to source songs
   for a stretch of the day, how talkative the DJ is, and what it should talk about. A station with one
   24-hour scene is perfectly valid.
5. **Press start.** That is all. Mixpla builds the day's plan itself and begins streaming on the
   station's own link, with the DJ speaking between tracks.
6. **Share the link.** Listeners open the station's public address — the player host plus the station
   slug — and can chat with the DJ there. Nobody needs an account to listen.
7. **Optionally run a one-time stream.** A one-off event stream on its own link — a party, a
   presentation, a ceremony — that plays once and ends, with its own guest chat.

# You never build the agenda

The **agenda** is the day's actual running order: which songs, in which order, at which minute, and
where the DJ speaks. Mixpla builds it, not the user.

It is built automatically when the station is started, and rebuilt every night so programming changes
are picked up. For a one-time stream there is no start button at all — the first person to open the link
triggers the build and the stream together.

What a user authors is the **script and its scenes**: the intent, not the running order. Never tell
someone they have to build or maintain an agenda or a schedule by hand.

# What the plan controls

The plan sets the limits, not permission. Free is what you get with no subscription at all; Pro is
paid, or granted temporarily by a promo code. A plan decides how many stations you may run, how large
your catalog may be, the audio quality and stream duration, whether you may create one-time streams,
whether you may bulk-upload, and whether you may write custom scripts.

# Where the confusion comes from

Two different things share the word "owner".

Being the **owner of a brand** is simply having created it, or having been given access to it. It is
self-service and needs no label.

The `owner` **chat label** is something else entirely: it is a hint on a Listener row that tells a
station's DJ and Mixplaclone that they are talking to a station owner, so they can adjust tone. Chat
labels are assigned in datanest and cannot be granted from chat, but they grant no product access —
not having one does not stop anyone from creating a station.

Likewise the `artist` label only matters for submitting a track into **someone else's** station through
its chat. It has nothing to do with uploading music into your own catalog in Mixdeck. The `artist` label is
not configured in Mixdeck — instead, the listener talks to the station's AI DJ in the player chat, convinces
the DJ they are an artist, and the DJ grants the `artist` label dynamically right in the conversation.
