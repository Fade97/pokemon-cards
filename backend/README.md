# Backend

Cloudflare Worker with D1 and R2 ([ADR 0005](../docs/adr/0005-cloudflare-backend.md)). It serves current card
prices to the app, runs the nightly price import and stores the language packs. Licensed under
[AGPLv3](LICENSE).

## Status

The price API and the nightly import work locally and in tests. Not deployed yet (see below).

## Nightly import

Issue #4, ADR 0004. The cron only queues work; every queue message is one small job with its own CPU, memory and
subrequest budget (#30).

| When (UTC) | Job | What it does |
|---|---|---|
| 04:30 | `nightly` | Applies `mapping_overrides`, queues one `tcgdex-sets` job per language, and the two price jobs 30 min later |
| | `tcgdex-sets` | Lists a language's TCGdex sets and queues the due ones: new, changed card count, or last synced over 30 days ago. At most `TCGDEX_SETS_PER_NIGHT` (40) per language, so the first backfill takes about a week. Non-English languages only add sets English lacks (JP, DE-only). |
| | `tcgdex-set` → `tcgdex-cards` | Splits a set into chunks of 25 cards; each chunk fetches the full card records and stores the card and its Cardmarket/TCGplayer IDs from `variants_detailed[].thirdParty` (the plain standard print, not stamped or special-foil ones). |
| +30 min | `cardmarket` | Streams the ~16 MB `price_guide_6.json` item by item (no full `JSON.parse`) and upserts the rows of mapped products, 1,000 per statement as one JSON parameter. A `0` in the guide counts as no price. |
| +30 min | `tcgcsv-groups` → `tcgcsv-group` | One job per TCGplayer group (EN Pokémon, category 3): fills missing TCGplayer IDs by set code + collector number (name decides ties), then upserts the prices of mapped products. |
| 07:30 | `snapshot` | Writes all mappings and prices as gzipped JSON to `SNAPSHOTS` at `prices/YYYY/MM/DD.json.gz` (format in [`src/import/snapshot.ts`](src/import/snapshot.ts)). |

Mapping priority per market: a row in `mapping_overrides`, then TCGdex's own ID, then a set + number match. A match
stays until TCGdex has an ID of its own. `products_singles_6.json` is not imported: it has no collector numbers, so
it cannot improve the matching, and TCGdex already provides the Cardmarket IDs.

Monitoring: `import_runs` gets one row per top-level job (`nightly`, `tcgdex:<lang>`, `cardmarket`, `tcgcsv`,
`snapshot`). Child jobs are retried 3 times; only their final failure is written there (`tcgcsv-group:<id>` …),
and the message then moves to the dead-letter queue `pokemon-cards-import-dlq`.

```sh
wrangler d1 execute DB --remote --command "SELECT job, status, detail FROM import_runs ORDER BY id DESC LIMIT 20"
```

## Limits

- Every route is rate-limited to 120 requests per minute per client IP (Workers Rate Limiting binding).
- `GET /v1/prices/:id` answers are kept in Cloudflare's edge cache for an hour (Cache API), so repeat lookups don't
  reach D1.
- The import needs the **Workers Paid** plan: parsing the price guide takes ~2 s of CPU, far above the free
  plan's 10 ms.

## API

| Request | Answer |
|---|---|
| `GET /health` | `{ "ok": true }` after a D1 round trip |
| `GET /v1/prices/:tcgdexId` | Prices of one card; `404` if the import doesn't know it. Cached for an hour, at the edge and in the client. |
| `POST /v1/prices` with `{ "ids": [...] }` | `{ "prices": { id: … }, "missing": [ids] }` for up to 100 cards |
| any, over the rate limit | `429` with `Retry-After: 60` |

A card price looks like this. A market is `null` when the card has no product there (TCGplayer covers EN
cards only), and `holo` is `null` when the guide has no foil price.

```json
{
  "id": "sv03.5-025",
  "cardmarket": {
    "productId": 712345, "currency": "EUR", "mixedLanguages": true, "priceDate": "2026-11-16T02:00:00Z",
    "normal": { "low": 0.5, "trend": 1.2, "avg": 1.1, "avg1": 1.0, "avg7": 1.15, "avg30": 1.3 },
    "holo": { "low": 1.5, "trend": 2.4, "avg": 2.2, "avg1": 2.0, "avg7": 2.3, "avg30": 2.5 }
  },
  "tcgplayer": {
    "productId": 501234, "currency": "USD", "priceDate": "2026-11-15T20:00:00Z",
    "variants": { "Normal": { "low": 0.4, "mid": 0.9, "high": 5.0, "market": 0.8, "directLow": null } }
  }
}
```

## Storage

- **D1 `DB`**: schema in [`migrations/`](migrations/). `tcgdex_sets` tracks the TCGdex sync; `cards` and
  `card_mapping` map TCGdex IDs to Cardmarket `idProduct` and TCGplayer `productId`; `mapping_overrides` holds
  manual fixes; `cardmarket_prices` and `tcgplayer_prices` keep only the latest price of mapped products;
  `import_runs` logs the import jobs. Until the first remote deploy, `0001_initial.sql` is edited in place.
- **R2 `SNAPSHOTS`**: one gzipped JSON snapshot of all prices per day, kept from day one for Pro history.
- **R2 `PACKS`**: language packs (model, reference index, card DB, deltas).

## Develop

```sh
pnpm install
pnpm test                    # vitest inside workerd, with local D1 and R2
pnpm run typecheck           # regenerates worker-configuration.d.ts first
pnpm run db:migrate:local
pnpm run dev                 # http://localhost:8787, cron: /__scheduled?cron=30+4+*+*+*
                             # note: the local queue then runs the jobs against the real upstream sources
```

## Deploy (not done yet)

```sh
wrangler d1 create pokemon-cards          # put the database_id into wrangler.jsonc
wrangler r2 bucket create pokemon-cards-packs
wrangler r2 bucket create pokemon-cards-snapshots
wrangler queues create pokemon-cards-import
wrangler queues create pokemon-cards-import-dlq
wrangler d1 migrations apply DB --remote
pnpm run deploy
```

Resource names use the working title and change with the final app name (#15).
