# Android app

Kotlin + Jetpack Compose app (ADR 0001). Package `de.fabianduerkop.cardindex`, debug builds install as
`de.fabianduerkop.lumifoil.debug` next to a release build.

## Requirements

- JDK 17
- Android SDK with platform 37.0 (`compileSdk`; `targetSdk` is 36). Point Gradle at it with `local.properties` (gitignored):
  `sdk.dir=/path/to/Android/sdk`, or set `ANDROID_HOME`.

The Gradle wrapper downloads the right Gradle version on the first run.

## Build and test

Run everything from this folder (`android/`):

```sh
./gradlew :app:assembleDebug        # debug APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # JVM unit tests (Room DAOs run in-memory on Robolectric)
./gradlew :app:lintDebug            # lint, report in app/build/reports/
./gradlew :app:assembleRelease      # R8 + resource shrinking, unsigned (no signing config yet)
```

CI (`.github/workflows/android.yml`) runs all four on every push and pull request that touches `android/`; the
release build checks the R8 keep rules.

## Run on a device or emulator

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n de.fabianduerkop.lumifoil.debug/de.fabianduerkop.cardindex.MainActivity
```

To smoke-test a release build (R8 problems show up only there), sign it with the debug key just for the test:

```sh
apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android \
  --out /tmp/cardindex-release.apk app/build/outputs/apk/release/app-release-unsigned.apk
adb install -r /tmp/cardindex-release.apk
adb shell am start -n de.fabianduerkop.lumifoil/de.fabianduerkop.cardindex.MainActivity
```

## Live scan (Scan tab)

The Scan tab scans without a shutter (issue #8). Every camera frame (CameraX, latest frame only) goes through the
pipeline below; `LiveScanDecider` turns the frames into one decision per physical card:

- **Added**: the collector number confirms the best match (after the rerank), its similarity reaches 0.45, no
  other candidate with the same number is within 0.02, and two frames in a row agree. The card goes into All Cards
  as its own copy (normal, unlimited, near mint), with Undo in the tray.
- **Review**: the card stays unsure for 2 s, or is taken away unsure; the crop and the top 3 go to the review queue
  (Room tables `review_items` / `review_candidates`, crop JPEGs under `files/review/`). Cards from sets with 1st
  edition prints always go there, since a photo can't tell the edition.
- **Once per card**: a decided card is ignored while it stays in view and for 2.5 s after it was last seen; a
  different card replaces it after an empty guide (0.8 s) or three agreeing frames. Cards already decided skip the
  number OCR.

The embedder runs on the GPU (LiteRT `CompiledModel`, `litert-gpu`) when the GPU reproduces the CPU embedding of a
probe image (cosine ≥ 0.995); otherwise on the CPU at about 4 frames per second. A dynamic-range int8 ("w8") model
never passes that check, because the CPU runs it with int8 activations and the GPU in float (issue #54); a float16
model does. `EmbedderGpuProbe` (androidTest) compares CPU and GPU per model file and prints cosine and latency:
`ANDROID_SERIAL=<device> ./gpu-probe.sh model_f32.tflite model_f16.tflite ...` from this folder. The thresholds are first guesses; tune them with `RealPhotoTest` and on devices.

The Scan tab recognizes the cards of the installed language packs. Without any, a debug build with the lab assets
(below) uses those instead, so scanning works before the pack service is deployed.

## Language packs (issue #39)

Format, checks and download rules: [`docs/packs.md`](../docs/packs.md). The app side is in `data/packs/`:

- **First run** asks for the card languages (`ui/packs/`), with each pack's download size from `catalog.json` plus
  the model once. "Not now" leaves it to **Settings → Card languages**, which adds and removes languages, shows the
  installed version and storage, and offers **Download again** for a pack that failed its checks.
- **Sync** (`PackManager`, run by `PackSyncWorker` on WorkManager, network required): full pack or delta chain
  (`PackPlanner`), downloads resumed with `Range` (`PackHttp`), size + SHA-256 checked after every download (a file
  that fails is fetched again at most twice), deltas applied in memory and checked against their `result`, a delta
  that fails falls back to the full pack. A new model downloads the model plus the full packs of every installed
  language and switches in one commit; the old model and packs stay in use until all new files are verified.
- **Polling**: the catalog is requested with `If-None-Match` once a day (periodic work) and when the language
  settings open.
- **Storage**: `noBackupFilesDir/packs/` (re-downloadable, so not in the Android backup). `state.json` names the
  active model and pack directories and is replaced atomically; anything it doesn't name is deleted. The chosen
  languages are in SharedPreferences, which are backed up, so a restored phone downloads them again.
- **On load** (`PackLoader`): SHA-256 of the model and of each `index.bin`, index size = `cards × dim × 4`, and the
  card file's indexed ids = `keysSha256`. A pass is remembered with the file's size and mtime. A pack that fails is
  marked broken: recognition skips that language and Settings offers a fresh download.
- **Card catalog**: each installed pack's sets and cards go into Room (`PackCatalogImporter`), replacing the rows
  scans and backups only filled in. Cards a pack drops are retired, never deleted (owned copies keep them). A set
  row takes its name from the pack in the phone's language if installed, else English.
- The backend base URL is the `API_BASE_URL` BuildConfig field (prices and packs, same Worker); see Prices below.

## Recognition lab (debug builds)

Debug builds have a **Lab** button on the Scan screen. The lab is a test bench for the recognition models from
[`ml/`](../ml/) (issues #7, #8) that shows every intermediate result. It runs the pipeline of `ml/pipeline.py` on
the phone (on the CPU, like the parity tests):

1. **Card outline** inside a dashed 63:88 guide (`ScanGuide.kt`; only the guide plus a 7 % margin is searched, so
   neighbouring cards on a binder page are ignored). Two detectors, switchable under **Details → Outline**:
   - **Learned** (default, `CornerDetector.kt`): a small LiteRT heatmap model that finds the printed card edge,
     not the sleeve or binder-pocket edge. I/O as in the ml README ("Learned corner detector: exact I/O").
   - **Classic** (`CardDetector.kt`): a port of `ml/detect.py` on the same OpenCV version.
2. A **gate** (`OutlineGate.kt`): the outline must reach a minimum score (learned 0.10, classic 0.30; slider) and
   stay put between two frames.
3. **Rectify** to 63:88 → **embedding** on LiteRT (`Embedder.kt`) for the crop and its 180° rotation → cosine kNN
   over the reference index (`ReferenceIndex.kt`), limited to the languages switched on under **Details →
   Languages** (a stand-in for language packs) → collector-number OCR with ML Kit → rerank.
4. A card counts as **identified** at similarity ≥ 0.45 (slider), and as **stable** when every identified frame of
   the last 1.2 s (at least 3) agrees.

**Photo** runs the same steps on a full-resolution photo; with **Save photos** (under Details) it is also stored
in `Pictures/CardIndexLab` for evaluation. **Gallery** photos use the classic detector on the whole image.

The models, index and card list are not in the repository. Build them into `app/src/main/assets/recognition/`
(git-ignored) before building the app. The lab and the parity tests use these assets; without them the lab loads
the installed language packs instead (the build and the unit tests need neither):

```sh
cd ml
CARDINDEX_DATA=/path/to/pokemon-cards-data python fetch.py        # reference images, if not there yet
CARDINDEX_DATA=/path/to/pokemon-cards-data python app_assets.py   # needs <data>/export/mnv3_256_real_w8.tflite
                                                                  # and corners_mnv3_w8.tflite
```

`app_assets.py` needs only numpy, opencv and ai-edge-litert; it embeds every reference image with the same
`.tflite` the app runs. Debug builds contain native code for arm64-v8a and x86_64 only (OpenCV is large).

### Tests on a device

`RecognitionParityTest` compares the app pipeline (classic detector) with the Python pipeline on synthetic photos:

```sh
python ml/synth.py --per-card 1 --seed 777 --name parity --groups en/sv03.5 de/sv03.5 ja/SV2a en/me03 en/me04 de/30th
python ml/app_parity.py --every 5          # writes <data>/app-parity/ (PNGs + expected.json)
adb push <data>/app-parity/. /sdcard/Android/data/de.fabianduerkop.lumifoil.debug/files/parity/
./gradlew :app:connectedDebugAndroidTest   # results.json lands next to the images
```

With the first model (2026-11-16, 206 photos): same top-1 as Python on 97.6 % (Galaxy S25 Ultra) and 98.1 %
(emulator), top-1 93.7 % on both sides, 64 ms per photo without OCR on the phone.

`RealPhotoTest` runs the photo pipeline on labelled real photos (`realphotos/` with `labels.json`, see the class
comment; the photos are the user's own and never go into the repository). Result on the Galaxy S25 Ultra with the
retrained model, 29 photos of German cards, similarity minimum 0.45:

| Outline | Languages | Right | Shown | Shown but wrong | Outline time |
|---|---|---|---|---|---|
| Learned | all | 26 | 27 | 3 | 24 ms |
| **Learned** | **German only** | **28** | 26 | **1** | 24 ms |
| Classic | all | 25 | 22 | 0 | 40 ms |
| Classic | German only | 26 | 22 | 0 | 37 ms |

Two of the three wrong matches with all languages are the right card in English; the third is a badly cut outline.

Without bundled lab assets the test uses the installed language packs, as the app does. Install the debug and
test APKs with `adb install -r` (Gradle's `connectedAndroidTest` uninstalls the app afterwards, which deletes the
packs and the photos), then run `adb shell am instrument --user 0 -w -e class
de.fabianduerkop.cardindex.recognition.RealPhotoTest de.fabianduerkop.lumifoil.debug.test/androidx.test.runner.AndroidJUnitRunner`.
Result on 2026-11-17 with the German, English and Japanese packs (45,170 cards), 73 photos of German cards
(64 de/30th, 9 de/me04), similarity minimum 0.45:

| Outline | Languages | Right | Shown | Shown but wrong | Outline time |
|---|---|---|---|---|---|
| Learned | all | 61 | 69 | 8 | 25 ms |
| **Learned** | **German only** | **64** | 68 | **6** | 32 ms |
| Classic | all | 33 | 49 | 16 | 43 ms |
| Classic | German only | 40 | 46 | 10 | 41 ms |

**Two-pass detection (issue #78).** Photos in the lab (shutter and gallery) use two-pass detection with the learned
outline (`RecognitionEngine.identifyTwoPass`, `recognition/TwoPass.kt`, a port of `two_pass` in `ml/detect.py`):
detect, cut the guide box around the quad (card at 78 % of the box height, 63:88, 7 % margin, clipped to the
photo), run the learned corner detector again on that box (skipped when the box is under 24 px), recognise both
crops in both orientations and keep the second only with a strictly higher top-1 similarity. Live scan stays one
pass; `ml/REPORT.md` ("Two-pass corner detection (#78)") measured no gain in guide framing. The lab chip
"Two-pass (photos)" turns it off for a comparison; the detail line says "second pass kept" when the second crop won.

`RealPhotoTest` runs one pass by default and the learned outline with two-pass when given `-e twoPass true`. It then
writes `results-twopass.json` instead of `results.json` (the classic outline stays one pass), and both files carry
`medianTotalMs` (detection plus identification, no OCR) and `secondPassKept` per configuration:
```
adb shell am instrument --user 0 -w -e class de.fabianduerkop.cardindex.recognition.RealPhotoTest de.fabianduerkop.lumifoil.debug.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument --user 0 -w -e class de.fabianduerkop.cardindex.recognition.RealPhotoTest -e twoPass true de.fabianduerkop.lumifoil.debug.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/data/de.fabianduerkop.lumifoil.debug/files/realphotos/results.json
adb pull /sdcard/Android/data/de.fabianduerkop.lumifoil.debug/files/realphotos/results-twopass.json
```
Not measured on a phone yet (#78, #83).

## Scan contributions (issue #14)

Opt-in uploads for the open dataset (ADR 0008); the API contract is in issue #14 and `backend/README.md` (PR #41).

- **Settings → Open dataset → Contribute my scans** is off by default. Turning it on opens the consent screen
  (CC0 1.0, version `cc0-1`, sent with every upload). It also says that withdrawing doesn't remove a scan from
  dataset releases that are already published. Turning it off deletes scans that haven't been sent.
- **What is sent**: the rectified crop of a scan the user confirmed, either auto-added in live scan or confirmed in
  Review, with the confirmed card id, the print's language and the consent version. The JPEG passes through
  `JpegMetadata.strip` (only JFIF and image segments stay), and no location, device or account data is sent.
  **Undo** on an auto-added card withdraws its contribution, because a wrong match must not stay in the dataset
  under that label.
- **Queue** (`data/contributions/`, Room table `contributions`): `ContributionWorker` (WorkManager, network
  required) sends withdrawals first, then uploads. A `429` is waited out as `Retry-After` says, three times per
  run; other network errors retry with backoff. `400/409/413/415/422` drop that scan. Each scan gets its id (UUID
  v4) and withdraw token (32 random bytes, base64url) when it is queued, and every attempt sends the same ones
  (`?id=`, `Authorization: Bearer`): after a lost answer the retry gets the stored copy back (`200`) instead of
  making a second one without a token on the phone.
- **My contributions** lists every scan with its state; withdraw one or all. Withdrawing a scan not sent yet only
  deletes it locally. For a sent one the app calls `DELETE` with the stored token (`404` counts as done) and then
  forgets it. The withdraw token lives only in the app's database and is redacted from `toString()`, so it never
  reaches a log. The database is in the user's own Android backup, so a restored phone can still withdraw.

## Collections, images and prices

- **Collections** (`ui/collections/`, `data/collections/`): All Cards, one automatic set collection per owned set
  (completion counts distinct collector numbers, so EN/DE prints of one number count once; JP sets are separate
  sets), and custom collections. Card metadata comes through the `CatalogSource` seam (`data/catalog/`), which reads
  the card rows in Room; the installed language packs fill them (see above). Debug builds have **Add sample data** on the empty
  Collections screen (source set `src/debug/`), so the screens can be tried without scanning.
- **Card images** (`data/images/`, `ui/components/CardImage.kt`): `{imageUrl}/low.webp` in grids and
  `{imageUrl}/high.webp` on the card screen, from the TCGdex CDN. Coil keeps them in a 250 MB disk cache
  (`cacheDir/card_images`) regardless of cache headers. No art is in the APK. The user's own photo of a copy
  (`filesDir/scans/<copy uuid>.jpg`, set in the copy sheet) replaces the CDN image when the card has none or when
  **Settings → Prefer my own photos** is on.
- **Prices** (`data/prices/`): the backend's `POST /v1/prices` (≤ 100 ids per call; a `429` is waited out as its
  `Retry-After` says), cached in `cached_prices` per card, market and printing and refreshed when older than
  12 hours. `PriceSelection` values a copy through `PriceVariant.candidates`; TCGplayer (TCGCSV's English category) prices
  English copies only, other languages get Cardmarket. The main market follows the region
  (Cardmarket EUR, TCGplayer USD in the US, Canada and US territories) and can be switched in Settings. The API base
  URL is the `API_BASE_URL` BuildConfig field, by default the deployed backend at `https://cards-api.fdu.gg`.
  Build against another backend with `./gradlew :app:assembleDebug -Pcardindex.apiBaseUrl=https://…`
  (`cardindex.priceApiBaseUrl` still works).

## Pro: price history (issue #17)

- **Entitlements** (`data/pro/`): `Entitlements.isPro` decides whether the Pro screens show; the backend checks Pro
  again on every Pro request (ADR 0007). Sign-in and billing (#16) don't exist yet, so release builds are never Pro.
  Debug builds have **Settings → Pro (debug build) → Pro features**, and send the tester token from the Gradle property
  `cardindex.proTestToken` (put it in `~/.gradle/gradle.properties`, never in the repo; the backend needs its SHA-256
  in `PRO_TEST_TOKENS`, see `backend/README.md`, "Price history"). Without the token the charts say the price service
  did not accept the device. The token is compiled into the debug APK, so a debug APK that leaves your machine
  is a Pro credential: remove its digest from `PRO_TEST_TOKENS` then.
- **History cache** (`data/prices/PriceHistory*.kt`, Room `price_history` + `price_history_fetches`, DB v2): one row
  per card, market, printing and month, with the month's days and amounts packed as text. Fetched from
  `GET /v1/history/<month>?sets=…` (20 sets per call), only for cards the user owns or opens. A past month is fetched
  once on or after the 8th of the next month (the backend rebuilds it until the 3rd), the current month again after 12 hours.
  Months older than 13 are dropped.
- **Charts** (`ui/collections/PriceHistoryViews.kt`): the card screen shows the daily price of the printing its price
  box shows (same `PriceVariant.candidates` rule); the collections screen shows the portfolio under the value header:
  today's copies valued with each day's prices, a copy's last price carried over gaps, copies without a price in the
  main market left out (as in the total). Ranges 30 days, 90 days, 1 year.

## Pro: price alerts (issue #76)

- **Rules** (`data/alerts/PriceAlerts.kt`, Room `price_alerts`, DB v3): one per card, market and printing, "rises to X
  or more" or "falls to X or less" in the market's currency (EUR Cardmarket trend, USD TCGplayer market). Set and
  deleted on the card screen under the price history; Settings → Price alerts lists all of them. Both only show with Pro.
- **Check** (`PriceAlertChecker`, `PriceAlertWorker`): a daily periodic WorkManager job from 07:00 Europe/Berlin
  (after the 05:30 import), only when online and only with Pro. It forces a price refresh of every alerted card
  (`POST /v1/prices`, 100 cards per call) and evaluates each rule: an armed rule whose price is on its side of the
  threshold fires one notification and disarms; it re-arms, silently, once the price is back on the other side. No
  price or another currency changes nothing. The job is scheduled while alerts exist and cancelled with the last one.
- **Notifications**: channel `price_alerts`; `POST_NOTIFICATIONS` (Android 13+) is asked when the first alert is created.
  A notification opens the card (`lumifoil://card/<id>/<language>`, handled only inside the app). Without the
  permission the rules still fire and disarm; the lists show when they last did and warn that notifications are off.
- This is a soft gate by decision ([ADR 0013](../docs/adr/0013-price-alerts-on-device.md)): the rules and the check are
  plain Kotlin so they can move to the backend with sign-in and push (#16).
- Try it in a debug build: switch on Pro in Settings, add an alert on a card with a price (e.g. "rises to" a cent
  under the current price), then tap **Settings → Pro (debug build) → Check price alerts now**. It notifies once; a
  second tap stays quiet until the price crosses back.

## Backup (JSON and CSV)

**Settings → Backup** exports all copies, custom collections and price alerts (JSON only) through the Storage
Access Framework and imports them back. Both formats are matched **by copy and collection uuid**, never by the local row id, so importing the
same file twice changes nothing, and a backup can be merged into a phone whose collection changed since:

- A row from the file replaces the local one when its `updatedAt` is not older and its content differs. A newer
  local edit stays. When an import changes a row whose timestamp equals the local one (e.g. a CSV edited in a
  spreadsheet), the row gets the import time as `updatedAt`.
- Collection membership is merged as a union: an import adds copies to collections, it never removes them.
- Catalog rows (sets, cards) in the file only fill gaps; data from language packs wins. Copies of cards that
  neither the file nor the phone knows are skipped and counted. The file carries the catalog rows of every owned
  and every alerted card.
- Price alerts are matched by uuid too. A new uuid is added with its state (`armed`, `triggeredAt`), so a fired
  alert doesn't notify again after a restore; an alert the phone already has stays as it is (the app can't edit
  an alert, only the check changes its state). Like copies, an alert on a card neither the file nor the phone
  knows is skipped and counted. An import that adds alerts schedules the daily check.
- Own photos (`filesDir/scans/`) are in neither the JSON/CSV export nor the Android backup or device transfer
  (`backup_rules.xml`, `data_extraction_rules.xml`: database and settings only, which keeps the app far below
  Auto Backup's 25 MB quota). Copying a phone's collection keeps the copies; their photos stay on the old phone.

### JSON format, version 2

```json
{
  "format": "cardindex-backup",
  "version": 2,
  "exportedAt": "2026-11-17T10:00:00Z",
  "sets": [{ "id": "sv03.5", "name": "151", "series": "Scarlet & Violet", "language": "en",
             "printedTotal": 165, "region": "INTERNATIONAL" }],
  "cards": [{ "id": "sv03.5-006", "language": "de", "setId": "sv03.5", "localId": "006", "name": "Glurak-ex",
              "rarity": null, "energyType": "FIRE", "imageUrl": "https://assets.tcgdex.net/de/sv/sv03.5/006" }],
  "copies": [{ "uuid": "6f1c3c2e-…", "cardId": "sv03.5-006", "cardLanguage": "de", "quantity": 2,
               "finish": "HOLO", "edition": "UNLIMITED", "condition": "LIGHTLY_PLAYED", "grader": null,
               "grade": null, "purchasePriceMinor": 2450, "purchaseCurrency": "EUR", "purchaseDate": "2025-08-02",
               "createdAt": "2025-08-02T18:00:00Z", "updatedAt": "2026-03-12T09:30:00Z" }],
  "collections": [{ "uuid": "0b9e…", "name": "Trade binder", "description": null,
                    "updatedAt": "2026-03-12T09:30:00Z", "copies": ["6f1c3c2e-…"] }],
  "alerts": [{ "uuid": "8a6b3a52-…", "cardId": "sv03.5-006", "cardLanguage": "de", "source": "CARDMARKET",
               "variant": "HOLO", "direction": "ABOVE", "thresholdMinor": 5000, "currency": "EUR",
               "createdAt": "2026-11-01T08:00:00Z", "triggeredAt": null, "armed": true }]
}
```

- Enums are stored by name: `finish` NORMAL, HOLO, REVERSE_HOLO, OTHER; `edition` UNLIMITED, FIRST_EDITION,
  SHADOWLESS; `condition` NEAR_MINT … POOR (null for graded copies); `grader` PSA, CGC, BGS, OTHER;
  `region` INTERNATIONAL, JP; alert `source` CARDMARKET, TCGPLAYER; `variant` NORMAL, HOLO, REVERSE_HOLO,
  FIRST_EDITION, FIRST_EDITION_HOLO; `direction` ABOVE (fires at or above the threshold), BELOW (at or below).
- An alert's fields are the `price_alerts` columns under the same names. `thresholdMinor` is in the market's
  currency (`currency`); `armed` is false after the alert fired, until the price is back across the threshold.
  Sign-in and sync (#16) will upload alerts with these names and uuids, so keep them stable.
- Money is in minor units (cents) with an ISO 4217 code; dates are ISO-8601 (`LocalDate` for the purchase date,
  UTC instants for the timestamps). Fields that are null may be left out.
- `version` is bumped when an older app would misread the file or silently drop part of it; other new optional
  fields don't bump it. The app refuses files with a higher version than it knows, and still reads every older
  one. Version 2 added `alerts`: a version-1 app would restore such a file without its alerts and not say so.

### CSV format

The CSV is a spreadsheet view of the copies, not a full backup: it has no price alerts. One row per copy, UTF-8 with a byte order mark (so Excel shows Japanese names), comma separated, RFC 4180 quoting:

`uuid, card_id, language, set_id, set_name, set_total, set_region, number, name, quantity, finish, edition,
condition, grader, grade, purchase_price, purchase_currency, purchase_date, collections, created_at, updated_at`

`purchase_price` is a decimal (`24.50`; `24,50` is read too), `collections` lists custom collection names separated
by ` | `, matched by name on import (case-insensitive) and created when missing. Only `card_id`, `language`,
`set_id`, `number` and `name` are required on import; a row without `uuid` is a new copy each time it is imported.

## Layout

| Path | What |
|---|---|
| `app/src/main/java/.../ui/theme/` | Index design tokens: colour schemes, Schibsted Grotesk type scale, energy colours |
| `app/src/main/java/.../ui/components/CollectorNumber.kt` | The collector number (`006/165`), the signature type |
| `app/src/main/java/.../ui/` | App shell (bottom navigation) and one package per top-level screen |
| `app/src/main/java/.../recognition/` | On-device recognition pipeline (detector, embedder, index, number OCR) |
| `app/src/main/java/.../data/db/` | Room schema and DAOs (ADR 0006) |
| `app/src/main/java/.../data/collections/`, `data/catalog/` | Collection repository, set slots, the catalog seam |
| `app/src/main/java/.../data/packs/`, `ui/packs/` | Language packs: catalog, planner, downloads, deltas, store, worker, picker and settings |
| `app/src/main/java/.../data/contributions/`, `ui/contributions/` | Scan contributions: queue, API client, worker, consent and list |
| `app/src/main/java/.../data/prices/` | Price API client, cache refresh, price selection, region and market links, Pro price history |
| `app/src/main/java/.../data/pro/` | Pro entitlements (debug switch until #16) |
| `app/src/main/java/.../data/alerts/` | Pro price alerts: rules, daily check, worker and notifications |
| `app/src/main/java/.../data/images/` | CDN image URLs, the user's own photos |
| `app/src/main/java/.../data/backup/` | JSON/CSV export and import |
| `app/src/debug/` | Debug-only sample data |
| `app/schemas/` | Exported Room schema JSON, one file per database version. Commit it with every schema change. |
| `app/proguard-rules.pro` | R8 keep rules for Room and ML Kit |

## Design

- Product and architecture: [`docs/DESIGN.md`](../docs/DESIGN.md), decisions in [`docs/adr/`](../docs/adr/).
- UI direction "Index" (ADR 0010): clickable mockups in [`design/mockups/c-index.html`](../design/mockups/c-index.html)
  and [`c-index-more.html`](../design/mockups/c-index-more.html). The theme takes its colours, radii and type from
  their `:root` tokens.

## Third-party assets

- Schibsted Grotesk (variable font, `res/font/schibsted_grotesk.ttf`) from
  [google/fonts](https://github.com/google/fonts/tree/main/ofl/schibstedgrotesk), SIL Open Font License 1.1.
  License text and source: `app/src/main/assets/licenses/schibsted-grotesk/`.

## Versions

All versions live in `gradle/libs.versions.toml`. `compileSdk` is 37 (platform `android-37.0`), which current
AndroidX needs; `targetSdk` stays 36. Unit tests run Robolectric on SDK 35 because SDK 36+ needs Java 21.
