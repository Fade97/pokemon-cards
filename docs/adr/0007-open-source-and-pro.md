# 0007. Open source (GPLv3/AGPLv3) with server-side Pro

Status: accepted (2026-11-15)

## Context

The app should be free and trustworthy, and Pro should still cover running costs.

## Decision

The app is GPLv3 and backend/ is AGPLv3. Pro (sync, price history/charts, alerts) is enforced server-side and sold as a cheap subscription + lifetime option through Play Billing, with sign-in through Google Sign-In (Credential Manager). The repo is public from the start.

## Alternatives considered

Closed source; app open with backend closed; MIT.
