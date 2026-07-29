---
type: Workflow
title: Brand radio
description: Continuous brand radio — a station that runs indefinitely from a programmed script, with an AI DJ speaking between tracks.
tags: [radio, agenda, scenes, timeline, station, dj, boost]
audience: [owner, developer]
---

# Brand radio

A brand radio is a station that runs continuously. It always belongs to a brand — there is no
ownerless radio — and it runs indefinitely once started, unlike a one-time stream which plays a
programme once and ends.

Someone has to start it: the station does not come up because a listener arrived. That is the opposite
of a one-time stream, which cold-starts on the first listener.

# How it works, in order

The station's script is turned into an agenda: an ordered plan of scenes covering the day, each filled
with songs from the catalog and marked where the DJ should speak. jesoos then walks that plan in real
time and hands each entry to aivox slightly ahead of when it is due, and aivox mixes the audio and
serves it as a live stream.

Nothing is pre-rendered as one long file. Each transition is mixed shortly before it is needed, which
is why a change to the DJ toggle or a live boost takes effect within a track or two rather than at the
next rebuild.

# The pieces an owner controls

* Scripts and scenes decide what plays when, on which weekdays, and from which part of the catalog.
* Talkativity decides how often the DJ speaks; the DJ toggle decides whether it speaks at all.
* Catalog boost biases which songs get picked; live boost forces intros for the next few entries.
* Generated scenes produce ads, news or weather instead of music.

The agenda is rebuilt daily, so a programming change is picked up on the next build rather than
mid-stream.

# When something goes quiet

A watchdog reports a silence risk after roughly two minutes of nothing playing, but it only warns — it
never stops the station. Underfilled scenes are reported as a content gap when they fall short by more
than six minutes.
