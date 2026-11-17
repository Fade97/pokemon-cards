# Backend

Two parts ([ADR 0005](../docs/adr/0005-cloudflare-backend.md), [ADR 0004](../docs/adr/0004-price-sources.md)):

- **Importer** (`importer/`, Node on Vulcan): the nightly price import. It maps TCGdex cards to Cardmarket and
  TCGplayer products, reads both price sources, and publishes one price file per set to R2.
- **Worker** (`src/`, Cloudflare): the API the app calls. It only reads those files from R2, so it fits the free
  Workers plan; it also serves the language packs.

Licensed under [AGPLv3](LICENSE).

```
Vulcan, 05:30 Europe/Berlin (systemd timer)                 Cloudflare
importer ─► prices.db (SQLite) ─► publish ─► R2 pokemon-cards-prices ◄── Worker ◄── app
   TCGdex, Cardmarket guide, TCGCSV          current/<set>.json, current/meta.json, snapshots/YYYY/MM/DD.json.gz
```

## Status

- Importer: runs nightly on Vulcan since 2026-11-17 and writes the files to
  `~/.local/share/pokemon-cards-prices/files`. Uploads to R2 start once the bucket and credentials exist
  (see [Deploy](#deploy)).
- Worker: works locally and in tests, not deployed yet.

## Importer

Issue #4. One run is a series of small jobs; a job that fails is retried 3 times, recorded in `import_runs` and
skipped, so one broken set or group never costs the whole night.

| Order | Job | What it does |
|---|---|---|
| 1 | `nightly` | Applies `mapping_overrides`, queues one `tcgdex-sets` job per language, then the two price jobs |
| 2 | `tcgdex-sets` | Lists a language's TCGdex sets and queues the due ones: new, changed card count, or last synced over 30 days ago. At most `--sets-per-night` (100) per language. Non-English languages only add sets English lacks (JP, DE-only). |
| 2 | `tcgdex-set` → `tcgdex-cards` | Splits a set into chunks of 25 cards; each chunk fetches the full card records and stores the card and its Cardmarket/TCGplayer IDs from `variants_detailed[].thirdParty` (the plain standard print, not stamped or special-foil ones). |
| 3 | `cardmarket` | Streams the ~16 MB `price_guide_6.json` item by item and upserts the rows of mapped products. A `0` in the guide counts as no price. |
| 3 | `tcgcsv-groups` → `tcgcsv-group` | One job per TCGplayer group (EN Pokémon, category 3): fills missing TCGplayer IDs by set code + collector number (the name decides ties), then upserts the prices of mapped products. |
| 4 | publish | Writes `current/<set>.json` per set, the daily snapshot and `current/meta.json` (last) to every sink: the local `files/` directory always, R2 when credentials are set. |

Mapping priority per market: a row in `mapping_overrides`, then TCGdex's own ID, then a set + number match. A match
stays until TCGdex has an ID of its own. `products_singles_6.json` is not imported: it has no collector numbers, so
it cannot improve the matching, and TCGdex already provides the Cardmarket IDs.

File formats are defined in [`src/price-data.ts`](src/price-data.ts) (shared by importer and Worker) and the
snapshot in [`importer/publish.ts`](importer/publish.ts). The database schema is
[`importer/schema.sql`](importer/schema.sql).

### Operate (on Vulcan)

```sh
backend/importer/deploy.sh            # build, install to ~/.local/share/pokemon-cards-prices/app, enable the timer
backend/importer/deploy.sh --run      # the same, then start a run now
systemctl --user list-timers pokemon-cards-prices.timer
journalctl --user -u pokemon-cards-prices.service -n 50
sqlite3 ~/.local/share/pokemon-cards-prices/prices.db \
  "SELECT job, status, detail FROM import_runs ORDER BY id DESC LIMIT 20"
# A manual mapping fix, applied at the next run:
sqlite3 ~/.local/share/pokemon-cards-prices/prices.db \
  "INSERT INTO mapping_overrides VALUES ('sv03.5-025', 733620, NULL, 'why', datetime('now'))"
```

The unit exits with 1 when a top-level job (`nightly`, `tcgdex:<lang>`, `cardmarket`, `tcgcsv`) or the publish
failed, so systemd marks it failed. R2 credentials are read from Vault (`secret/services/pokemon-cards`:
`R2_ENDPOINT`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET`) with the `vulcan-apps` token at run time
and never written to disk.

## Worker API

| Request | Answer |
|---|---|
| `GET /health` | `{ "ok": true, "pricesGeneratedAt", "cardmarketPriceDate", "tcgplayerPriceDate" }`; `503` with `ok: false` when the files or either source's prices are older than 72 h, or missing |
| `GET /v1/prices/:tcgdexId` | Prices of one card; `404` if the importer doesn't know it. Cached for an hour, at the edge and in the client. |
| `POST /v1/prices` with `{ "ids": [...] }` | `{ "prices": { id: … }, "missing": [ids] }` for up to 100 cards |
| `GET /v1/packs/catalog.json` | Language-pack catalog ([`docs/packs.md`](../docs/packs.md)), cached 5 min |
| `GET /v1/packs/<lang>/<file>`, `GET /v1/packs/models/<file>` | A pack or model file from R2 `PACKS`, cached for a year (versioned names, never overwritten). `ETag`/`If-None-Match` → `304`, a single `Range: bytes=…` → `206` (`416` past the end), `HEAD` gives the size. |
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

### Limits

- Every route is rate-limited to 120 requests per minute per client IP (Workers Rate Limiting binding).
- `GET /v1/prices/:id` answers are kept in Cloudflare's edge cache for an hour (Cache API).
- A request reads one R2 file per set it touches; a set file is up to ~120 KB. That keeps a 100-card batch well
  inside the free plan's CPU budget when the cards come from a few sets.

## Storage

- **R2 `PRICES`** (`pokemon-cards-prices`): `current/` price files and `snapshots/`, written by the importer.
- **R2 `PACKS`** (`pokemon-cards-packs`): language packs (model, reference index, card DB, deltas), written by
  [`ml/build_packs.py`](../ml/README.md#language-packs) with `wrangler r2 object put`, never by the Worker.

## Develop

```sh
pnpm install
pnpm test                    # Worker tests in workerd (local R2), then importer tests on Node (in-memory SQLite)
pnpm run typecheck           # regenerates worker-configuration.d.ts first
pnpm run dev                 # Worker on http://localhost:8787
pnpm run import --data /tmp/prices --langs en --sets-per-night 3   # a small real import, local files only
```

## Deploy

Worker (not done yet):

```sh
wrangler r2 bucket create pokemon-cards-packs
wrangler r2 bucket create pokemon-cards-prices
pnpm run deploy
```

Importer uploads: create an R2 API token with write access to `pokemon-cards-prices`, store it in Vault at
`secret/services/pokemon-cards` (keys above; `R2_ENDPOINT` is `https://<account id>.r2.cloudflarestorage.com`),
and add `secret/data/services/pokemon-cards` to the `vulcan-apps` policy. The next run then uploads.

Resource names use the working title and change with the final app name (#15).
