---
type: Service
title: datanest
description: CRUD backend for the Mixdeck and 42next SPAs, where Row-Level Security keeps every query user-scoped.
tags: [service, datanest, crud, rls, security]
audience: [developer]
---

# datanest

CRUD backend service. It serves the Mixdeck (owner/user) and 42next (admin) SPAs.

Row-Level Security is a datanest concern: every query is user-scoped. The backend services jesoos
and aivox run as a trusted system user instead and skip RLS for performance.
