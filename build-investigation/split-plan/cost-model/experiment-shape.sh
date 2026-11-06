#!/usr/bin/env bash
# Parity experiment 2: is the gap the *shape* of the code rather than its size?
#
# Same N = 1237, same Compose plugin, plus what :shared's sources contain and the stand's did not:
# @Serializable data classes (277 in :shared), the kotlinx-serialization compiler plugin, and composable
# bodies with state instead of one-line returns (868 @Composable in :shared).
#
# The classpath half of the gap was already ruled out by experiment-classpath.sh.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
CYCLE="$ROOT/build-investigation/split-plan/cycle.sh"
RESULTS="$ROOT/build-investigation/split-plan/results/parity"
SIZE=${1:-1237}
serialization=$(grep -E '^version\.kotlinSerialization=' "$ROOT/gradle.properties" | cut -d= -f2)

python3 - "$HERE/module/build.gradle.kts" "$serialization" <<'PY'
import sys
out, serialization = sys.argv[1:3]
open(out, "w", encoding="utf-8").write(f'''plugins {{
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}}

kotlin {{
    jvmToolchain(21)
    jvm()
    sourceSets["commonMain"].apply {{
        kotlin.srcDir(layout.buildDirectory.dir("generated/kotlin"))
        dependencies {{
            implementation("org.jetbrains.compose.runtime:runtime:1.12.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:{serialization}")
        }}
    }}
}}
''')
PY

python3 "$HERE/generate.py" --size "$SIZE" --compose --serializable >/dev/null
for n in 1 2 3 4 5; do
  out="$RESULTS/shape-$SIZE-$n"
  [ -d "$out" ] && continue
  PROJECT_DIR="$HERE" EDIT_HOOK="$HERE/edit-hook.sh" \
    "$CYCLE" module "" "$out" || { echo "cycle failed at $n" >&2; exit 1; }
done
echo "# shape curve at N=$SIZE (compose + serialization + stateful composables)"
grep -E "^shape-$SIZE-[1-5]" "$RESULTS/SUMMARY.tsv" | cut -f1,4
