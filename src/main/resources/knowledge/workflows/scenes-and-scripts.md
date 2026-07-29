---
type: Workflow
title: Scripts and scenes
description: How a station's programming is authored — scripts, scenes, loop versus one-time blocks, weekdays, talkativity and how songs are sourced.
tags: [script, scene, programming, loop, one-time, talkativity, sourcing, weekdays]
audience: [owner, developer]
---

# Scripts and scenes

A station (brand) is programmed by one or more **scripts**. A script is an ordered container of
**scenes**, and one script produces one agenda build. A brand also needs a timezone and a mandatory
AI agent — without an agent there is nobody to voice intros.

A scene is a programming block. It carries a playlist request (what music), intro prompts (what the
DJ may say), actions, a talkativity value, the weekdays it is active on, its start times, and its
scene type.

# Loop versus one-time

There are two scene types and they compose rather than compete.

* `LOOP` is the 24-hour baseline. It fills any time not claimed by something else, and anchors at
  00:00 if it has no start time.
* `ONE_TIME` preempts the loop for a fixed window at its declared start time, and does not repeat
  again that day.

Gaps are never left empty: whatever a one-time scene does not cover is back-filled by the loop.

# Weekdays

Weekdays use ISO numbering, Monday 1 through Sunday 7. An empty weekday set means the scene is
active every day.

# Talkativity

Talkativity is a probability between 0.0 and 1.0 that decides how often a transition gets a spoken
DJ intro instead of going song-only or jingle-only. It is applied at **build** time, not at emission:
the agenda is sized assuming the expected mix (`talkativity × intro + (1 − talkativity) × jingle`),
not by flipping a coin per entry. Two consecutive intros suppress the next one unless talkativity is
1.0.

Talkativity decides *how much* the DJ talks; the DJ toggle decides *whether* it talks at all.

# How songs are sourced

Each scene's playlist request picks a way of sourcing:

| Mode | Behaviour |
|---|---|
| `RANDOM` (default) | mixes newest, oldest and random picks from the brand catalog plus eligible shared songs |
| `QUERY` | filtered selection (genre, label and so on), quantity-limited, shuffled |
| `STATIC_LIST` | an explicit list of song ids, played in the pinned order |
| `GENERATED` | no songs at all — the content is generated at emission time (ads, news, weather) |

A song already used earlier in the same build is not reused while unused songs remain, and songs
carrying a higher catalog boost are favoured. See the boost concept for the difference between
catalog boost and live boost.
