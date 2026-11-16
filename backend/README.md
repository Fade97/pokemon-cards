# Backend

Cloudflare Worker with D1 and R2 ([ADR 0005](../docs/adr/0005-cloudflare-backend.md)). It serves current card
prices to the app, runs the nightly price import and stores the language packs. Licensed under
[AGPLv3](LICENSE).

## Status

Scaffold. The price API reads from D1, but nothing fills D1 yet: the nightly cron only records a `skipped`
run in `import_runs`. Next steps:

- #4 nightly import: Cardmarket price guide + TCGCSV, mapping to TCGdex IDs, daily snapshot to R2
- #5 language packs in the `PACKS` bucket
- Deploy: create the real resources (see below)

## API

| Request | Answer |
|---|---|
| `GET /health` | `{ "ok": true }` after a D1 round trip |
| `GET /v1/prices/:tcgdexId` | Prices of one card; `404` if the import doesn't know it. Cached for an hour. |
| `POST /v1/prices` with `{ "ids": [...] }` | `{ "prices": { id: … }, "missing": [ids] }` for up to 100 cards |

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

- **D1 `DB`**: schema in [`migrations/`](migrations/). `cards` and `card_mapping` map TCGdex IDs to Cardmarket
  `idProduct` and TCGplayer `productId`; `mapping_overrides` holds manual fixes; `cardmarket_prices` and
  `tcgplayer_prices` keep only the latest price; `import_runs` logs each nightly run.
- **R2 `SNAPSHOTS`**: one gzipped JSON snapshot of all prices per day, kept from day one for Pro history.
- **R2 `PACKS`**: language packs (model, reference index, card DB, deltas).

## Develop

```sh
pnpm install
pnpm test                    # vitest inside workerd, with local D1 and R2
pnpm run typecheck           # regenerates worker-configuration.d.ts first
pnpm run db:migrate:local
pnpm run dev                 # http://localhost:8787, cron: /__scheduled?cron=30+4+*+*+*
```

## Deploy (not done yet)

```sh
wrangler d1 create pokemon-cards          # put the database_id into wrangler.jsonc
wrangler r2 bucket create pokemon-cards-packs
wrangler r2 bucket create pokemon-cards-snapshots
wrangler d1 migrations apply DB --remote
pnpm run deploy
```

Resource names use the working title and change with the final app name (#15).
