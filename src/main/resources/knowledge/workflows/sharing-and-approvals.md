---
type: Workflow
title: Sharing and approvals
description: How a song is offered to a station and reviewed — the received inbox, the status lifecycle, the two access layers, reversibility, and why an accepted share is not the same as library membership.
tags: [sharing, share, contribution, approval, approve, review, accept, reject, received, inbox, pending, accepted, rejected, rls, archive, catalog, library, song]
audience: [owner, artist, developer]
---

# Sharing and approvals

A **share** is one party offering a sound fragment to a target station, which can then accept or
reject it. Station-to-station sharing and an outside artist's contribution are the *same* mechanism:
one row in `mixpla__shared_sound_fragments` (`SharedSoundFragment`) carrying one `ApprovalStatus`. A
contribution used to be its own system with a `LifecycleStatus` column on the fragment; that is gone.

Every new share starts as pending, unconditionally. The target station's submission policy is checked
when the offer is made (`patchShares` → `validateAndBuildEntities` requires `NO_RESTRICTIONS`), but
nothing is auto-accepted or auto-rejected by genre — genres are shown to the reviewer as tags and a
human decides. A station may be offered something well outside its usual genres.

| Status | Value | Meaning |
|---|---|---|
| `PENDING` | 506 | waiting for the receiver's decision |
| `ACCEPTED` | 500 | accepted |
| `REJECTED` | 501 | rejected |

The numbers reuse older operational codes, so no data migration was needed. The duplicate `505` for
accepted and `502 REJECTED_NOT_MEET_GENRE` are gone; code compares through the enum.

# Accepting does not add the song to the library

This is the rule that surprises people. Accept and reject only flip the status. They do **not** create
a `mixpla__brand_sound_fragments` row and do not grant access to the fragment itself. Getting a track
into a station's library is a separate action — editing which brands the fragment is represented in,
through `SoundFragmentBrandAssociationHandler`.

So "I accepted it, why isn't it in my catalog" is expected behaviour rather than a bug: acceptance is a
decision about the offer, and library membership is a separate edit. The coupling runs neither way —
removing a brand from a fragment's association list used to auto-reject an accepted share, and no
longer touches share status at all.

**Airplay is the exception worth knowing.** jesoos does not need a brand association to *play* a
received song: its shared-fragment queries join `mixpla__shared_sound_fragments` directly and require
`ssf.status = ACCEPTED`, so accepting an offer is enough for that track to enter the station's song
pool. Two independent paths therefore exist — the station's own catalog through brand association, and
received songs through accepted shares. Older datanest prose claiming playback eligibility is brand
association "same as any other fragment" is stale for the shared path.

# Why the fragment is not associated at creation

An offered fragment deliberately gets no brand association and no fragment-level access at creation.
That fixed a real problem: when the association existed immediately, a contributor who later
registered a real account could never find their own submission again, because it was excluded from the
one page that would have shown it — the unassigned-to-brands view. With no association until someone
acts, it stays visible to the submitter in the meantime.

# The two access layers

Visibility of the offer comes entirely from the share row, through its own access table
`mixpla__shared_sound_fragment_readers`, keyed by the `SharedSoundFragment` id. `insertRlsForReceivers`
grants the target brand's owner **and co-owners**, plus `SuperUser`, immediately at creation. That is
what puts the offer in the received inbox, and every decision endpoint re-checks it
(`id IN (SELECT entity_id FROM mixpla__shared_sound_fragment_readers WHERE reader = $2)`), so a station
owner who was never granted a reader row on that specific share cannot act on it.

It says nothing about whether the underlying song is visible anywhere else. Sharing is a status-only
workflow; the fragment's own access layer is managed separately.

Co-owners were once silently excluded here, because the grant extracted only the single scalar
`owner->>'userId'` through `RlsActionUtil.grantFromJsonField`. It now mirrors the owner-plus-co-owners
pattern used elsewhere.

# The received inbox

Offers appear at `/sound-library/received`, listed by plain single-source queries
(`getSharingPreviewList`, `getSharingPreviewCount`, `getById`). Because contributions are just shares,
there is no merge or dispatch logic and no "origin" concept to display: an artist contribution and a
station-to-station offer are indistinguishable rows behind one status tag.

`SharingPreviewDTO` carries title, artist, genres, labels, sharer name and email, target brand name,
boost and status — plus `uploadedFiles` on the single-item fetch only. It has no `origin` or `regDate`,
since there is no longer a multi-source merge to sort.

# Reversibility

Approve and reject are freely reversible in either direction, any number of times: neither has a status
guard, so rejected can be accepted and accepted can be rejected. Since neither touches brand
association or fragment access, there is nothing to leak or re-grant on either transition.

Both do check `archived = 0`. Once a receiver has deleted a share it can no longer be accepted or
rejected back to life — those calls match zero rows, exactly like an unknown id or an unauthorized
caller. Re-sharing is the only way back, and it produces a fresh pending row rather than restoring the
previous status.

A rejected offer stays visible rather than disappearing. Rejection once deleted every reader row
instantly, which made `REJECTED` unobservable — the record vanished for everyone, including
`SuperUser`, before anyone could see the outcome, and there was no deliberate way to remove it later.

Removal is now a separate archive step, gated on the offer already being rejected
(`WHERE id = $1 AND status = 501 AND …`), which sets `archived = 1`. It cannot be used to quietly drop
a pending or accepted offer.

The Mixdeck detail view has to reflect that reversibility deliberately. Approve and Reject are
**always rendered** rather than conditionally shown — an earlier version toggled them with `v-if` and
caused a visible flash as the real status arrived — and only `:disabled` is state-driven: Approve is
disabled once accepted, Reject once rejected, and both while loading or while a request is in flight.
There is no Delete button in the detail view on purpose; permanently removing a rejected offer is
reachable only from the list's remove action.

# Routes

| Route | Effect |
|---|---|
| `PATCH /received/:id/accept` | status becomes accepted |
| `PATCH /received/:id/reject` | status becomes rejected, everything stays visible |
| `DELETE /received/:id` | archives, only when already rejected |

# Previewing the audio

A receiver needs to *listen* before deciding, and acceptance grants no file access at all, so preview
is a deliberate exception. `SharedSoundFragmentRepository.findById(id, userId)`, behind
`GET /received/:id`, chains `attachPreviewFiles`, which queries `_files` directly by
`sound_fragment_id` and **bypasses the fragment's own access table**.

That is safe only because it is reached exclusively after `findById`'s share-level check has already
passed: the caller is a confirmed reader of this specific share, just not of the fragment. It is
exposed as `SharingPreviewDTO.uploadedFiles` — the same `UploadFileDTO` shape as a normal fragment,
with `type` of `opus` or `original` — and populated **only** on the single-item fetch, never on the
paged lists, to avoid N+1 file queries.

**Known open gap.** The file-serving endpoint the preview relies on,
`GET /soundfragments/files/:id/:slug`, does not appear to check fragment access for cloud-stored files:
the user is passed in but never used for an ownership check. So any authenticated user who knows a
fragment's id and file slug can currently fetch its audio regardless of access rules. Any fix must not
break the receiver preview, which depends on that endpoint being reachable for someone holding only
share-level access.

# What the sender sees

`listBySoundFragmentId`, behind the fragment's Sharing tab, filters only on `archived = 0` and applies
**no** status filter, so a rejected offer is still returned to the sender tagged 501. The frontend shows
every entry with its status, rendering rejection as a muted "Not accepted" rather than an alarming red
"Rejected", so it does not read as something having gone wrong. `ShareDTO.shared` is a legacy
convenience flag that no longer drives visibility — don't assume otherwise from older code.

That guarantee holds only until the receiver deletes it. Archiving sets `archived = 1` on the *same*
row the sender's list filters on, so a permanently deleted rejection silently disappears from the
sender's history too, with no trace and no notice. This was a discussed trade-off rather than an
oversight: making the sender's history durable would need per-side visibility flags, which do not
exist.

# Creating a share

Both entry points build a `SharedSoundFragment` and funnel into `applyPatch` and `insertInTx`.

`patchShares(fragmentId, slug, patch, user)` is station-to-station. Fragment access is already checked
separately, so the `slug` is not about permission — it resolves **whose identity to credit**, since a
fragment can belong to several of a user's brands. That station owner's name and email become
`sourceUserName` and `sourceUserEmail`, shown to the receiver as "shared by".

A fragment with no brand association at all has no station to name, so the frontend sends the literal
sentinel `NO_BRAND` (`NO_BRAND_SLUG`) and `patchShares` attributes the share to the user directly. It is
one branch in one method, and deliberately one route (`PATCH /shared/:slug/:fragmentId`) for both cases.

`shareContribution(...)` is the artist path, called from `createFromBulkUpload` right after a public or
chat submission is inserted, when a target station was named.

Re-sharing the same fragment to the same brand always resets it to pending. The upsert
(`ON CONFLICT ON CONSTRAINT unique_brand_shared_fragment DO UPDATE SET archived = 0, status =
EXCLUDED.status, …`) keeps exactly one row per fragment-and-brand pair, so sharing twice never
duplicates — it silently revives and resets the existing row, with no error or warning either way.
Different target brands are fully independent, each with its own row and status, so accepting for one
station has no effect on another.

# Where status is and is not enforced

The received list and the sender's list both show every share regardless of status, and the frontend
renders status as a tag. The station's regular library page is scoped by brand association instead, which
share acceptance no longer creates. A new query that lists fragments for a brand therefore needs no
contribution-specific status gate: brand association is already the right scope, and for received songs
jesoos applies the accepted-status filter itself.

# Deleting the underlying song

Both cascades were missing and are now in place. A hard delete calls
`sharedSoundFragmentService.deleteBySoundFragmentId(uuid)` first — reader rows, then share rows — and
then deletes the fragment, instead of leaving orphaned shares pointing at a fragment that no longer
exists.

An archive is what the REST delete on a sound fragment has always actually done, and it now calls
`archiveBySoundFragmentId(uuid)` when the main archive succeeded, setting `archived = 1` on every share
for that fragment. Previously the share stayed fully active, so the sender still saw it while the
receiver's queries — which join the fragment and check `sf.archived = 0` — had already stopped showing
it.

# Key files

| Area | File |
|---|---|
| Routes | datanest `rest/SharedSoundFragmentController` |
| Share creation, accept, reject, archive, list mapping | `service/soundfragment/SharedSoundFragmentService` |
| Share queries and transactions, preview files, delete and archive cascades | `repository/soundfragment/SharedSoundFragmentRepository` |
| Brand association and fragment access, independent of share status | `repository/soundfragment/SoundFragmentBrandAssociationHandler` |
| Status enum | `model/cnst/ApprovalStatus` |
| Inbox DTO | `dto/sharing/SharingPreviewDTO` |
| Share entity | `model/soundfragment/SharedSoundFragment` |
| Fragment delete and archive | `service/soundfragment/SoundFragmentService` |
| Mixdeck views | `ReceivedForm.vue`, `ReceivedView.vue`, `SoundFragmentForm.vue`, `ShareToBrandsDialog.vue` |
