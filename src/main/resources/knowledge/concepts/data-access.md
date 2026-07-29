---
type: Concept
title: Data access and row-level security
description: How datanest scopes every row to the user allowed to see it, and why jesoos and aivox deliberately skip that machinery.
tags: [rls, security, datanest, acl, readers, superuser, scoping]
audience: [developer]
---

# Where RLS applies

Row-level security is how **datanest** scopes every row to the user allowed to see it. datanest is the
CRUD backend for Mixdeck and 42next, so every query is user-scoped: a user only ever sees or edits rows
they have been granted.

`jesoos` and `aivox` do **not** use RLS. They run as a trusted system user (`SuperUser`) and skip the
ACL join for performance. This is a datanest-only concern and must never be ported into the backend
services.

# Mechanism

Every entity table `X` has a companion ACL table (`entityData.getRlsName()`, typically `X_readers`):

| Column | Meaning |
|---|---|
| `reader` | the user id allowed to access the row |
| `entity_id` | foreign key to `X.id` |
| `can_edit` | may update the row |
| `can_delete` | may delete the row |
| `reading_time` | last-read bookkeeping |

It is implemented in the shared 2next core — `com.semantyca.core.repository.AsyncRepository`,
`repository.rls.RLSRepository` and `repository.rls.RlsActionUtil`. datanest repositories extend
`AsyncRepository` and pass the `IUser` into every call.

# Read, create, write

Every select joins the entity to its ACL table on the caller:

```sql
SELECT t.* FROM X t JOIN X_readers rls ON t.id = rls.entity_id
WHERE rls.reader = <user.id>
```

No matching ACL row means the record is invisible to that user. Counts, single fetches and paged lists
are scoped identically — **there is no unscoped read path**.

On insert the repository, in one transaction, writes the entity, then grants the creator a reader row
with `can_edit` and `can_delete` through `insertRLSPermissions`, then applies any additional shares via
`applyRlsActions`.

Update and delete are gated on the caller's ACL row carrying `can_edit` or `can_delete`; without the
flag the write is rejected.

# SuperUser

Every reader-granting helper in `RlsActionUtil` also calls `ensureSuperUserAccess`, which grants each
`i_su = true` user — including `SuperUser`, id 1 — a reader row on the same entity. When something needs
guaranteed read access with no real caller identity, such as a public-facing endpoint, call the
repository with `SuperUser.build()` as the `IUser`; the existing scoped queries already find its row, so
no query changes are needed.

# Rules

Always pass the real `IUser` into datanest repositories and never bypass the ACL join or read unscoped.
A new CRUD entity gets RLS wired from day one — ACL table, scoped queries and grant-on-insert — exactly
like the existing repositories. Repositories are reached only through their service, so scoped access
stays behind the service boundary. And none of this is copied into jesoos or aivox, which are
intentionally RLS-free for speed.
