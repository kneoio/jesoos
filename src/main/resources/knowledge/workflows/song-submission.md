---
type: Workflow
title: Song submission intake
description: The two ways an outside track enters the platform — the chat upload and the public web form with an emailed code — and how the submitter's account is resolved.
tags: [submission, contribution, otp, upload, chunked, requires-approval, account]
audience: [artist, developer]
---

# Two entry points

A **contribution** is a track submitted for a station's review. There are exactly two ways in:

| Entry | Where | Who may use it |
|---|---|---|
| AI chatbot | jesoos `UploadSongToolHandler` | a signed-in listener carrying the `artist` label |
| Public web form | datanest `PublicSongSubmissionController`, `/datanest/public/songs/*` | anyone, verified by an emailed code |

Both converge on `SoundFragmentService.createFromBulkUpload(...)`.

# The public web form

1. `POST /public/songs/request-code` — `OtpService.sendOtp(email)` issues a six-digit code with a
   10-minute lifetime, held in an in-memory map, and emails it. It is deliberately **not** consumed on
   success: it stays valid until it expires, because it is re-checked on every chunk of a multi-chunk
   upload and again for "submit another track". A single-use code would break both.
2. `POST /public/songs/chunk` re-validates the email and code on **every** chunk, answering 401
   otherwise. The `stationSlug` is required and read only on the first chunk —
   `FileUploadService.resolveBrandSlugIfNeeded` resolves it to a brand id once and caches it per
   `batchId` — and the descriptive metadata (artist name, genre, country, description) is read only from
   the last chunk, which is the only one `assembleAndProcess` uses it from. Chunks stream to disk, and on
   the last one `assembleAndProcess` extracts audio metadata with FFprobe through `AudioMetadataService`
   and calls `createFromBulkUpload(..., requiresApproval = true, meta)`.
3. `GET /public/songs/status/:batchId/stream` streams post-upload progress over SSE, reusing
   `FileUploadService.streamBulkProgress` and the same `bulkUploadProgressMap` keyed by `batchId` that
   the authenticated bulk-upload dialog polls, rather than duplicating it.

A QA bypass exists for `qa-test@mixpla.io` with code `424242`, bound to that one address, so it cannot
skip verification on a real submission.

# The requiresApproval split

`createFromBulkUpload` is shared with the authenticated station-owner bulk upload, which is an unrelated
flow over self-owned content needing no review. `FileUploadService` derives the flag from which
`controllerKey` called it — `public-submissions` against `sound-fragments-controller` — so the same
creation call behaves differently:

* `false` — an authenticated bulk upload by the catalog's own owner. Typed `USER_UPLOAD`, associated
  with the brand directly, no share created.
* `true` — a public or chat submission. Typed `CONTRIBUTION`, **no** brand association at creation
  (`brandIds = List.of()`), access granted only to the submitter's resolved account and `SuperUser`, and a
  pending share created through `shareContribution(...)` if a target station was named.

Never change this method's behaviour without checking both call sites — a blanket change here silently
affects station owners' own uploads. Both paths set `fragment.setStatus(1)`, a fixed placeholder that is
neither a `LifecycleStatus` nor an `ApprovalStatus`: the fragment carries no approval status of its own,
the share does.

Because a contribution starts with no brand association, the submitter finds their own track under the
unassigned-to-brands view while it waits for a decision. An earlier design associated the brand
immediately, which had the effect that registered submitters could not see their own submission.

# Account resolution

`resolveSubmitterAccount(email)` quietly finds or creates a core user for the submitting address and
grants them access to their fragment. If that person later signs up with the same email, the same
account id is reused — so there is no "claim your submissions" step to go through.

Naming a station is optional at the API level: without one, the track is created but no offer is made
to anyone. The Mixdeck form requires a station in practice.
