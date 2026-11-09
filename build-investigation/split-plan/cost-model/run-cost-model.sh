#!/usr/bin/env bash
# The cost-model grid: sizes × plugin mixes, measured through the shared cycle instrument.
#
#   run-cost-model.sh            the whole grid (long; run it as one background task)
#   run-cost-model.sh bare 100   a single pair, for a smoke test
#
# Two orders matter. Sizes are measured in a fixed shuffled order, not ascending: daemon and compiler
# warm-up land on the intercept `a`, and the replay multiplies `a` by the number of modules, so an
# ascending sweep would charge every cold start to the smallest size. The plugin mix is the outer loop
# because changing it changes the buildscript classpath, which would otherwise be paid inside the next
# mix's first cycle.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
CYCLE="$ROOT/build-investigation/split-plan/cycle.sh"
RESULTS="$ROOT/build-investigation/split-plan/results/cost-model"

# Versions come from the repository's catalog, not from this script: a version copied into a script is
# a version that silently stops matching the project it claims to model.
kotlin=$(grep -E '^version\.kotlin=' "$ROOT/gradle.properties" | cut -d= -f2)
compose=$(sed -n 's/^composeMultiplatform = "\(.*\)"/\1/p' "$ROOT/gradle/libs.versions.toml")
koin_lib=$(sed -n 's/^koin = "\(.*\)"/\1/p' "$ROOT/gradle/libs.versions.toml")
koin_plugin=$(sed -n 's/^koin-compiler-plugin = "\(.*\)"/\1/p' "$ROOT/gradle/libs.versions.toml")

CONFIGS=("bare:250 1 500 44 1237 10 100" "compose:500 100 1237 250" "compose-koin:100 1237 500 250")

configure() {  # <mix>: rewrite the module's plugin set. The only variable of H2.
  local plugins="" deps="" compose_sources=""
  case "$1" in
    bare) ;;
    compose)
      plugins='    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")'
      deps="            implementation(\"org.jetbrains.compose.runtime:runtime:$compose\")"
      compose_sources=--compose
      ;;
    koin)
      plugins='    id("io.insert-koin.compiler.plugin")'
      deps="            implementation(\"io.insert-koin:koin-core:$koin_lib\")"
      ;;
    compose-koin)
      plugins='    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.insert-koin.compiler.plugin")'
      deps="            implementation(\"org.jetbrains.compose.runtime:runtime:$compose\")
            implementation(\"io.insert-koin:koin-core:$koin_lib\")"
      compose_sources=--compose
      ;;
    *) echo "unknown mix: $1" >&2; exit 2 ;;
  esac
  python3 - "$HERE/module/build.gradle.kts.in" "$HERE/module/build.gradle.kts" "$plugins" "$deps" <<'PY'
import sys
template, out, plugins, deps = sys.argv[1:5]
text = open(template, encoding="utf-8").read().replace("@PLUGINS@", plugins).replace("@DEPS@", deps)
open(out, "w", encoding="utf-8").write(text)
PY
  echo "$compose_sources"
}

generate_sources() {  # <size> <compose flag>
  # shellcheck disable=SC2086 # the flag is empty or one word, by construction
  python3 "$HERE/generate.py" --size "$1" $2 >/dev/null
}

run_once() {  # <mix> <size> <cycle>
  local mix=$1 size=$2 n=$3
  local out="$RESULTS/$mix-$size-$n"
  [ -d "$out" ] && { echo "skip $out"; return 0; }
  generate_sources "$size" "$(configure "$mix")"
  PROJECT_DIR="$HERE" \
  EDIT_HOOK="$HERE/edit-hook.sh" \
  EXTRA_OPTS="-PkotlinVersion=$kotlin -PcomposeVersion=$compose -PkoinPluginVersion=$koin_plugin -PkoinVersion=$koin_lib" \
    "$CYCLE" module "" "$out"
}

mkdir -p "$RESULTS"
if [ $# -eq 2 ]; then
  run_once "$1" "$2" 0
  exit 0
fi

for config in "${CONFIGS[@]}"; do
  mix=${config%%:*}
  read -r -a sizes <<< "${config#*:}"
  for n in 0 1 2 3 4 5; do  # 0 is the warm-up: kept in SUMMARY.tsv, excluded from the fit by name
    for size in "${sizes[@]}"; do
      run_once "$mix" "$size" "$n"
    done
  done
done
