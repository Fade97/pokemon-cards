# 0005. Backend on Cloudflare Workers + D1 + R2

Status: accepted (2026-11-15)

## Context

The backend has to host the nightly import, the price API and the language-pack CDN at near-zero cost and without server maintenance.

## Decision

Workers (API + cron), D1 (current prices, mapping), R2 (packs, snapshots, contributed scans). Pro auth, sync and alerts come later on the same platform.

## Alternatives considered

Homelab behind a tunnel; Supabase/VPS.
