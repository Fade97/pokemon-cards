# 0011. Price import on Vulcan, price files in R2

Status: accepted (2026-11-17)

## Context

The nightly import (ADR 0004) was first built as a Cloudflare cron with queue fan-out into D1. Parsing the
16 MB Cardmarket price guide takes about 2 s of CPU, so that design needs the Workers Paid plan. On the free plan
D1 also caps writes at 100k rows a day, and one night writes about 100k price rows.

## Decision

The import runs on Vulcan (homelab) as a systemd timer: Node with a local SQLite database, the same jobs as
before. After each run it publishes one JSON price file per TCGdex set, a `meta.json` and the daily snapshot to
R2 over the S3 API, and keeps a local copy. The Worker only reads those files, so it stays on the free plan and
needs no D1 for prices.

If Vulcan is down, the app keeps getting the last published prices; `/health` reports them as stale after 72 h, counting the files' age and each source's price date.

## Alternatives considered

Workers Paid with D1 and queues (the first implementation); Vulcan writing changed rows into D1 (close to the
free D1 write limit); serving the whole API from Vulcan behind a tunnel (the app would depend on the homelab).
