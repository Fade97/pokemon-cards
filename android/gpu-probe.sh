#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Runs EmbedderGpuProbe (issue #54) on one device: CPU vs GPU cosine and time per embedding for each model file.
#
#   ANDROID_SERIAL=<device> ./gpu-probe.sh model_f32.tflite model_f16.tflite model_w8.tflite [-- card images...]
#
# The first model whose file name contains "f32" is the reference (or pass PROBE_ARGS="-e reference <name>"). Card
# images (png/jpg crops) are optional; the app's synthetic probe image is always used. Paths are relative to the
# current directory. Builds and installs the debug app and its test APK with `adb install -r` (never
# connectedAndroidTest: that uninstalls the app and its language packs).
# Extra arguments for the test go through PROBE_ARGS, e.g. PROBE_ARGS="-e runs 50 -e precisions FP32,FP16".
set -euo pipefail
: "${ANDROID_SERIAL:?set ANDROID_SERIAL (adb devices -l)}"
export ANDROID_SERIAL

models=(); images=()
target=models
for a in "$@"; do
  if [[ $a == -- ]]; then target=images; continue; fi
  [[ -f $a ]] || { echo "not a file: $a" >&2; exit 2; }
  [[ $a == /* ]] || a=$PWD/$a  # the script changes into its own folder below
  if [[ $target == models ]]; then models+=("$a"); else images+=("$a"); fi
done
(( ${#models[@]} )) || { echo "usage: $0 model.tflite... [-- images...]" >&2; exit 2; }

pkg=de.fabianduerkop.lumifoil.debug
dir=/sdcard/Android/data/$pkg/files/gpuprobe
names=()
for m in "${models[@]}"; do
  n=$(basename "$m")
  # The names go through the device shell and a comma-separated list unquoted.
  [[ $n =~ ^[A-Za-z0-9._+-]+$ ]] || { echo "model file name must be [A-Za-z0-9._+-]: $n" >&2; exit 2; }
  names+=("$n")
done

cd "$(dirname "$0")"
if [[ ${SKIP_BUILD:-} != 1 ]]; then ./gradlew -q :app:assembleDebug :app:assembleDebugAndroidTest; fi
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell rm -rf "$dir/images" "$dir/results.json" && adb shell mkdir -p "$dir/images"
adb push "${models[@]}" "$dir/"
if (( ${#images[@]} )); then adb push "${images[@]}" "$dir/images/"; fi

# am instrument exits 0 even when the test fails, so check its output.
log=$(mktemp); trap 'rm -f "$log"' EXIT
# shellcheck disable=SC2086
adb shell am instrument --user 0 -w -e models "$(IFS=,; echo "${names[*]}")" ${PROBE_ARGS:-} \
  -e class de.fabianduerkop.cardindex.recognition.EmbedderGpuProbe \
  $pkg.test/androidx.test.runner.AndroidJUnitRunner | tee "$log"
grep -q '^OK (1 test)' "$log" || { echo "EmbedderGpuProbe did not pass, no results pulled" >&2; exit 1; }
out="build/gpuprobe-$(adb shell getprop ro.product.model | tr -d '\r ')-$(date +%Y%m%d-%H%M%S).json"
mkdir -p build && adb pull "$dir/results.json" "$out" >/dev/null && echo "results: android/$out"
