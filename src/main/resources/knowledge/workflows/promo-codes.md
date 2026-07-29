---
type: Workflow
title: Promo codes
description: Time-boxed Pro grants issued without payment — redemption rules, expiry sweeping and the admin generation endpoints.
tags: [promo, code, redemption, grant, expiry, admin]
audience: [owner, developer]
---

# Redeeming

A promo code grants Pro for a set number of days with no payment involved. Redemption fails with a bad
request when the code is invalid, expired, exhausted or already redeemed by that user, and with a
conflict when the user already has an active subscription — a code cannot top up or extend what is
already running.

A successful redemption writes an active subscription row whose type comes from the code's plan, whose
period ends at `now() + duration_days`, whose entitlements are copied from that plan's defaults, and
whose Stripe ids are null. The null Stripe subscription id is what marks the row as promotional.

# Expiry

`PromoCodeExpiryJob` runs `@Scheduled(every = "1h")` and flips rows that have no Stripe subscription id
and are past their period end to `subscription_status = 'expired'` with `active = false`. Access
therefore ends within an hour of the nominal expiry rather than exactly on it.

# Generating codes

The admin surface (`PromoCodeController`, role `admitp`, used by 42next) lists codes with
`GET /nivaro/promo-codes`, generates them with `POST /nivaro/promo-codes/generate` taking a plan
identifier, duration in days, quantity, maximum redemptions and an expiry, and soft-deactivates one
with `DELETE /nivaro/promo-codes/:id`.

Codes are formatted `XXXX-XXXX` and avoid the ambiguous characters 0, O, 1 and I. At most 500 can be
generated per request. Deactivating a code does **not** revoke subscriptions already granted with it.

The tables `mixpla__promo_codes` and `mixpla__promo_code_redemptions` are nivaro-local and are not part
of the shared 2next model.
