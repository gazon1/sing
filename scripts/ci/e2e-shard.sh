#!/usr/bin/env bash
# Runs INSIDE android-emulator-runner's `script:` step.
#
# Two constraints shape this file, and both are properties of the action:
#
#   1. The emulator is killed the moment `script:` returns. Anything that needs the
#      device — installing the APK, running the flows, writing the JUnit XML — has to
#      happen in here. The nightly workflow used to boot the emulator in one step and
#      then `adb wait-for-device` in the next, which is a device that is already gone.
#
#   2. The action executes each LINE of `script:` as its own command. Variables do
#      not survive between lines and `if`/`for` blocks cannot span lines. That is why
#      this is a file and not a `script: |` block — the smoke suite needs an `if`, and
#      the shard loop needs a `for`.
#
# Env: SUITE=smoke|full  SHARD=1..N  SHARDS=N  (set by e2e.yml)
#   MAESTRO_TIMEOUT    — per-flow timeout in ms passed to maestro test --timeout (default: 60000)
set -euo pipefail

: "${SUITE:?SUITE must be set by the workflow}" "${SHARD:?SHARD must be set}" "${SHARDS:?SHARDS must be set}"
: "${MAESTRO_TIMEOUT:=60000}"

# Where `traceability results` looks for Maestro JUnit when it is given `--maestro`,
# and where the uploaded artifact is read from.
OUT=build/maestro-results
mkdir -p "$OUT"

# The action's own readiness check does not wait for the package manager, and
# launching a flow against a half-booted device is a failure that reads like a
# product defect.
bash Maestro/scripts/wait-for-boot.sh
adb shell input keyevent KEYCODE_WAKEUP
adb shell input keyevent 82

# The binary built in THIS workflow run, not whatever the AVD snapshot holds. A
# relaunched emulator can come back with a different build, and then a green run
# proves nothing about the commit it was run against.
apk=$(ls apk/*.apk | head -n1)
echo "installing $apk"
adb install -r -t "$apk"

# Wait for the app to be ready for instrumentation instead of a fixed sleep.
# Probes the activity stack every second; succeeds when MainActivity appears or
# times out (at which point Maestro's own extendedWaitUntil in launch-clean.yaml
# takes over as a secondary guard). This replaces the hardcoded `sleep 3`.
wait_for_app_ready() {
  local deadline=$((SECONDS + 30))
  echo "probing for app readiness (appId=$1)..."
  until adb shell "dumpsys activity top 2>/dev/null" | grep -q "cmp=$1"; do
    if (( SECONDS > deadline )); then
      echo "WARNING: app readiness probe timed out after 30s — proceeding anyway (Maestro extendedWaitUntil is the secondary guard)"
      return 0
    fi
    sleep 1
  done
  echo "app ready"
}
wait_for_app_ready "com.singularity.todo/.MainActivity"

# Explicit config path — Maestro looks for config.yaml in the workspace root,
# but our config lives in Maestro/config.yaml relative to the repo root.
MAESTRO_CONFIG=Maestro/config.yaml

# One retry per flow; a flow that needs it is reported as flaky, not hidden.
run_flow() {
  local name=$1 attempt
  shift
  echo "DEBUG: pwd=$(pwd) OUT=$OUT name=$name config=$MAESTRO_CONFIG MAESTRO_TIMEOUT=$MAESTRO_TIMEOUT args=$*"
  for attempt in 1 2; do
    if maestro test --config "$MAESTRO_CONFIG" --format junit --output "$OUT/$name.xml" --timeout "$MAESTRO_TIMEOUT" "$@"; then
      if ((attempt > 1)); then
        echo "::warning title=Flaky flow::$name passed only on attempt $attempt"
      fi
      return 0
    fi
  done
  return 1
}

failed=0 ran=0

if [[ $SUITE == smoke ]]; then
  # Run each smoke flow individually — Maestro CLI 2.10.0 does not reliably handle
  # multiple file paths in a single invocation (Top-level directories error).
  mapfile -t smoke_flows < <(find Maestro/flows/smoke/ -name '*.yaml' -type f | sort)
  echo "DEBUG: smoke_flows count=${#smoke_flows[@]} files=${smoke_flows[*]}"
  echo "DEBUG: $(ls -la Maestro/flows/smoke/)"
  if ((${#smoke_flows[@]} == 0)); then
    echo "::error::smoke suite: no flows found in Maestro/flows/smoke/"
    exit 1
  fi
  for f in "${smoke_flows[@]}"; do
    echo "DEBUG: running flow file=$f"
    ran=$((ran + 1))
    run_flow "$(basename "$f" .yaml)" "$f" || failed=$((failed + 1))
  done
else
  # Round-robin slice of the sorted flow list.
  #
  # Shared sub-flows live in Maestro/helpers/, not under Maestro/flows/, so this find
  # picks up only real entry-point flows. If a helper is ever moved under flows/,
  # exclude its directory here or a shard will run it without its entry point.
  mapfile -t flows < <(find Maestro/flows -name '*.yaml' -type f | sort |
    awk -v s="$SHARD" -v n="$SHARDS" '(NR - 1) % n == s - 1')
  if ((${#flows[@]} == 0)); then
    # The nightly used to exit 0 here with an empty list and report success: job
    # outputs do not become environment variables, so the shard loop iterated over
    # nothing and printed `passed=0 failed=0`. A shard that received no work is a
    # broken shard, not a passing one.
    echo "::error::shard $SHARD/$SHARDS received no flows — refusing to report green"
    exit 1
  fi
  for f in "${flows[@]}"; do
    ran=$((ran + 1))
    run_flow "$(basename "$f" .yaml)" "$f" || failed=$((failed + 1))
  done
fi

echo "shard $SHARD/$SHARDS ($SUITE): ran=$ran failed=$failed"
((failed == 0))