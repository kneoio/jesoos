---
type: Workflow
title: One-time stream (OTS)
description: Temporary event streams accessed by QR or URL, with brand-scoped or owner-scoped catalogs.
tags: [ots, event, stream, qr, catalog]
audience: [owner, developer]
---

# One-time stream (OTS)

Temporary event streams reached by QR code or URL. Catalogs are either brand-scoped or owner-scoped,
and an OTS has a routing identity distinct from continuous brand radio.

Event chat runs as anonymous guest mode on the public chat WebSocket, where the slug acts as the
access token.

The authoritative detail lives in `OTS_WORKFLOW.md` in jesoos, kept coherent with aivox's
`OTS_SCOPE.md` and `RADIO_SCOPE.md`.
