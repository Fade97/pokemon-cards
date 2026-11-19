# Language packs

The app downloads card data and the recognition index per language instead of shipping them in the APK
([ADR 0003](adr/0003-language-packs-on-demand.md), issue #5). This document is the contract between the builder
([`ml/build_packs.py`](../ml/build_packs.py), pure parts in [`ml/packs.py`](../ml/packs.py)), the backend
([`backend/src/packs.ts`](../backend/src/packs.ts)) and the app.

Format version: **1**.

## Overview

```
R2 bucket PACKS                         served as
  catalog.json                          GET /v1/packs/catalog.json        mutable, cached 5 min
  models/<modelId>.tflite               GET /v1/packs/models/<file>       immutable
  models/corners-<sha12>.tflite
  <lang>/cards-<v>.json.gz              GET /v1/packs/<lang>/<file>       immutable
  <lang>/index-<v>.bin
  <lang>/delta-<v-1>-<v>.json.gz
  <lang>/delta-<v-1>-<v>.bin
```

- `<lang>` is the TCGdex language code: `en de fr it es pt nl pl ja` in v1. TCGdex has only a few NL/PL sets
  (3 and 2 on 2026-11-17), and only about 56 % of the JP cards have an image, so those packs index fewer cards.
- `fetch` fills in images the TCGdex API leaves out although the file is on its CDN (tcgdex/cards-database#2362,
  #2477): for every card without `image` it sends one `HEAD` to `<cdn>/<lang>/<serie>/<set>/<localId>/high.webp`
  and sets `image` when the answer is 2xx (403/404/410 mean missing). Answers are cached in `<work>/tcgdex/<lang>.cdn-gap.json` for
  `--max-age-days`. On 2026-11-17 this added 3,477 JP, 449 DE and 86 EN cards (JP 3,882 → 7,359). `ja/M1S` is
  excluded: its files show other cards from 057 on (#2395). `--no-cdn-gap` turns it off.
- `embed` takes the reference image of a card TCGdex has no image of from another source where one is known
  (`packs.REF_FALLBACK_SETS`): set `mee` (Mega Evolution Energy, #60) comes from the Limitless TCG image CDN
  (`limitlesstcg.nyc3.cdn.digitaloceanspaces.com/tpci/MEE/MEE_<NNN>_R_<LANG>_LG.png`, 460×640, en de fr it es pt, no
  JP; images: Limitless TCG, from the malie.io export of Pokémon TCG Live). `limitlesstcg.com/robots.txt` allows all
  agents; malie.io's own CDN disallows crawling and is not used (checked 2026-11-18). Such a card is indexed
  but keeps `image: null`, so the app shows a placeholder and never loads the other source (ADR 0009).
- `<v>` is the pack version, an integer per language that goes up by one with every publish.
- One model serves all languages. Its id is the embedder's file stem plus the first 12 hex digits of the SHA-256
  of the embedder and the corner detector together, e.g. `mnv3_256_real_w8-1a2b3c4d5e6f`. A new corner detector
  alone is a new model: unchanged packs only point to it (same version, same files, same `modelSha256`). The app
  treats any new model id as a model change and downloads the model and the full packs again (#45); that costs
  bandwidth once, not correctness.

### Immutability

Every file except `catalog.json` has its version or hash in the name and is **never overwritten**. The Worker
serves these with `Cache-Control: public, max-age=31536000, immutable`, so a reused key would hand out stale bytes
for a year. The builder only ever adds keys. `catalog.json` is uploaded **last**, so it never points at files that
are not uploaded yet. Old versions stay in R2: an app halfway through a download can finish it.

## catalog.json

```json
{
  "formatVersion": 1,
  "generatedAt": "2026-11-17T03:00:00Z",
  "models": {
    "mnv3_256_real_w8-1a2b3c4d5e6f": {
      "embedder": { "path": "models/mnv3_256_real_w8-1a2b3c4d5e6f.tflite", "size": 1843200, "sha256": "…" },
      "corners":  { "path": "models/corners-9f8e7d6c5b4a.tflite", "size": 3500000, "sha256": "…" },
      "dim": 256,
      "inputSize": 224
    }
  },
  "packs": {
    "en": {
      "version": 3,
      "builtAt": "2026-11-17T03:00:00Z",
      "model": "mnv3_256_real_w8-1a2b3c4d5e6f",
      "dim": 256,
      "cards": 22104,
      "modelSha256": "…",
      "indexSha256": "…",
      "keysSha256": "…",
      "metadataCards": 23901,
      "sets": 221,
      "files": {
        "cards": { "path": "en/cards-3.json.gz", "size": 1200000, "sha256": "…" },
        "index": { "path": "en/index-3.bin", "size": 22634496, "sha256": "…" }
      },
      "size": 23834496,
      "deltas": [
        {
          "from": 2, "to": 3,
          "files": {
            "cards": { "path": "en/delta-2-3.json.gz", "size": 9000, "sha256": "…" },
            "index": { "path": "en/delta-2-3.bin", "size": 196608, "sha256": "…" }
          },
          "size": 205608,
          "result": { "indexSha256": "…", "keysSha256": "…", "cards": 22104, "metadataCards": 23901 }
        }
      ]
    }
  }
}
```

| Field | Meaning |
|---|---|
| `formatVersion` | The app refuses a catalog with a version it does not know and keeps its installed packs. |
| `models.<id>` | The shared model: embedder file, optional learned corner detector, embedding size and input size. |
| `packs.<lang>.model`, `dim`, `cards`, `modelSha256`, `indexSha256` | Same names and meaning as today's `meta.json` from [`ml/app_assets.py`](../ml/app_assets.py): `cards` is the number of index rows, the two hashes are SHA-256 of the model file and of `index.bin`. |
| `keysSha256` | SHA-256 of the indexed card ids in row order, joined with `\n` (UTF-8, no trailing newline). |
| `metadataCards`, `sets` | Cards and sets in the card file, including cards without an image (and thus without a row). |
| `files.<role>` | `path` is the key below `/v1/packs/`; `size` and `sha256` are of the bytes as served. |
| `size` | Sum of the full pack's files, for the download dialog. The model is listed once, under `models`. |
| `deltas` | The last few delta links (at most 6), oldest first. Reset to `[]` when the model changes. |

A pack built with `--no-embeddings` has `model`, `modelSha256` and `indexSha256` set to `null`, no `index` file
and `cards: 0`. Such packs are for tests only; the builder refuses to upload them.

## Pack files

### `cards-<v>.json.gz`

Gzip (deterministic: mtime 0) of compact, key-sorted UTF-8 JSON:

```json
{
  "formatVersion": 1, "lang": "de", "version": 3,
  "sets": [
    { "id": "sv03.5", "name": "151", "serieId": "sv", "serieName": "Karmesin & Purpur",
      "releaseDate": "2023-09-22", "official": 165, "total": 207, "abbreviation": "MEW",
      "logo": "https://assets.tcgdex.net/de/sv/sv03.5/logo", "symbol": "https://assets.tcgdex.net/univ/sv/sv03.5/symbol" }
  ],
  "cards": [
    { "id": "sv03.5-001", "setId": "sv03.5", "localId": "001", "name": "Bisasam",
      "image": "https://assets.tcgdex.net/de/sv/sv03.5/001", "printings": ["normal", "reverse"], "indexed": true }
  ]
}
```

- `sets` are ordered by release date, then id. `releaseDate`, `official`, `total`, `abbreviation`, `logo`,
  `symbol`, `serieId` and `serieName` can be `null`.
- `cards` are in **canonical order**: sorted by `id`, comparing strings code unit by code unit (ids are ASCII).
- `id` is the TCGdex card id, the key prices and collections use. `localId` is the collector number as printed.
- `image` is TCGdex's base URL; append `/high.webp` or `/low.webp`. `null` when TCGdex has no image. Images are
  loaded from the CDN and never stored in a pack (ADR 0009).
- `printings` (#143) lists how the card was printed, from TCGdex `variants`: the flags that are true, in the order
  `normal`, `reverse`, `holo`, `firstEdition`, `wPromo`. The first three are finishes, `firstEdition` is a print
  run of one of them (Base Set Charizard: `["holo", "firstEdition"]`), `wPromo` the "W" stamped promo. `null` means
  **unknown** (no answer from TCGdex, or no flag set), never "normal only". Packs built before this field have no
  key; readers treat a missing key as `null`. An app ignores keys it does not know, inside the list too.
  `fetch` reads them for a whole language in one request from TCGdex's GraphQL endpoint (`cards @locale(lang:)
  { id variants }`; the REST set answer has none) and caches the answer in `<work>/tcgdex/<lang>.variants.json`
  for `--max-age-days`.
  - What the app does with them: a scanned card with exactly one finish gets that finish instead of `normal`
    (`CopyDefaults.finishFor`); several or unknown keep the default. The finish stays editable.
  - Quality (checked 2026-11-19): TCGdex answers for every card of the seven published languages, and the flags of
    a card id are the same in every language. Many sets carry only TCGdex's set default: XY, Sun & Moon, BW8/BW9 and
    the promos list every card as `["normal"]`, also ex/GX and reverse holos. That is harmless here (normal is the
    default anyway) but means "normal only" is not to be trusted. Holo-only is reliable where checked (ex/V/VMAX,
    WotC holo rares, Shiny Vault, Trainer/Galarian Gallery). Stamped reverse holos from battle decks are not listed
    as printings (#108, tcgdex/cards-database#2602).
- `indexed` is true when the card has a row in `index.bin`.

### `index-<v>.bin`

`cards` × `dim` float32 little-endian, L2-normalized, no header. Row *i* belongs to the *i*-th card with
`indexed: true` in canonical order. Same layout as today's `index.bin`. The embeddings are computed with the
model the catalog names, by the same code path as `app_assets.py`.

### Deltas: `delta-<from>-<to>.json.gz` and `.bin`

A delta turns version `from` into version `to` of the same language and model. New sets are the main case, but a
delta also carries corrected names, new or removed cards and changed images.

```json
{
  "formatVersion": 1, "lang": "en", "from": 2, "to": 3,
  "upsertSets": [ { "id": "me05", "...": "full set entry" } ],
  "removeSets": [],
  "upsert": [ { "id": "me05-001", "setId": "me05", "localId": "001", "name": "…", "image": "…", "indexed": true } ],
  "remove": [ "sv03.5-999" ]
}
```

`delta-<from>-<to>.bin` holds one row per `upsert` card with `indexed: true`, in `upsert` order. It is absent
when no upserted card is indexed.

#### Applying a delta

1. Start from the installed version `from`: its sets, cards and rows (by card id).
2. Drop the sets in `removeSets`, then insert or replace the sets in `upsertSets`.
3. Drop the cards in `remove` and their rows.
4. For each card in `upsert`, insert or replace it. If `indexed`, take the next row from the `.bin`; otherwise
   drop any row it had.
5. Sort the cards canonically and rebuild `index.bin` in that order.
6. Check the result against the delta's `result`: SHA-256 of the new `index.bin` = `indexSha256`, SHA-256 of the
   indexed ids = `keysSha256`, and the counts. If anything differs, discard it and download the full pack.

The builder applies every delta to its base itself (`packs.apply_delta`) and refuses to publish one that does not
reproduce the full pack exactly.

## The app

### Picking what to download

- No pack installed: full pack (`files.cards`, `files.index`) plus the model if its file is not installed yet.
- Installed version `v` < `version`, same `modelSha256`, and the delta chain `v → … → version` is complete in
  `deltas`: download the chain if its summed `size` is smaller than the full pack, else the full pack.
- Different `modelSha256`: download the new model and the full pack of **every** installed language (the old
  index does not fit the new model). Switch atomically: keep the old model and packs until all new files are verified.
- An installed pack whose `pack.json` lacks `"printingsKept": true` was stored by an app from before `printings`
  (#143). Such an app rewrites the card file after a delta and drops the key, and no catalog check covers it, so
  the pack is downloaded in full once, even at the catalog's version. Deltas alone would never bring back the
  printings of cards that did not change.

### Checks

- **After every download**: size and SHA-256 must match the catalog entry. On mismatch the file is deleted and
  downloaded again (at most twice, then the user sees an error). A partial file is resumed with `Range:
  bytes=<have>-` and `If-Range` is not needed: the file under that key never changes.
- **After applying a delta**: the `result` checks above.
- **On load** (`RecognitionEngine.load`): the stored pack entry is the pack's `meta.json`. The engine checks that
  SHA-256 of the model file = `modelSha256`, SHA-256 of `index.bin` = `indexSha256`, and the file size =
  `cards × dim × 4`. On mismatch the pack is marked broken, recognition for that language is disabled and the
  app offers a fresh download. Hashing ~23 MB should take well under a second (not measured on a phone yet); the result may be cached together with the
  file's size and modification time and checked again when either changes.
- The catalog itself comes over HTTPS from the API. Signing it is left for later (issue to be opened when packs
  are public).

### Catalog polling

`GET /v1/packs/catalog.json` with `If-None-Match` once a day and when the user opens the language settings. A
`304` costs almost nothing. The catalog is cached for 5 minutes at most, so a publish reaches apps within minutes.

## Building and publishing

See [`ml/README.md`](../ml/README.md#language-packs). In short: `fetch` (TCGdex metadata, any machine), `embed`
(on the machine with `CARDINDEX_DATA` and the model), `build` against the published catalog, then `--upload`.
