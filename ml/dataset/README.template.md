# $name

Version **$version** ($date) · schema $schema · DOI: $doi · Homepage: $homepage

An open dataset for recognising Pokémon Trading Card Game cards from photos, in several languages. It is the data
behind the on-device recogniser of an open-source Android app (GPLv3). It has two parts:

1. **Reference cards** (`cards.csv`): $cards card prints, each with its id, language, set, collector number and
   name, and the URL of its official card image at a third-party source. **The images themselves are not in this
   dataset**: `download.py` fetches them for your own use.
2. **User scans** (`scans/`, `scans.csv`): $scans photos of real cards ($scan_cards distinct prints), cropped
   and perspective-corrected on the phone and contributed by app users who opted in and released them under
   **CC0 1.0**.

This dataset is not affiliated with, endorsed or sponsored by The Pokémon Company, Nintendo, Creatures or GAME
FREAK. Pokémon and the card artwork are their trademarks and copyright.

## Licences

| Part | Licence | Notes |
|---|---|---|
| `scans/`, `scans.csv` | [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) (`LICENSES/CC0-scans.txt`) | Each contributor agreed to the CC0 consent text named in the `consent` column. The cards shown are still the rights holders' artwork: CC0 covers the contributors' photos, not the card designs. |
| `cards.csv`, `sources.json` | MIT (`LICENSES/TCGdex-MIT.txt`) | Card ids, names and set data come from [TCGdex](https://tcgdex.dev) (MIT, © 2021 TCGdex). Our additions (source, split, checksums) are CC0. |
| Reference images | **not included** | Referenced by URL only. Owned by the rights holders above and served by the sources below, each on its own terms. Do not re-host them. |
| `download.py` | GPL-3.0-or-later | |

## Image sources

$source_table

`download.py` keeps to each source's `min_interval_s`, honours `robots.txt` and `Retry-After`, sends a generic
User-Agent and does one request at a time. Read the source's terms before you use the images.

## Statistics

$lang_table

$split_table

- `split` of a reference card: `train` = a set the published model was trained on; `holdout` = a set it never saw
  in training (index and evaluation only); `index` = a card that is only in the model's reference index.
- `split` of a user scan: a fixed card-level split (`train`/`test`, SHA-1 of the card id, 20 % test). Every scan of
  one card is in the same split, so a test card is never seen in training.
- $with_checksum of the reference cards carry the SHA-256 their image had when this release was built.

## Files

| File | Content |
|---|---|
| `cards.csv` | `key` (`<lang>/<card_id>`), `card_id` (TCGdex id), `lang` (TCGdex code), `set_id`, `set_name`, `local_id` (collector number as printed), `name`, `category`, `source`, `image_url`, `alt_url` (fallback, e.g. WebP), `sha256`, `alt_sha256`, `split` |
| `sources.json` | Per source: name, hosts, homepage, terms, image ownership, credit line, minimum request interval |
| `scans.csv` | `scan_id`, `file`, `card_id`, `lang`, `split`, `consent`, `month` (of upload), `content_type`, `width`, `height`, `bytes`, `sha256` |
| `scans/` | The crops, JPEG or WebP, 64–2048 px per side, no metadata |
| `withdrawn.csv` | `scan_id`s that were in an earlier release and are not in this one. Please delete them from your copies. |
| `stats.json`, `release.json` | The numbers above; release name, version and licences |
| `SHA256SUMS` | Checksums of every file: `sha256sum -c SHA256SUMS` |

## Rebuilding the reference images

```sh
python download.py cards.csv --out images --lang de --limit 50     # a sample
python download.py cards.csv --out images --prefer alt             # all, WebP where the source has it (smaller)
```

Python 3.9+, no packages needed. The run can be stopped and restarted at any time. It writes
`images/downloaded.csv` and `images/download-report.json`. A `mismatch` there means the source changed that
image after this release; a `missing` means it is gone.

## Intended use

- Training and evaluating card recognition: retrieval/embedding models, detectors, collector-number reading.
- Measuring how a recogniser does on real photos (user scans) versus clean renders (reference images).

Out of scope: re-hosting or redistributing the card images, producing counterfeit cards, or any use the sources'
terms or the rights holders' rights do not allow.

## How the data was made

- Reference cards come from TCGdex's card database; a card is listed only if it has an image. Sets TCGdex has no
  images for may come from another source (see `source`).
- A user scan is the card crop the app made after detecting the card and correcting its perspective. The user
  confirmed the card id. Before upload the app keeps only the crop; the server removes all metadata (EXIF, XMP, ICC,
  comments) without re-encoding, stores no IP address, account or device data, and the release step checks this
  again. Only the month of upload is published. A contributor can withdraw a scan at any time; it is then left out
  of every later release and listed in `withdrawn.csv`.

## Known limitations and biases

- **Languages and sets are uneven.** TCGdex has far more English cards than other languages; about half of the
  Japanese cards have no image. Korean and Chinese are not covered yet.
- **Reference images are clean digital renders**, not photos: no glare, sleeves, wear, holo shine or perspective.
  A model trained on them alone does worse on real photos.
- **User scans reflect the contributors**: the cards they own (recent sets, popular cards), their phones,
  lighting, sleeves and tables. They exist only where the app's own detector found the card, so cards it misses are
  missing here too.
- **Labels can be wrong.** Scan labels are what the user confirmed. Normal and reverse-holo prints share one id and
  cannot be told apart in a photo. Reference labels follow TCGdex, including its errors.
- **Third-party URLs can change or vanish** after a release; the checksums show when that happened.
- **Some images cannot be rebuilt automatically.** When a source's `robots.txt` forbids automated access,
  `download.py` skips its cards (status `robots`); see `sources.json`.

## Citation

```bibtex
@misc{cardindex_open_dataset_$year,
  title  = {$name},
  note   = {Version $version},
  year   = {$year},
  doi    = {$doi},
  url    = {$homepage}
}
```

Please also credit the image sources listed above when you use their images.
