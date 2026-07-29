---
type: Workflow
title: Sharing and approvals
description: How a song is offered to a station and reviewed — the received inbox, approval statuses, and why accepting a share does not by itself add the song to the library.
tags: [sharing, contribution, approval, received, inbox, pending, accepted, rejected]
audience: [owner, artist, developer]
---

# Sharing and approvals

A **share** is one party offering a sound fragment to a target station, which can then accept or
reject it. Station-to-station sharing and an outside artist's contribution are the *same* mechanism:
one row in `mixpla__shared_sound_fragments` (`SharedSoundFragment`) carrying one `ApprovalStatus`.

Every new share starts as pending. The target station's submission policy is checked when the offer is
made. Nothing is auto-accepted or auto-rejected by genre — genres are shown as tags and a human
decides.

| Status | Value | Meaning |
|---|---|---|
| `PENDING` | 506 | waiting for the receiver's decision |
| `ACCEPTED` | 500 | accepted |
| `REJECTED` | 501 | rejected |

# Accepting does not add the song to the library

This is the rule that surprises people. Accept and reject only flip the status. They do **not** create
a brand association and do not grant access to the audio. Getting a track into a station's library is
a separate action — editing which brands the fragment is represented in.

So "I accepted it, why isn't it in my catalog" is expected behaviour, not a bug: acceptance is a
decision about the offer, and library membership is a separate edit.

# The received inbox

Offers appear in the station's received inbox as soon as they are created, because share visibility is
granted to the target brand's owner **and co-owners** at creation time. Contributions from artists and
shares from other stations look the same there.

Approve and reject are freely reversible — there is no status guard, so a rejected offer can be
accepted later and the other way round. A rejected offer stays visible rather than disappearing.
Removing it entirely is a separate archive action and is only allowed once the offer is rejected.

Once archived, the only way back is re-sharing: offering the same fragment to the same station again
upserts the single row for that pair, resets it to pending and un-archives it.

The receiver can play the audio to review it even though acceptance has not granted any access to the
file — preview is a deliberate exception, available on the single-item view of a received offer.

# What the sender sees

The sending side keeps seeing its offer, including a rejected one, tagged with its status. When the
receiver archives a rejected offer it disappears from the sender's view as well, since there is only
one row and no separate per-side visibility flag.

# Deleting the underlying song

Hard-deleting a fragment deletes its shares first, then the fragment. Archiving a fragment archives all
of its shares. Neither leaves orphaned offers in an inbox.

# Implementation notes

`SharedSoundFragmentRepository` exposes `acceptByReceiver`, `rejectByReceiver` and `archiveByReceiver`;
archive requires status 501. The REST surface is `PATCH /received/:id/accept`,
`PATCH /received/:id/reject` and `DELETE /received/:id`, each gated by reader rows in
`mixpla__shared_sound_fragment_readers` — the share entity's own access layer, not the fragment's.
`SoundFragmentBrandAssociationHandler.removeBrands` no longer auto-rejects shares when a brand is
removed from a fragment.

Creation goes through `SharedSoundFragmentService`: `patchShares(fragmentId, slug, patch, user)` for
station-to-station, where the slug identifies *which* of the sharer's stations gets the attribution
rather than granting any permission, with `NO_BRAND` for fragments not assigned to a station; and
`shareContribution(...)` for an artist submission with a target.

There is one known open gap: the file download endpoint
`GET /soundfragments/files/:id/:slug` may not check fragment-level access, so anyone holding the id and
slug could fetch audio. Any fix has to preserve the receiver's legitimate preview.
