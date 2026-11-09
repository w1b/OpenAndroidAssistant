#!/usr/bin/env bash
#
# Copyright 2026 OpenAndroidAssistant contributors
# SPDX-License-Identifier: AGPL-3.0-or-later

set -euo pipefail

readonly PACKAGE="org.openandroidassistant.app"
readonly DEVICE_DIR="/data/user/0/$PACKAGE/files/benchmarks"
readonly STAGING_DIR="/data/local/tmp/oaa-benchmark-audio"
readonly SAMPLE_DIR="${1:-prompt-samples}"
readonly WORK_DIR="${OAA_BENCH_WORK_DIR:-${TMPDIR:-/tmp}/oaa-benchmark-audio}"

command -v adb >/dev/null
command -v ffmpeg >/dev/null
test -d "$SAMPLE_DIR"

mkdir -p "$WORK_DIR"
adb shell run-as "$PACKAGE" mkdir -p files/benchmarks
adb shell mkdir -p "$STAGING_DIR"

index=0
while IFS= read -r -d '' sample; do
  index=$((index + 1))
  output="$WORK_DIR/prompt-$index.pcm"
  ffmpeg -hide_banner -loglevel error -y -i "$sample" \
    -ac 1 -ar 16000 -f s16le "$output"
  adb push "$output" "$STAGING_DIR/prompt-$index.pcm" </dev/null >/dev/null
  adb shell run-as "$PACKAGE" cp \
    "$STAGING_DIR/prompt-$index.pcm" "files/benchmarks/prompt-$index.pcm" </dev/null
  printf '%s\t%s\n' "$index" "$sample"
done < <(find "$SAMPLE_DIR" -maxdepth 1 -type f -print0 | sort -zV)

adb shell rm -r "$STAGING_DIR"
printf 'Staged %s prompt(s) in %s\n' "$index" "$DEVICE_DIR"
printf '%s\n' \
  "Trigger one with:" \
  "scripts/benchmark-turn.sh 1"
