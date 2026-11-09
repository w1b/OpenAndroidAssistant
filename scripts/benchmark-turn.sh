#!/usr/bin/env bash
#
# Copyright 2026 OpenAndroidAssistant contributors
# SPDX-License-Identifier: AGPL-3.0-or-later

set -euo pipefail

readonly PACKAGE="org.openandroidassistant.app"
readonly ACTIVITY="$PACKAGE/org.openandroidassistant.MainActivity"
readonly DEVICE_DIR="/data/user/0/$PACKAGE/files/benchmarks"

prompt="${1:?Usage: $0 PROMPT_NUMBER [REPEATS] [--cold]}"
repeats="${2:-1}"
cold="${3:-}"

[[ "$prompt" =~ ^[0-9]+$ ]]
[[ "$repeats" =~ ^[0-9]+$ ]] && ((repeats > 0))
if [[ -n "$cold" && "$cold" != "--cold" ]]; then
  printf 'Unknown option: %s\n' "$cold" >&2
  exit 2
fi

for ((run = 1; run <= repeats; run++)); do
  if [[ "$cold" == "--cold" ]]; then
    adb shell am force-stop "$PACKAGE"
    for _ in {1..25}; do
      current_pid="$(adb shell pidof "$PACKAGE" 2>/dev/null || true)"
      [[ -z "${current_pid//$'\r'/}" ]] && break
      sleep 0.2
    done
  fi

  printf 'prompt=%s run=%s/%s mode=%s\n' \
    "$prompt" "$run" "$repeats" "$([[ "$cold" == "--cold" ]] && printf cold || printf warm)"
  adb shell am start \
    -f 0x20000000 \
    -n "$ACTIVITY" \
    --es debug_audio_path "$DEVICE_DIR/prompt-$prompt.pcm" \
    >/dev/null

  pid=""
  for _ in {1..75}; do
    pid="$(adb shell pidof "$PACKAGE" 2>/dev/null || true)"
    pid="${pid//$'\r'/}"
    [[ -n "$pid" ]] && break
    sleep 0.2
  done
  if [[ -z "$pid" ]]; then
    printf 'App process did not start within 15 seconds.\n' >&2
    exit 1
  fi

  # Poll the pid-filtered ring buffer. This is slightly less elegant than a streaming pipe, but
  # remains reliable when a cold Android process takes several seconds to attach to adb.
  complete=false
  filtered=""
  for _ in {1..90}; do
    filtered="$(
      adb logcat -d --pid="$pid" -v threadtime |
        awk '
          /OaaPerf|ConversationController|TurnLoop|ToolExecutor/ {
            if ($0 ~ /OaaPerf.*turn_trigger/) lines = ""
            lines = lines $0 "\n"
          }
          END { printf "%s", lines }
        '
    )"
    if [[ "$filtered" =~ OaaPerf.*turn_done ]]; then
      complete=true
      break
    fi
    sleep 1
  done
  if [[ "$complete" != true ]]; then
    printf 'Turn did not complete within 90 seconds.\n' >&2
    exit 1
  fi
  printf '%s\n' "$filtered"

  # After a turn the mic stays open for a follow-up (8 s without speech). Let that run out so the
  # next run starts a fresh turn.
  if ((run < repeats)); then sleep 9; fi
done
