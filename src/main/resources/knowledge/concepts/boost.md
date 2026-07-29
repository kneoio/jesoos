---
type: Concept
title: Boost
description: Two different things share the name boost — catalog boost, which biases song selection at build time, and live boost, which forces DJ intros at emission time.
tags: [boost, catalog-boost, live-boost, dj, selection]
audience: [owner, developer]
---

# Boost

The platform uses the word boost for two unrelated mechanisms. Confusing them leads to the wrong
expectation about what a boosted song does.

# Catalog boost

Catalog boost is a property of a song in a station's catalog, and it biases **selection**. The values
are `SUPER_BOOST`, `BOOST`, `NOTHING` and `QUARANTINE`.

A super-boosted song is drawn roughly four times as often as a normal one, a boosted song twice as
often, and a quarantined song is effectively suppressed — excluded from ordered queries and weighted
down to a twentieth in random ones. It changes how often a track is picked. It does not make the DJ
talk about it.

# Live boost

Live boost is a runtime counter that forces **spoken intros**, and it applies at emission time rather
than during the build. It upgrades an entry that was going to play silently into one with an intro,
and a super boost also adds a jingle.

It only fires when the entry has no intro already, the scene has active intro prompts, the DJ is
enabled, and fewer than two intros have run consecutively. Starting a brand grants three super
boosts; enabling the DJ grants three normal ones — that is what makes a station sound talkative right
after it comes up. Each application publishes a `dj_boost_applied` metric.

# In short

Catalog boost changes which songs are chosen. Live boost changes whether the DJ speaks over the next
few of them. Live boost does nothing at all while the DJ is switched off.
