---
type: Concept
title: Data access and row-level security
description: How datanest scopes every query to the calling user through ACL tables, and why jesoos and aivox deliberately skip it.
tags: [rls, acl, security, superuser, datanest, permissions]
audience: [developer]
---

# Row-level security

Row-level security scopes every datanest read and write to the rows the caller has been granted, which
is what makes Mixdeck and 42next show a user only their own data. It is a **datanest concern only**:
jesoos and aivox run as the trusted `SuperUser`, skip the ACL join for performance, and must not have
this ported into them.

# The mechanism

Every entity table `X` has a companion ACL table, named by `entityData.getRlsName()` — conventionally
`X_readers`:

| Column | Meaning |
|---|---|
| `reader` | the user id allowed access |
| `entity_id` | foreign key to `X.id` |
| `can_edit` | may update |
| `can_delete` | may delete |
| `reading_time` | last-read bookkeeping |

The implementation lives in 2next core: `AsyncRepository`, `RLSRepository` and `RlsActionUtil`.
datanest repositories extend `AsyncRepository` and pass an `IUser` on every call.

# Reads

Every read is a join, and there is no unscoped read path — `count`, `getById` and every list are
scoped alike:

```sql
SELECT t.* FROM X t JOIN X_readers rls ON t.id = rls.entity_id
WHERE rls.reader = <user.id>
```

No ACL row means the row does not exist as far as that caller is concerned.

# Writes

Creation is one transaction: insert the entity, then `insertRLSPermissions` grants the creator a reader
row with edit and delete, then `applyRlsActions` applies any additional shares. Updates require
`can_edit` and deletes require `can_delete` on the caller's row, otherwise they are rejected.

# SuperUser

`SuperUser` (id `1`) always has access. The `RlsActionUtil` helpers call `ensureSuperUserAccess`, which
grants a reader row to every user flagged `i_su = true`. Public endpoints with no real caller should
pass `SuperUser.build()` as the `IUser`.

# Rules

Always pass a real `IUser` and never bypass the ACL. A new CRUD entity needs its ACL table, scoped
queries and grant-on-insert — follow `AiAgentRepository` or `SceneRepository`. Repositories are private
to their service and are never called across features.
