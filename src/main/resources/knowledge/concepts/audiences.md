---
type: Concept
title: Audiences
description: The four audiences a Mixpla agent serves — user, artist, owner, developer — derived from labels on the Listener.
tags: [audience, labels, listener, roles, permissions]
---

# Audiences

Who an agent is talking to is derived from the labels attached to the caller's Listener row. Every
signed-in caller is a `user`; labels add to that rather than replacing it.

| Audience | Granted by | Sees |
|---|---|---|
| user | signed in, no label needed | listening, chat, signing up, running your own station |
| artist | `artist` label | submission and upload knowledge, plus everything a user sees |
| owner | `owner` label | deeper station-operation knowledge |
| developer | `developer` label | service internals, shared contracts, messaging |

# Audience is about tone and depth, not permission

An audience decides how much detail an agent gives, **not** what the person is allowed to do in the
product. Creating an account, creating a brand, uploading music and running a station are all
self-service and need no label at all — a plain `user` can do every one of them. Never treat a missing
`owner` label as "this person cannot have a station".

# Where labels live

Labels are rows in the shared `__labels` table, keyed by an identifier string. The listener link is
`mixpla__listener_labels`.

# Read-only from chat

Labels are read-only in chat: an agent may look them up, but assignment of `owner` and `developer`
happens in datanest or admin. Asking an assistant to grant a label does not work. The `artist` label
is the only one a listener-facing tool may attach.

That restriction is about **chat labels only**. It says nothing about product access, so it must never
be relayed as "the Mixpla team has to set you up" — signing up and creating a station are self-service.

# Effect on knowledge

Knowledge concepts carry an optional `audience` list in their frontmatter. A concept without the key
is visible to everyone; a concept with one is only returned to matching audiences. This is why the
same question can produce different depth for a user and a developer.
