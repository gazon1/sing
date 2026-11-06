#!/usr/bin/env bash
# Gate E: the two assumptions the replay rests on.
#
#   1. Additivity — a build that compiles two modules costs what the per-module model says.
#   2. Cascade — a body-only edit does not rebuild a dependent module; a signature edit does.
#
# If either fails, the replay's arithmetic is void and the split line of work stops there.
#
#   gate-e.sh            run both checks, print a verdict
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
OUT="$ROOT/build-investigation/split-plan/results/gate-e"
SIZE=100
GW=(./gw -p "$HERE" :module-b:compileKotlinJvm --console=plain
    -Pkotlin.build.report.output=file -Pkotlin.compiler.execution.strategy=in-process --no-build-cache)
report() { find "$HERE/build/reports/kotlin-build" -name '*.txt' -newer "$OUT/.marker" -print -quit; }
task_time() { sed -n "s/^[[:space:]]*Task ':$1:compileKotlinJvm' finished in \\(.*\\) s[[:space:]]*$/\\1/p" "$2"; }
executed() { grep -q "^> Task :$1:compileKotlinJvm$" "$2"; }

# The bare configuration: plugin mix is not what this gate varies.
python3 - "$HERE/module/build.gradle.kts.in" "$HERE/module/build.gradle.kts" <<'PY2'
import sys
template, out = sys.argv[1:3]
open(out, "w", encoding="utf-8").write(
    open(template, encoding="utf-8").read().replace("@PLUGINS@", "").replace("@DEPS@", ""))
PY2

mkdir -p "$OUT"
python3 "$HERE/generate.py" --size "$SIZE" --module a >/dev/null
python3 "$HERE/generate.py" --size "$SIZE" --module b >/dev/null

# ── 1. additivity ────────────────────────────────────────────────────────────────────────────────
"${GW[@]}" --rerun-tasks >"$OUT/1-force.log" 2>&1
"${GW[@]}" >"$OUT/2-settle.log" 2>&1
grep -q "Task :module-b:compileKotlinJvm UP-TO-DATE" "$OUT/2-settle.log" \
  || { echo "settle is not UP-TO-DATE" >&2; exit 2; }

python3 "$HERE/generate.py" --edit >/dev/null
touch "$OUT/.marker"; start=$(date +%s%N)
"${GW[@]}" --info >"$OUT/3-measure.log" 2>&1
end=$(date +%s%N)
python3 "$HERE/generate.py" --revert >/dev/null
report_file=$(report)
cp "$report_file" "$OUT/report.log"
a_time=$(task_time module "$OUT/report.log")
b_time=$(task_time module-b "$OUT/report.log")
wall_ms=$(( (end - start) / 1000000 ))
serial=$(python3 -c "print(f'{float('$a_time') + float('$b_time'):.3f}')")
makespan=$(python3 -c "print(f'{max(float('$a_time'), float('$b_time')):.3f}')")
{
  echo "size=$SIZE"
  echo "module_a_s=$a_time"
  echo "module_b_s=$b_time"
  echo "measured_wall_ms=$wall_ms"
  echo "serial_sum_s=$serial"
  echo "makespan_2w_s=$makespan"
} >"$OUT/additivity.txt"

# ── 2. cascade ───────────────────────────────────────────────────────────────────────────────────
# A body-only edit in module a must leave module b UP-TO-DATE; a signature edit must rebuild it.
touch "$OUT/.marker"
python3 "$HERE/generate.py" --edit >/dev/null
"${GW[@]}" --info >"$OUT/4-body.log" 2>&1
python3 "$HERE/generate.py" --revert >/dev/null
body_rebuilds_b=$(executed module-b "$OUT/4-body.log" && echo yes || echo no)

python3 "$HERE/generate.py" --signature >/dev/null
"${GW[@]}" --info >"$OUT/5-signature.log" 2>&1
python3 "$HERE/generate.py" --signature-revert >/dev/null
signature_rebuilds_b=$(executed module-b "$OUT/5-signature.log" && echo yes || echo no)

{
  echo "body_only_rebuilds_dependent=$body_rebuilds_b"
  echo "signature_rebuilds_dependent=$signature_rebuilds_b"
} >>"$OUT/additivity.txt"

cat "$OUT/additivity.txt"
if [ "$body_rebuilds_b" != "no" ] || [ "$signature_rebuilds_b" != "yes" ]; then
  echo "GATE E FAILED: the cascade assumption is wrong" >&2
  exit 1
fi
echo "GATE E: cascade holds; compare serial_sum_s and makespan_2w_s with measured_wall_ms"
