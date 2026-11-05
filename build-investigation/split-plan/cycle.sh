#!/usr/bin/env bash
# One measurement cycle: host gate -> forced real compile -> settle -> patch -> measure -> revert.
#   cycle.sh <gradle-module> <patch> <out-dir> [strategy]
#
# Protocol and why each step exists: README.md in this directory.
set -euo pipefail

M=${1:?module}; PATCH=${2:?patch}; OUT=${3:?out-dir}; S=${4:-in-process}
T=":$M:compileKotlinJvm"
REPORTS=build/reports/kotlin-build
SUMMARY="$(dirname "$OUT")/SUMMARY.tsv"
MAX_LOAD_PER_CORE=${SPLIT_PLAN_MAX_LOAD_PER_CORE:-1.0}
OPTS=(-Pkotlin.compiler.execution.strategy="$S" -Pcoverage.enabled=false
      -Pkotlin.build.report.output=file --console=plain --no-build-cache)

# Host state: sampled BEFORE the measurement, because a sample after it measures this compile, not the host.
host_state() {
  printf '%s loadavg=%s cpu_pressure=%s gradle_daemons=%s kotlin_daemons=%s\n' "$1" \
    "$(cut -d' ' -f1-3 /proc/loadavg)" \
    "$(tr '\n' ';' </proc/pressure/cpu 2>/dev/null || true)" \
    "$(pgrep -fc GradleDaemon 2>/dev/null || echo 0)" \
    "$(pgrep -fc KotlinCompileDaemon 2>/dev/null || echo 0)"
}

# 0. A busy host is indistinguishable from a slow commit. 1.0/core, not the skill's 2.0: the skill's own
#    numbers (1.0/core -> 8 min, 2.6-3.2/core -> 24 min for :shared:jvmTest) show 2.0 already includes the
#    range where load dominates the result.
load1=$(cut -d' ' -f1 /proc/loadavg); cores=$(nproc)
awk -v l="$load1" -v c="$cores" -v m="$MAX_LOAD_PER_CORE" 'BEGIN{exit !(l/c <= m)}' \
  || { echo "load $load1 on $cores cores exceeds $MAX_LOAD_PER_CORE/core; retry in a quiet window" >&2; exit 8; }

# Result files land in results/ while the run is in flight, so they are not dirt.
dirty() { git status --porcelain -- . ':(exclude)build-investigation/split-plan/results'; }

[ -z "$(dirty)" ] || { echo "tree is not clean:" >&2; dirty >&2; exit 3; }
[ ! -e "$OUT/result.txt" ] || { echo "refusing to overwrite $OUT/result.txt" >&2; exit 7; }
mkdir -p "$OUT"

./gw "$T" --rerun-tasks "${OPTS[@]}" --info >"$OUT/1-force.log" 2>&1
[ -d "$REPORTS" ] || { echo "no $REPORTS after the forced run — check kotlin.build.report.output" >&2; exit 6; }

./gw "$T" "${OPTS[@]}" --info >"$OUT/2-settle.log" 2>&1
grep -q "Task $T UP-TO-DATE" "$OUT/2-settle.log" \
  || { echo "settle is not UP-TO-DATE: see $OUT/2-settle.log" >&2; exit 2; }

host_state before >"$OUT/host.txt"
git apply "$PATCH"
trap 'git apply -R "$PATCH" 2>/dev/null || true' EXIT

# Marker after git apply and after settle: only the measured run's report can be newer than it.
marker="$OUT/.marker"; touch "$marker"
start=$(date +%s%N); status=0
./gw "$T" "${OPTS[@]}" --info >"$OUT/3-measure.log" 2>&1 || status=$?
end=$(date +%s%N)
host_state after >>"$OUT/host.txt"

report=$(find "$REPORTS" -name '*.txt' -newer "$marker" -print -quit)
trap - EXIT
git apply -R "$PATCH"

# One extraction, anchored, then validated: an empty or non-numeric time must fail here,
# not survive as a blank column in SUMMARY.tsv.
[ -n "$report" ] || { echo "no build report for the measured run" >&2; exit 4; }
cp "$report" "$OUT/report.log"                       # *.log is git-ignored: the report stays local
task_s=$(sed -n "s/^[[:space:]]*Task '$T' finished in \(.*\) s[[:space:]]*$/\1/p" "$OUT/report.log")
case "$task_s" in
  ''|*[!0-9.]*) echo "no numeric task time for $T in $report (got '$task_s')" >&2; exit 4 ;;
esac

{
  echo "module=$M"; echo "task=$T"; echo "strategy=$S"
  echo "commit=$(git rev-parse HEAD)"; echo "patch=$PATCH"; echo "gradle_exit=$status"
  echo "task_s=$task_s"; echo "wall_ms=$(( (end - start) / 1000000 ))"
  # The source count is not a report field: it is the number of lines under "Compile iteration:".
  grep -E "Non-incremental compilation will be performed|Incremental compilation completed|REBUILD_REASON|Classpath changes info|kotlin compiler using" \
    "$OUT/report.log" || true
  echo "--- tasks executed ---"
  grep -E "^> Task " "$OUT/3-measure.log" | sort -u || true
  echo "--- host ---"
  cat "$OUT/host.txt"
} >"$OUT/result.txt"

[ -f "$SUMMARY" ] || printf 'run\tstrategy\tcommit\ttask_s\twall_ms\tgradle_exit\tnon_incremental\tload1\n' >"$SUMMARY"
printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$(basename "$OUT")" "$S" "$(git rev-parse --short HEAD)" \
  "$task_s" "$(( (end - start) / 1000000 ))" "$status" \
  "$(grep -c 'Non-incremental compilation will be performed' "$OUT/result.txt")" "$load1" >>"$SUMMARY"

[ -z "$(dirty)" ] || { echo "tree dirty after revert:" >&2; dirty >&2; exit 5; }
exit "$status"
