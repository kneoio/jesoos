---
type: Workflow
title: Brand team visibility
description: Why every owner and co-owner of a station sees all songs saved to it, how the grants happen, and what deliberately does not happen.
tags: [team, co-owner, visibility, catalog, grants, backfill]
audience: [owner, developer]
---

# The policy

Every owner and co-owner of a station sees **all** songs assigned to it, no matter which team member
saved them. Role does not matter — being on the brand does. Someone's visible library is the union of
what all their brands' teams have saved.

Access is full, not read-only: co-owners can edit and delete each other's songs. That is deliberate.

# Why an extra step is needed

The brand library query scopes by per-user access to the *fragment*, not by brand membership:

```sql
JOIN mixpla__sound_fragment_readers rls ON t.id = rls.entity_id
WHERE bsf.brand_id = $1 AND rls.reader = $2
```

On save only the creator gets a reader row, so without additional grants each team member would only
ever see their own uploads.

# The two grant points

| When | Where | What is granted |
|---|---|---|
| A fragment is assigned to a brand, on create or via `addBrands` | `SoundFragmentBrandAssociationHandler.grantFragmentRlsToBrands` | that fragment to each newly added brand's owner and co-owners |
| A brand is saved | `BrandRepository.backfillFragmentRlsForBrandMembers` | every song already on the brand to the brand's current owner and co-owners |

Both grant edit and delete, are idempotent (`ON CONFLICT DO NOTHING`) and run inside the caller's
transaction. The backfill on brand-save is what makes a **newly added co-owner** see the station's
existing catalog rather than only songs added after they joined; it reads the owner JSON written by the
same update.

Do not expand brand owners into `rlsActions` on fragment create or update — that produced double-grant
collisions with the explicit grants the client already sends.

# Deliberate non-goals

There is **no revoke**. Removing someone from a brand's owners or co-owners does not drop their
existing access to the songs, so they keep it until it is cleaned up by hand. And there is no schema
change: this all reuses `mixpla__sound_fragment_readers`.
