# Open dataset

How a release of the open dataset is built ([ADR 0008](adr/0008-open-models-and-dataset.md), issue #20). The code
is in [`ml/dataset/`](../ml/dataset). It builds a release directory and **never uploads anything**. Publishing is
a manual step (see [Publishing](#publishing)).

## What a release contains

| Part | Content | Licence |
|---|---|---|
| Reference cards (`cards.csv`, `sources.json`) | Card id, language, set, number, name, image URL + fallback URL, source, split, optional SHA-256 of the image | MIT (the data is from TCGdex) |
| Reference images | **Not included.** `download.py` rebuilds them from the URLs (ADR 0009: the card art is not re-hosted) | owners' terms, per source |
| User scans (`scans/`, `scans.csv`) | Contributed card crops (#14) | CC0 1.0 |

Layout of one release directory, written once and never changed:

```
README.md       dataset card (licences, statistics, intended use, biases, citation), from ml/dataset/README.template.md
cards.csv       reference manifest
sources.json    per source: owner, terms, credit, minimum request interval
download.py     stdlib-only downloader (a copy of ml/dataset/download.py)
LICENSES/       CC0 notice for the scans, TCGdex's MIT licence
scans.csv       scan_id, file, card_id, lang, split, consent, month, content_type, width, height, bytes, sha256
scans/          <scan_id>.jpg / .webp
withdrawn.csv   scan ids of earlier releases this one drops
stats.json      numbers behind the README
release.json    name, version, date, schema version, licences
SHA256SUMS      every file above; `sha256sum -c SHA256SUMS`
```

The release builder also writes `<release>.review.html` next to (not into) the release: a contact sheet of all
scans for the manual look before publishing.

### Versioning and checksums

- **Release version**: `YYYY.MM.N` (e.g. `2026.11.0`), one directory per version, never rewritten. `release.json`
  carries a separate `schemaVersion` (1) that changes only when a file's columns change.
- **`SHA256SUMS`** covers every file of the release.
- **Per-image SHA-256** in `cards.csv` (`sha256`, `alt_sha256`): third-party images are not ours, so their hashes
  come from a download run (`downloaded.csv`, passed as `--checksums`). Users then see a `mismatch` when a source has
  changed an image since the release. Without such a run the columns stay empty.

### Splits

- Reference cards: `train` (trained set in `ml/manifest.csv`), `holdout` (unseen set in `ml/manifest.csv`, index and
  evaluation only), `index` (only in a pack's reference index).
- User scans: `common.split_of(card_id)`, the fixed card-level split also used for the real-photo sets (20 %
  `test`). All scans of one card land in the same split.

## Image sources

`ml/dataset/sources.py` lists every host a reference URL may point to, with the owner, terms, credit line and a
minimum request interval. **The export fails for a URL whose host is not listed**, so a new source (like malie.io for
set MEE, #60/#69) needs its terms written down before it can be released. Today:

| Source | Hosts | Interval | Notes |
|---|---|---|---|
| `tcgdex` | `assets.tcgdex.net` | 0.25 s | Base URL + `/high.png`, fallback `/high.webp`. No `robots.txt` (404). |
| `malie` | `cdn.malie.io` | 1 s | Full PNG URLs. **`robots.txt` is `Disallow: /` for every agent** (checked 2026-11-18), so `download.py` skips these cards with status `robots`. |

`download.py` honours `robots.txt` (and `Crawl-delay`), the per-host interval, `Retry-After` and 429/5xx backoff,
sends the generic User-Agent `open-card-dataset-downloader/1.0 (python-urllib)`, makes one request at a time and
resumes partial files with `Range`/`If-Range`.

## User scans: what is released

Input is a **copy** of the backend's D1 table `contributions` and of the R2 objects, never the live storage:

```sh
# read-only on the live side
wrangler d1 export pokemon-cards --remote --table contributions --output contributions.sql
cd ml
python -m dataset.export_scans fetch-commands --rows ../contributions.sql --objects ../scans-r2 > ../fetch.sh
sh ../fetch.sh                     # wrangler r2 object get … for eligible scans only
```

A row is released only if all of these hold:

- `stored_at IS NOT NULL` and `withdrawn_at IS NULL` (backend README). Dumps from before migration 0002 have no
  `stored_at`; such a row counts as stored when it has an object key, as the migration assumes. Tombstones stay out.
- `consent_version` is a CC0 consent (`CC0_CONSENTS` in `export_scans.py`, today `cc0-1`). A new consent text must
  be added there on purpose; an unknown version is never released.
- Card id and language are well-formed, the object key is `scans/<uuid>[.jpg|.webp]`, the object exists and its
  SHA-256 matches the row, the magic bytes match `content_type`, and it is 64–2048 px and at most 1 MiB.
- The image passes the backend's metadata rules again (a Python port of `backend/src/image-meta.ts`): JPEG APPn
  (except the fixed Adobe APP14), comments and trailing bytes, WebP `EXIF`/`XMP `/`ICCP`/unknown chunks are dropped.
  The backend already did this, so normally nothing changes; the count is printed.

What is published per scan, and what is not:

| Published | Not published |
|---|---|
| `scan_id`: first 20 hex digits of SHA-256(`cardindex-scan:<contribution id>`). Stable across releases, so `withdrawn.csv` works; does not expose the backend id. | Contribution id, token hash, R2 key |
| `card_id`, `lang`, `consent`, `split`, size, SHA-256 of the released bytes | Exact upload time: only the **month** |
| | Row order: rows are sorted by `scan_id`, not by upload time, so a burst of uploads from one person cannot be read off |

`withdrawn.csv` lists every scan id of the previous release (and the ids it had already listed) that this release
does not contain, whether withdrawn or failing a check. A scan already in a published release stays in that
release; later ones drop it (ADR 0008).

Before publishing, look through `<release>.review.html`: a crop can still show fingers, a face in a reflection, a
background or a wrong label.

## Building a release

```sh
cd ml
# optional: per-image checksums from one download run (large: all PNGs of the listed cards)
python -m dataset.export_manifest --manifest manifest.csv --pack-cards <packs>/de/cards-<v>.json.gz … --out ../tmp-export
python -I dataset/download.py ../tmp-export/cards.csv --out ../images --prefer alt
# the release
python -m dataset.release --version 2026.11.0 --out ../dataset-release/v2026.11.0 \
    --manifest manifest.csv --pack-cards <packs>/<lang>/cards-<v>.json.gz … \
    --checksums ../images/downloaded.csv \
    --rows ../contributions.sql --objects ../scans-r2 \
    --previous ../dataset-release/<previous version> \
    --name <dataset name> --homepage <url> --doi <doi>
cd ../dataset-release/v2026.11.0 && sha256sum -c SHA256SUMS
```

Tests (CPU, standard library and pytest only): `cd ml && uv run --no-project --with pytest pytest tests/test_dataset.py`.

## Publishing

Not done yet; nothing in this repo uploads a release. Steps once the open decisions on #20 are made:

1. Pick name, host and whether to get a DOI (#20). On Zenodo, reserve the DOI first, so it can go into the README.
2. Build the release as above from the published packs (all languages) and a fresh copy of the contributions.
3. Look through the review sheet; drop a scan by withdrawing it in the backend and rebuilding, not by hand.
4. Check `sha256sum -c SHA256SUMS`, open the README, and make sure the directory holds no reference images.
5. Upload the directory (or a zip of it) to the host, then link it from the repo README and the app's about screen.
6. For every later release, pass `--previous` so `withdrawn.csv` is filled.
