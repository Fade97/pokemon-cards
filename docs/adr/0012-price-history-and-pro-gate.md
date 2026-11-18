# 0012. Price history from the snapshots, Pro gated by test tokens until billing

Status: accepted (2026-11-18), for #17

## Context

Pro (ADR 0007) includes price history and portfolio charts. The nightly import keeps a full daily snapshot from day
one (ADR 0004, 0011), but a snapshot is several MB and the Worker must stay on the free plan. Collections stay on the
device (ADR 0006), so the server can't compute a user's portfolio. Sign-in and billing (#16) don't exist yet.

## Decision

- The importer derives **one history file per set and month** from the snapshots (`history/<set>/YYYY-MM.json`:
  cents per card, market, printing and day). The snapshots stay the source of truth; history files are rebuilt.
- The Worker serves them through `GET /v1/history/<month>?sets=…`, streaming up to 20 files into one answer without
  parsing them. The app caches what it needs per card and month and computes the portfolio on the device: today's
  copies valued with each day's prices.
- Pro is checked behind an **`Entitlements` interface** on both sides. Until #16 the backend accepts bearer tokens whose
  SHA-256 is in the `PRO_TEST_TOKENS` secret (none = nobody is Pro), and only debug builds can switch Pro on. #16
  replaces both implementations.
- Price alerts are a separate issue (#76): checked on the device they'd only be a soft gate, which needs a decision.

## Alternatives considered

Client-side snapshots (history starts at install, gaps when the app isn't opened, lost with the phone); a per-card
API that parses the files (CPU grows with file size and the portfolio needs hundreds of calls); history rows in the
importer's SQLite (millions of rows a year, while the snapshots already hold the data).
