---
type: Workflow
title: Subscriptions
description: Free and Pro plans — what a plan unlocks, how upgrading, changing and cancelling behave, and how entitlements are stored.
tags: [subscription, free, pro, stripe, entitlements, billing, cancel]
audience: [owner, developer]
---

# Free and Pro

There are two plans today. **Free** is not a database row at all — a user is on Free when they have no
active subscription. **Pro** is paid through Stripe, or granted temporarily by a promo code. A user has
at most one active subscription, whether paid or promotional.

Asking for the current subscription while on Free returns a Free placeholder with HTTP 200, not a 404.

# What a plan unlocks

A plan's limits are copied onto the user's subscription row when it activates, so an entitlement is a
snapshot rather than a live lookup:

| Entitlement | Meaning |
|---|---|
| `max_stations` | how many stations the owner may run |
| `max_songs` | catalog size limit |
| `stream_quality_kbps` | audio quality of the stream |
| `stream_duration_minutes` | how long a stream may run; `0` means nonstop |
| `ots_allowed` | may create one-time streams |
| `bulk_upload_allowed` | may bulk-upload to the catalog |
| `custom_script_allowed` | may author custom scripts |
| `codecs`, `dj_type`, `price_eur` | available codecs, DJ tier, price |

Because they are copied on activation, changing a plan's definition does **not** retroactively change
subscriptions already issued.

# Upgrading, changing and cancelling

Upgrading from Free redirects to Stripe Checkout. Changing plan while already subscribed swaps the
price directly on the existing subscription with proration, charging or crediting the stored card. If
the bank requires 3-D Secure the API answers 402 with a client secret, the client confirms with
Stripe.js, and then re-reads the current subscription.

Cancelling is **immediate**. There is no cancel-at-period-end and no grace period: the subscription
ends and the account reverts to Free straight away.

# Promo codes

A promo code grants Pro for a fixed number of days without any payment, with Stripe ids left null. It
cannot be stacked on or used to extend an existing subscription — that is refused with a conflict.
Expiry is swept hourly rather than at the exact minute.

# Implementation notes

The user-facing surface is `PublicController`: `GET /nivaro/subscriptions/products`,
`GET|PATCH|DELETE /nivaro/subscriptions/current` and `POST /nivaro/subscriptions/redeem-promo`. Stripe
calls back on `POST /nivaro/billing/webhook` and `/webhook/subscription` (`StripeController`) — these
paths were renamed from `/nivaro/stripe/webhook*`, so the Stripe dashboard must match.

Entitlement keys live in `_subscription_products.default_values` and are written to columns on
`mixpla__user_subscriptions`. When parsing, a comma-separated list takes its first numeric token and
the word `infinitely` becomes `0`, matching the frontend convention that zero means unlimited. Values
are applied on the checkout-completion webhook and on a direct plan swap.

Free cannot be expressed as a product row because `stripe_price_id` is `NOT NULL UNIQUE`. A placeholder
row is created before Checkout — Stripe customer exists, subscription does not yet — which is why the
subscription type and Stripe id columns had to be made nullable; while they were `NOT NULL`, every
first-time Free-to-Pro checkout failed at the database layer. nivaro does not own DDL: those migrations
live in the `mxpldb` repo and are run separately.

A partial unique index enforces one `active = true` row per user:

```sql
CREATE UNIQUE INDEX uq_user_subscriptions_one_active_per_user
ON mixpla__user_subscriptions (user_id) WHERE active = true;
```

It guards against double activation, such as a webhook processed twice, and not against the
customer-id bug below, which produced distinct rows no constraint would catch.

`findOrCreateStripeCustomerId` runs the whole find-or-create in one transaction under
`pg_advisory_xact_lock(user_id)`, so a double-clicked Subscribe blocks and then sees the row the first
call inserted instead of creating a second Stripe customer and a second pending row. Duplicate pending
rows created before that fix are not cleaned up automatically.

`findStripeCustomerId` must filter out null customer ids and order by last modification. Without that it
could return an old seed row with a blank customer id, conclude there was no Stripe customer and mint a
new one on every Subscribe click.

`changePlan` throws for a Free user, so the controller — not `StripeService` — decides between Checkout
and a swap. Checkout completion resolves the plan by reading the created subscription's price id and
looking up the matching product identifier, rather than activating something generic and leaving the
subscription type null.

Entitlements are only written going forward, on the next checkout or plan change. Rows written before
that behaviour existed keep whatever they had and need a one-off backfill, which is why a Pro row can
still be found carrying Free limits.
