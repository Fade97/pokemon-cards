# Android app

Kotlin + Jetpack Compose app (ADR 0001). Package `de.fabianduerkop.cardindex`, debug builds install as
`de.fabianduerkop.cardindex.debug` next to a release build.

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
adb shell am start -n de.fabianduerkop.cardindex.debug/de.fabianduerkop.cardindex.MainActivity
```

To smoke-test a release build (R8 problems show up only there), sign it with the debug key just for the test:

```sh
apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android \
  --out /tmp/cardindex-release.apk app/build/outputs/apk/release/app-release-unsigned.apk
adb install -r /tmp/cardindex-release.apk
adb shell am start -n de.fabianduerkop.cardindex/.MainActivity
```

## Recognition lab (Scan tab)

The Scan tab is a test bench for the recognition models from [`ml/`](../ml/) (issues #7, #8). It runs the pipeline of
`ml/pipeline.py` on the phone:

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
(git-ignored) before building the app; without them the tab says so:

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
adb push <data>/app-parity/. /sdcard/Android/data/de.fabianduerkop.cardindex.debug/files/parity/
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

## Layout

| Path | What |
|---|---|
| `app/src/main/java/.../ui/theme/` | Index design tokens: colour schemes, Schibsted Grotesk type scale, energy colours |
| `app/src/main/java/.../ui/components/CollectorNumber.kt` | The collector number (`006/165`), the signature type |
| `app/src/main/java/.../ui/` | App shell (bottom navigation) and one package per top-level screen |
| `app/src/main/java/.../recognition/` | On-device recognition pipeline (detector, embedder, index, number OCR) |
| `app/src/main/java/.../data/db/` | Room schema and DAOs (ADR 0006) |
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
