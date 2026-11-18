# 0013. Price alerts checked on the device until sign-in and push

Status: accepted (2026-11-18), for #76. Amends ADR 0007 for price alerts only.

## Context

Pro (ADR 0007) includes price alerts, and ADR 0007 says Pro is enforced server-side. Truly server-side alerts need
the server to hold each user's rules and push a notification after each import, so they need accounts and push
(#16 + FCM), which don't exist yet. The current prices that an alert compares against are free (`POST /v1/prices`).

## Decision

- For v1 the app **checks alerts on the phone**: a daily periodic WorkManager job (network required) from 07:00
  Europe/Berlin, after the 05:30 import, fetches the current prices of every alerted card through `PriceRepository`
  and notifies once per crossing.
- **We accept a soft gate for alerts.** The check runs behind `Entitlements.isPro` like the charts (release builds are
  never Pro until #16), but it only uses free data, so a fork can drop the check. Price history stays enforced by the
  backend (ADR 0012); this exception covers alerts only.
- With #16 alerts **move to the server**: the backend stores the rules per account, evaluates them after each import
  and pushes through FCM. To keep that move small, the rule model, the crossing/re-arm rule, the check and the
  schedule (`data/alerts/PriceAlerts.kt`) are plain Kotlin behind a `PriceAlertStore` and a `CurrentPrices` source,
  with Room, WorkManager and notifications only at the edges. Rules have uuids, so they can be uploaded as they are.

## Consequences

- Alerts work without an account and offline-first, but only as reliably as WorkManager on the phone (Doze can delay
  the run; a 24-hour period drifts by an hour when the clocks change, which the 07:00 margin absorbs).
- When #16 lands, the device check is switched off for signed-in users and their local rules are uploaded once.

## Alternatives considered

Waiting for #16 and doing alerts only server-side (no alerts until accounts and push exist); checking on the device
for good (a permanent soft gate, and the phone has to be online every morning).
