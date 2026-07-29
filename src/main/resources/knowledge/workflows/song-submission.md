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

1. `POST /public/songs/request-code` issues a six-digit code with a 10-minute lifetime, held in
   memory. It is **not** single-use — it is re-checked on every chunk.
2. `POST /public/songs/chunk` carries the email and code on every chunk, the station slug on the first
   chunk and the metadata on the last, then `assembleAndProcess` probes the file with FFprobe and
   creates the fragment.
3. `GET /public/songs/status/:batchId/stream` streams progress over SSE.

A QA bypass exists for `qa-test@mixpla.io` with code `424242`.

# The requiresApproval split

The same creation call behaves differently depending on one flag:

* `false` — an authenticated bulk upload by the catalog's own owner. Typed `USER_UPLOAD`, associated
  with the brand directly, no share created.
* `true` — a public or chat submission. Typed `CONTRIBUTION`, **no** brand association at creation,
  access granted to the submitter, and a pending share created if a target station was named.

Because a contribution starts with no brand association, the submitter finds their own track under the
unassigned-to-brands view while it waits for a decision. An earlier design associated the brand
immediately, which had the effect that registered submitters could not see their own submission.

# Account resolution

`resolveSubmitterAccount(email)` quietly finds or creates a core user for the submitting address and
grants them access to their fragment. If that person later signs up with the same email, the same
account id is reused — so there is no "claim your submissions" step to go through.

Naming a station is optional at the API level: without one, the track is created but no offer is made
to anyone. The Mixdeck form requires a station in practice.
