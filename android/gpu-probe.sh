#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Runs EmbedderGpuProbe (issue #54) on one device: CPU vs GPU cosine and time per embedding for each model file.
#
#   ANDROID_SERIAL=<device> ./gpu-probe.sh model_f32.tflite model_f16.tflite model_w8.tflite [-- card images...]
#
# Put the float32 model first or name it *f32*: it is the reference. Card images (png/jpg crops) are optional; the
# app's synthetic probe image is always used. Builds and installs the debug app and its test APK with
# `adb install -r` (never connectedAndroidTest: that uninstalls the app and its language packs).
# Extra arguments for the test go through PROBE_ARGS, e.g. PROBE_ARGS="-e runs 50 -e precisions FP32,FP16".
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_SERIAL:?set ANDROID_SERIAL (adb devices -l)}"
export ANDROID_SERIAL

models=(); images=()
target=models
for a in "$@"; do
  if [[ $a == -- ]]; then target=images; continue; fi
  if [[ $target == models ]]; then models+=("$a"); else images+=("$a"); fi
done
(( ${#models[@]} )) || { echo "usage: $0 model.tflite... [-- images...]" >&2; exit 2; }

pkg=de.fabianduerkop.cardindex.debug
dir=/sdcard/Android/data/$pkg/files/gpuprobe
names=()
for m in "${models[@]}"; do names+=("$(basename "$m")"); done

if [[ ${SKIP_BUILD:-} != 1 ]]; then ./gradlew -q :app:assembleDebug :app:assembleDebugAndroidTest; fi
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell rm -rf "$dir/images" && adb shell mkdir -p "$dir/images"
adb push "${models[@]}" "$dir/"
if (( ${#images[@]} )); then adb push "${images[@]}" "$dir/images/"; fi

# shellcheck disable=SC2086
adb shell am instrument --user 0 -w -e models "$(IFS=,; echo "${names[*]}")" ${PROBE_ARGS:-} \
  -e class de.fabianduerkop.cardindex.recognition.EmbedderGpuProbe \
  $pkg.test/androidx.test.runner.AndroidJUnitRunner
out="build/gpuprobe-$(adb shell getprop ro.product.model | tr -d '\r ')-$(date +%Y%m%d-%H%M).json"
mkdir -p build && adb pull "$dir/results.json" "$out" >/dev/null && echo "results: android/$out"
