#!/usr/bin/env bash
# Wrapper around ./gw that reports progress for long builds/tests.
# Usage: scripts/gw-progress.sh :shared:jvmTest [extra gw args...]
# Shows elapsed time and the most recent Gradle task line; at the end prints
# the "actionable tasks" summary line Gradle emits.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

LOG="$(mktemp "${TMPDIR:-/tmp}/gw-progress-XXXXXX.log")"
trap 'rm -f "$LOG"' EXIT

# Run gw, teeing to log so we can tail it.
./gw "$@" >"$LOG" 2>&1 &
GW_PID=$!

start=$(date +%s)
last_task=""
while kill -0 "$GW_PID" 2>/dev/null; do
    # extract last "> Task ..." line
    cur=$(grep -E '^> Task ' "$LOG" 2>/dev/null | tail -1)
    [ -n "$cur" ] && last_task="$cur"
    now=$(date +%s)
    elapsed=$((now - start))
    printf '\r\033[K[%ds] %s' "$elapsed" "$last_task" >&2
    sleep 2
done
wait "$GW_PID"
rc=$?
echo "" >&2
echo "--- gw finished (exit $rc) ---" >&2
# print final summary line
grep -E 'actionable tasks:' "$LOG" 2>/dev/null | tail -1 >&2 || true
# replay full output to stdout so callers see it
cat "$LOG"
exit $rc
