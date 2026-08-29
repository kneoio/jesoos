---
type: Workflow
title: One-time stream
description: A single ephemeral stream on its own link that plays a script once and then ends — how it is scoped, how it starts, and what a listener hears.
tags: [ots, event, one-shot, repeatable, link, waiting-melody]
audience: [owner, developer]
---

# One-time stream

A one-time stream (OTS) is one kind of Mixpla stream: it plays one script once, sequentially from the
moment it starts, and is then torn down. It lives on its own slug — something like
`birthday-party-aidazi` — and it is not the continuous brand radio: there is no 24-hour loop baseline
behind it. See the streams concept for the umbrella.

Two things always differ from radio. The DJ is **always on** for an OTS, regardless of the master
brand's DJ toggle — but a scene with no intro prompt still plays its songs without a spoken intro.
And the stream is not started by anyone in advance: it comes up when the first
listener opens the link.

# Two scopes

| | Brand-scoped (`brandId` set) | Owner-scoped (`brandId` null) |
|---|---|---|
| Songs come from | the master brand's catalog | the owner's own catalog |
| Timezone, country, bitrate | inherited from the master brand | owner timezone, 64 kbps, country unknown |
| Audio defaults | from the master brand | OPUS at 64000 |
| AI agent | from the definition, else the master brand's | from the definition, and mandatory |

An owner-scoped OTS is how someone runs a personal event stream from their own music without tying it
to a station.

# Scene duration overrides

The script's scenes carry a nominal duration each, but that is the template. A definition may
override the duration of any of its script's scenes — the real event decides how long each block
actually runs. The agenda build uses the override where one exists and the scene's own duration
everywhere else, and the overridden length is what drives song selection, the timeline and where the
next scene starts.

# What a listener hears

Opening the link starts the stream, so the first thing on air is a short waiting melody while the
agenda is being built and the first track mixed. After that the programme plays through once.

# One-shot versus repeatable

A `ONE_SHOT` definition is terminal: once it finishes, the link stays finished. Reopening it returns
an ended stream rather than starting over, and running the event again needs a new definition. A
`REPEATABLE` definition returns to pending instead, so the next time someone opens the link the whole
thing runs again from the start.

# Event chat

An OTS has its own chat, and the URL is the only gate — guests are not asked to sign in. The event
DJ's personality and context come from the definition's chat context, and guests can search the
catalog and request a song with a spoken intro. The conversation is purged when the stream is torn
down.

# Stopping early

Natural completion notifies the streaming service, which finalizes the run. An explicit stop command
cancels the timers and removes the stream but does not send that notification, so the two paths do not
behave identically.
