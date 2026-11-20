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
   TCGdex, Cardmarket guide, TCGCSV          current/<set>.json, current/meta.json, snapshots/YYYY/MM/DD.json.gz,
                                             history/<set>/YYYY-MM.json (Pro)
```

## Status

- Importer: runs nightly on Vulcan since 2026-11-17, writes the files to
  `~/.local/share/pokemon-cards-prices/files` and uploads them to R2 `pokemon-cards-prices`.
- Worker: deployed on 2026-11-17 at https://cards-api.fdu.gg (custom domain; the workers.dev address is off).
  It moves to the final app domain after #15.

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
| 4 | publish | Reads the week-ago prices from the local snapshots of 7–10 days back (skipped with a log line when there are none or they fail), then writes `current/<set>.json` per set, the daily snapshot and `current/meta.json` (last) to every sink: the local `files/` directory always, R2 when credentials are set. |
| 5 | history | Rebuilds `history/<set>/YYYY-MM.json` for the current month (and the previous one on the 1st–3rd) from the snapshots in `files/snapshots/`, to the same sinks. See [Price history](#price-history-pro). |

Mapping priority per market: a row in `mapping_overrides`, then TCGdex's own ID, then a set + number match. A match
stays until TCGdex has an ID of its own. `products_singles_6.json` is not imported: it has no collector numbers, so
it cannot improve the matching, and TCGdex already provides the Cardmarket IDs.

File formats are defined in [`src/price-data.ts`](src/price-data.ts) (shared by importer and Worker) and the
snapshot in [`importer/publish.ts`](importer/publish.ts). The database schema is
[`importer/schema.sql`](importer/schema.sql).

### Operate (on Vulcan)

```sh
backend/importer/deploy.sh            # (after `nvm use`) build, install to ~/.local/share/pokemon-cards-prices/app, enable the timer
backend/importer/deploy.sh --run      # the same, then start a run now
systemctl --user list-timers pokemon-cards-prices.timer
journalctl --user -u pokemon-cards-prices.service -n 50
sqlite3 ~/.local/share/pokemon-cards-prices/prices.db \
  "SELECT job, status, detail FROM import_runs ORDER BY id DESC LIMIT 20"
# Rebuild every month's history files from the local snapshots and upload them (first deploy, format change):
~/.local/share/pokemon-cards-prices/app/run-nightly.sh --no-import --no-publish --rebuild-history
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
| `GET /v1/history/<YYYY-MM>?sets=<set>,…` with `Authorization: Bearer <token>` | **Pro.** `{ "month", "sets": { set: <history file> \| null } }` for up to 20 sets; `401` without a token, `403` without Pro, `400` for a bad month or set list. See [Price history](#price-history-pro). |
| `GET /v1/packs/catalog.json` | Language-pack catalog ([`docs/packs.md`](../docs/packs.md)), cached 5 min |
| `GET /v1/packs/<lang>/<file>`, `GET /v1/packs/models/<file>` | A pack or model file from R2 `PACKS`, cached for a year (versioned names, never overwritten). `ETag`/`If-None-Match` → `304`, a single `Range: bytes=…` → `206` (`416` past the end), `HEAD` gives the size. |
| `GET /privacy`, `/privacy/de`, `/privacy/en` | The privacy policy as HTML (see below) |
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
    "variants": { "Normal": { "low": 0.4, "mid": 0.9, "high": 5.0, "market": 0.8, "directLow": null } },
    "weekAgo": { "date": "2026-11-09", "prices": { "Normal": 0.75 } }
  }
}
```

`weekAgo` (#186, free weekly direction) is each printing's headline price (Cardmarket `trend` as `normal` / `holo`,
TCGplayer `market` per sub type) a week before the file was written, read from the daily snapshots of 7 to 10 days
back (`importer/week-ago.ts`): a printing without a price on `date` takes the last one at most 3 days earlier. It is
missing when no such snapshot prices the market, and older apps ignore it. The app compares it with today's price and
shows only the direction to free users; amounts over time stay Pro (history files below, ADR 0010 and 0012).

### Limits

- Every route is rate-limited to 120 requests per minute per client IP (Workers Rate Limiting binding).
- `GET /v1/prices/:id` answers are kept in Cloudflare's edge cache for an hour (Cache API).
- A request reads one R2 file per set it touches; a set file is up to ~120 KB (plus ~50–80 bytes per card for
  `weekAgo`). That keeps a 100-card batch well
  inside the free plan's CPU budget when the cards come from a few sets.

## Price history (Pro)

Issue #17, ADR 0007. The importer derives one file per set and month from the daily snapshots
([`importer/history.ts`](importer/history.ts), format `PriceHistoryFile` in [`src/price-data.ts`](src/price-data.ts)):

```json
{ "version": 1, "set": "sv03.5", "month": "2026-11", "generatedAt": "2026-11-18T04:40:00.000Z",
  "days": ["2026-11-17", "2026-11-18"],
  "cards": { "sv03.5-025": { "cardmarket": { "normal": [120, 125], "holo": [240, null] },
                             "tcgplayer": { "Normal": [80, 82] } } } }
```

- One value per card, market, printing and day: the headline price the app shows (Cardmarket `trend`, TCGplayer
  `market`) in **cents**, aligned with `days`; `null` is no price that day. Series without any price are left out.
- The snapshots stay the source of truth. The history files can be deleted and rebuilt (`--rebuild-history`).
- Size: ~15–25 bytes per card, printing and day. A 200-card set with four priced printings per card is 143 KB a
  month (53 KB gzipped on the wire; measured with synthetic prices); all cards together are ~150–250 MB a year in R2.
- Parsing such a file takes ~7 ms, most of the free plan's 10 ms CPU limit, so the Worker streams the requested files
  into one answer without parsing them and its CPU time doesn't grow with the file size. Set ids are `shardOf` of the
  card id, which the app computes the same way.

**Who is Pro** ([`src/entitlements.ts`](src/entitlements.ts)): until sign-in and billing exist (#16), the Worker
secret `PRO_TEST_TOKENS` lists the SHA-256 hex digests of tester tokens (32–128 URL-safe characters, sent as
`Authorization: Bearer`). Without the secret nobody is Pro. #16 replaces the implementation behind the same
`Entitlements` interface.

```sh
token=$(openssl rand -hex 32)     # give this to the tester (app: cardindex.proTestToken)
printf %s "$token" | sha256sum | cut -d' ' -f1 | pnpm exec wrangler secret put PRO_TEST_TOKENS
```

`wrangler secret put` replaces the whole list, so pass all digests at once (comma-separated). Locally, put
`PRO_TEST_TOKENS=<digest>` into `.dev.vars` for `pnpm run dev`.

**First deploy: backfill before the first Pro request.** The app asks for a past month once (from the 8th of the next
month on) and keeps the answer, also an empty one. Run `run-nightly.sh --no-import --no-publish --rebuild-history`
on Vulcan before setting `PRO_TEST_TOKENS` (or before a tester opens a chart); a device that fetched earlier shows
no history for those months until its app data is cleared.

## Scan contributions

Issue #14, ADR 0008. Opt-in uploads of the rectified card crop for the open dataset. The app shows the consent
text, the backend only accepts versions it knows (`CONSENT_VERSIONS` in [`src/contributions.ts`](src/contributions.ts),
today `cc0-1`).

| Request | Answer |
|---|---|
| `POST /v1/contributions?cardId=<tcgdex id>&lang=<code>&consent=<version>` with the crop as body, `Content-Type: image/jpeg` or `image/webp` | `201 { "id", "withdrawToken" }` |
| The same POST plus `&id=<uuid>` and `Authorization: Bearer <43-char token>` chosen by the app | `201 { "id", "withdrawToken" }` with those values; a retry with the same id and token answers `200` with the same values instead of storing a second copy; `409` if the id belongs to another token. The app uses this form, so a lost response never leaves a copy it can't withdraw. |
| `DELETE /v1/contributions/:id` with `Authorization: Bearer <withdrawToken>` | `200 { "id", "withdrawnAt" }`, the same again on repeat; `404` for an unknown id or a wrong token, `401` without a token |

- **Validation**: body at most 256 KiB (`413`; the app's 252 × 352 JPEG crops are 20–60 KB); declared type and
  magic bytes must agree (`415`); 64–2048 px per side, card-shaped (short/long side 0.66–0.78, a card is 63:88,
  either orientation) and a well-formed container (`422`); `cardId`, `lang` and `consent` checked (`400`). Animated
  WebP is refused.
- **No metadata**: the file is walked, not re-encoded. JPEG: every APPn segment (EXIF, XMP, JFIF, ICC, …) except
  APP14 in its fixed 12-byte Adobe form, comments and bytes after the end marker are dropped. WebP: `ICCP`, `EXIF`, `XMP ` and
  unknown chunks are dropped and the VP8X flags cleared. Metadata segments between progressive scans → `422`.
- **No client data**: D1 `contributions` holds the id, the SHA-256 of the withdraw token, card id, language,
  consent version, R2 key, type, size, dimensions, SHA-256 of the image, `created_at`, `withdrawn_at` and the
  review state (`review`, `reviewed_at`). No IP
  address, user agent or device data. The client IP is only the in-memory rate-limit key.
- **Order of writes**: an upload first claims its id in D1, then writes the image, then checks again; so of two
  uploads with one id only one writes, and a withdrawal always wins. A withdrawal for an id the server doesn't know
  yet (with a 43-char client token) leaves a **tombstone**, a withdrawn row without card data: an upload still on its
  way then finds the id taken and stores nothing. The answer stays `404`, and tombstones count against the upload
  rate limit.
- **Stored or only claimed**: `stored_at` is set once the image is in R2. A retry of an upload still being written
  gets `503` with `Retry-After: 30` (the app retries), not a false success; a claim stuck for over 2 minutes is
  taken over by a retry with the same token.
- **Withdrawal** sets `withdrawn_at` first and then deletes the image, so an upload still writing can't leave it
  behind; a repeated withdrawal deletes again. Dataset releases (#20) take only
  `WHERE withdrawn_at IS NULL AND stored_at IS NOT NULL AND review = 'approved'`; a scan already in a published release stays there, later releases drop it.
- **Rate limit**: 10 uploads per minute per client IP (`CONTRIBUTION_RATE_LIMIT`), on top of the API limit.
- **Daily quota** (#135): at most `CONTRIBUTION_DAILY_QUOTA` uploads per UTC day for all clients together (a var in
  `wrangler.jsonc`, today `500`; unset or invalid means the default in `src/contributions.ts`, `0` pauses uploads).
  Over it, an upload gets `503` with `Retry-After` until the next UTC midnight; the app ends its run and tries
  later. Withdrawn uploads count for their day, tombstones don't. The claim checks the quota in the same statement,
  so concurrent uploads can't pass it. Retries of an already stored upload are still answered.
- **Quarantine** (#135): every upload is stored with `review = 'pending'`. A dataset release takes only rows an
  admin approved (below); `rejected` deletes the image and keeps the row, so it is never released.

### Reviewing contributions

Admin-only routes, in [`src/contribution-review.ts`](src/contribution-review.ts). The Worker secret
`CONTRIBUTION_ADMIN_TOKENS` lists the SHA-256 hex digests of the admin tokens (32–128 URL-safe characters, sent as
`Authorization: Bearer`). Without the secret, and for a missing or wrong token, every admin path answers `404`.

| Request | Answer |
|---|---|
| `GET /v1/admin/contributions[?review=pending\|approved\|rejected][&limit=1-200][&offset=n]` | `200 { "contributions": [{ "id", "cardId", "lang", "contentType", "size", "width", "height", "createdAt", "review", "reviewedAt" }] }`, stored and not withdrawn rows, oldest first; `review` defaults to `pending` |
| `GET /v1/admin/contributions/:id/image` | the stored crop (`404` once rejected: the image is gone) |
| `POST /v1/admin/contributions/:id/approve` | `200 { "id", "review": "approved", "reviewedAt" }`, the same on repeat |
| `POST /v1/admin/contributions/:id/reject` | `200 { "id", "review": "rejected", "reviewedAt" }`, image deleted; also for an approved scan (later releases drop it) |

Approving a rejected scan, or deciding on a withdrawn or unfinished upload, is a `409`. Set up a token and review:

```sh
token=$(openssl rand -base64 48 | tr '+/' '-_' | tr -d '=\n' | cut -c1-43)   # keep it in Vault, not here
printf %s "$token" | sha256sum | cut -d' ' -f1 | pnpm exec wrangler secret put CONTRIBUTION_ADMIN_TOKENS
api=https://cards-api.fdu.gg/v1/admin/contributions
curl -s -H "Authorization: Bearer $token" "$api?limit=50"                       # pending, oldest first
curl -s -H "Authorization: Bearer $token" -o scan.jpg "$api/<id>/image"        # look at it
curl -s -X POST -H "Authorization: Bearer $token" "$api/<id>/approve"           # or …/reject
```

## Privacy policy

Issue #70, for the Play listing (#15). `GET /privacy` answers in German or English by `Accept-Language` (English
when neither is asked for, `Vary: Accept-Language`); `/privacy/de` and `/privacy/en` are fixed. The texts are in
[`src/privacy-de.ts`](src/privacy-de.ts) and [`src/privacy-en.ts`](src/privacy-en.ts), the page frame and the
controller's details in [`src/privacy.ts`](src/privacy.ts). The data inventory behind the text is in issue #70.

- Before the first deploy, fill in `CONTROLLER` (name, postal address, email) in `src/privacy.ts`; the pages show
  the bracketed placeholders until then.
- Change both languages together and set `UPDATED` to the day of the change.
- Anything that changes what the app or the Worker does with data (a new SDK, logging, accounts for Pro, a new
  upload) needs a matching change here and in the Play Data safety form.
- The app links to `<API_BASE_URL>/privacy` (`BuildConfig.PRIVACY_POLICY_URL`) from Settings → About and from the
  contribution consent screen. The Play listing uses `https://cards-api.fdu.gg/privacy` until the final domain exists.

## Storage

- **D1 `DB`**: only `contributions` (schema in [`migrations/`](migrations/)); prices live in R2.
- **R2 `PRICES`** (`pokemon-cards-prices`): `current/` price files, `snapshots/` and `history/` (Pro), written by the importer.
- **R2 `PACKS`** (`pokemon-cards-packs`): language packs (model, reference index, card DB, deltas), written by
  [`ml/build_packs.py`](../ml/README.md#language-packs) with `wrangler r2 object put`, never by the Worker.

## Develop

Node 26 (`backend/.nvmrc`: `nvm use`); the Worker itself runs on workerd.

```sh
pnpm install
pnpm test                    # Worker tests in workerd (local R2), then importer tests on Node (in-memory SQLite)
pnpm run typecheck           # regenerates worker-configuration.d.ts first
pnpm run dev                 # Worker on http://localhost:8787
pnpm run import --data /tmp/prices --langs en --sets-per-night 3   # a small real import, local files only
```

## Deploy

Worker (done on 2026-11-17; repeat `pnpm run deploy` after changes):

```sh
wrangler r2 bucket create pokemon-cards-packs
wrangler r2 bucket create pokemon-cards-prices
wrangler r2 bucket create pokemon-cards-contributions
wrangler d1 create pokemon-cards          # put the database_id into wrangler.jsonc
wrangler d1 migrations apply DB --remote
pnpm run deploy
```

Importer uploads use an R2 API token limited to `pokemon-cards-prices` (read + write on its objects), stored in
Vault at `secret/services/pokemon-cards` (keys above, plus `CLOUDFLARE_R2_TOKEN_ID` to find the token again); the
`vulcan-apps` policy can read that path.

Resource names use the working title and change with the final app name (#15).
