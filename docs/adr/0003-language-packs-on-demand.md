# 0003. Language packs downloaded on demand

Status: accepted (2026-11-15)

## Context

Indexes for all languages would bloat the APK, and new sets would each need an app release.

## Decision

Packs with card DB + index + model files are served from Cloudflare R2, with delta updates for new sets. v1 covers EU + JP; KO/ZH come later. JP reference images come from a one-time crawl of the official JP database (check its terms first) plus opt-in user scans.

## Alternatives considered

Everything in the APK; Play Asset Delivery.
