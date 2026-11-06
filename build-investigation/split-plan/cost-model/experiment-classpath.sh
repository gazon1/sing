#!/usr/bin/env bash
# Parity experiment: is the 2.3x gap between :shared and the stand the *classpath*?
#
# Same generated sources (N = 1237, Compose), same plugin mix as the compose curve, plus the 230 jars
# :shared compiles against — extracted from its own Build Report (`-classpath` argument). One variable.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
CYCLE="$ROOT/build-investigation/split-plan/cycle.sh"
RESULTS="$ROOT/build-investigation/split-plan/results/parity"
CP="$RESULTS/shared-classpath.txt"
SIZE=${1:-1237}

[ -f "$CP" ] || { echo "missing $CP — extract it from a :shared Build Report first" >&2; exit 2; }

python3 - "$CP" "$HERE/module/build.gradle.kts" <<'PY'
import sys
classpath, out = sys.argv[1:3]
jars = [line.strip() for line in open(classpath, encoding="utf-8") if line.strip()]
listed = ", ".join(f'"{jar}"' for jar in jars)
open(out, "w", encoding="utf-8").write(f'''plugins {{
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}}

kotlin {{
    jvmToolchain(21)
    jvm()
    sourceSets["commonMain"].apply {{
        kotlin.srcDir(layout.buildDirectory.dir("generated/kotlin"))
        dependencies {{ implementation("org.jetbrains.compose.runtime:runtime:1.12.2") }}
    }}
    sourceSets["jvmMain"].dependencies {{ compileOnly(files({listed})) }}
}}

// The only difference from the compose curve: the jars :shared itself compiles against.

''')
PY

python3 "$HERE/generate.py" --size "$SIZE" --compose >/dev/null
for n in 1 2 3 4 5; do
  out="$RESULTS/classpath-$SIZE-$n"
  [ -d "$out" ] && continue
  PROJECT_DIR="$HERE" EDIT_HOOK="$HERE/edit-hook.sh" \
    "$CYCLE" module "" "$out" || { echo "cycle failed at $n" >&2; exit 1; }
done
echo "# classpath-only curve at N=$SIZE"
grep -E "^classpath-$SIZE-[1-5]" "$RESULTS/SUMMARY.tsv" | cut -f1,4
