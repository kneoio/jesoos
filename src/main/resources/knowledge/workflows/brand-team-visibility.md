---
type: Workflow
title: Brand team visibility
description: Why every owner and co-owner of a station sees all of its songs, where the two grants happen, and the deliberate absence of a revoke path.
tags: [visibility, co-owner, team, rls, brand, backfill, library]
audience: [owner, developer]
---

# The policy

Every **owner and co-owner** of a brand sees **all** songs assigned to that brand, no matter which team
member saved them. A user's visible library is the union of owner-plus-co-owner membership across every
brand a fragment is assigned to. The role does not matter; brand membership does.

# Why the base grant is not enough

The brand-library read is scoped by **per-user fragment access**, not by brand membership:

```sql
-- SoundFragmentBrandRepository.findForBrandFlat
JOIN mixpla__sound_fragment_readers rls ON t.id = rls.entity_id
WHERE bsf.brand_id = $1 AND rls.reader = $2   -- $2 is the caller
```

On save the creator gets a reader row, but co-owners of the same brand do not — so without an extra
grant each team member would only see their own songs even inside a shared brand.

# The two grant points

Both grant a **full** reader row, with `can_edit` and `can_delete`, to the brand's owner and every
co-owner. Both are `ON CONFLICT DO NOTHING` and run inside the caller's transaction.

| When | Where | Scope |
|---|---|---|
| A fragment is assigned to a brand, on create or update — both funnel through `addBrands` | `SoundFragmentBrandAssociationHandler.grantFragmentRlsToBrands` | that fragment, for each newly added brand's owner and co-owners |
| A brand is saved | `BrandRepository.backfillFragmentRlsForBrandMembers` | every song already assigned to the brand, for its current owner and co-owners |

The backfill on brand-save is what lets a **newly added co-owner** retroactively see songs saved before
they joined: the grant reads `b.owner` after the owner JSON is written in the same update, so it always
reflects the new membership.

Do **not** also expand brand owners into `rlsActions` on fragment create or update. That double-granted
the same reader-and-entity pair and collided with explicit client grants. Team visibility is exactly the
two points above.

# Deliberate non-goals

There is **no revoke**: removing someone from owner or co-owner does not drop their fragment reader
rows, so access stays once granted. New members only gain the songs that exist when they are added, or
on the next brand save.

There is no schema change — it reuses `mixpla__sound_fragment_readers` as it is. And access is full
rather than read-only: co-owners can edit and delete each other's songs by design.
