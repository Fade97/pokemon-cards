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

CI (`.github/workflows/android.yml`) runs the first three on every push and pull request that touches `android/`.

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

## Layout

| Path | What |
|---|---|
| `app/src/main/java/.../ui/theme/` | Index design tokens: colour schemes, Schibsted Grotesk type scale, energy colours |
| `app/src/main/java/.../ui/components/CollectorNumber.kt` | The collector number (`006/165`), the signature type |
| `app/src/main/java/.../ui/` | App shell (bottom navigation) and one package per top-level screen |
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
