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
set -euo pipefail

: "${SUITE:?SUITE must be set by the workflow}" "${SHARD:?SHARD must be set}" "${SHARDS:?SHARDS must be set}"

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

# One retry per flow; a flow that needs it is reported as flaky, not hidden.
run_flow() {
  local name=$1 attempt
  shift
  for attempt in 1 2; do
    if maestro test --format junit --output "$OUT/$name.xml" "$@"; then
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
  ran=1
  run_flow smoke --include-tags smoke Maestro/flows || failed=$((failed + 1))

  # Scenario-tagged flows are read from the flows themselves, so the list cannot go
  # stale. TASK-REC-01 is currently the only one, and running it is the reason this
  # block exists: before 2026-10-05 the smoke job passed only `TAGS=smoke`, so the
  # one flow a scenario could be joined to never executed in CI.
  scenario_tags=$(grep -rhoE 'scenario:[A-Z0-9-]+' Maestro/flows | sort -u | paste -sd, - || true)
  if [[ -n $scenario_tags ]]; then
    ran=$((ran + 1))
    run_flow scenarios --include-tags "$scenario_tags" Maestro/flows || failed=$((failed + 1))
  fi
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