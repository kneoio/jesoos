---
type: Workflow
title: Requesting a song
description: What happens when a listener asks the DJ to play something — the search, the shout-out (typed or recorded), and why it is queued rather than played instantly.
tags: [request, song, shout-out, dedication, chat, listener, voice]
---

# Requesting a song

Asking the station's DJ for a track in chat takes three turns, by design.

First the DJ searches the station's catalog and comes back with a numbered list of what it found. It
does not queue anything yet, because a search result is not a decision.

Then it confirms which one you meant, and asks whether you want something on air before the track: a
typed shout-out the DJ reads, a recorded greeting the DJ introduces, or your own voice as the intro.

Only then is the song queued. The DJ says it has been queued, not that it is playing now, because it
goes into the station's line-up behind whatever is already buffered. There can be a few minutes
between the confirmation and hearing it.

# Voice greetings

A signed-in listener can record a short greeting. The DJ hears a transcript first (language is
detected automatically). If the take is unclear or not fit for air, the DJ asks for another recording
or a typed message. If it is fine, the original audio goes on air — either after a short DJ
announcement (`INTRO_LISTENER_SONG`) or as the intro itself (`LISTENER_SONG`).

# Limits

The DJ can only play what is in that station's catalog — it cannot fetch a track from the internet on
request. If nothing matches, nothing is queued.

At a one-time event stream the same request flow works without signing in, since the event link itself
is the invitation. Event chat is still typed shout-outs only.
