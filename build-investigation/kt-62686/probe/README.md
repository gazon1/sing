# Minimal reproducer — KMP JVM incremental compilation falls back (`KT-62686`)

Two modules, one source file each, the same one-line body edit, the same Kotlin 2.4.10 and Gradle
9.8.2. The only difference is the applied Kotlin plugin:

- `:probe` — `kotlin("multiplatform")` + `jvm()`, source in `src/commonMain/kotlin`
- `:probeJvm` — `kotlin("jvm")`, the same source in `src/main/kotlin`

The KMP module reports `Non-incremental compilation will be performed: Incremental compilation might
be incorrect (KT-62686)`; the JVM module reports `Incremental compilation completed`. Both strategies
(`in-process`, `daemon`) give the same per-module result.

This project is deliberately **not** part of the repository build: it is not in
`settings.gradle.kts`, and it has its own settings file. It exists so the finding can be attached to
a Kotlin issue without the repository's build.

## Reproducing

From the repository root:

```bash
P=build-investigation/kt-62686/probe

run_probe() {   # task, source, strategy
  local task=$1 source=$2 strategy=$3 log
  log=$(mktemp)

  ./gw -p "$P" "$task" --rerun-tasks --no-build-cache -Pkotlin.build.report.output=file \
    -Pkotlin.compiler.execution.strategy="$strategy"
  ./gw -p "$P" "$task" --no-build-cache -Pkotlin.build.report.output=file \
    -Pkotlin.compiler.execution.strategy="$strategy" >"$log" 2>&1
  grep -q "$task UP-TO-DATE" "$log" || { echo "settle not UP-TO-DATE, see $log"; return 1; }

  cp "$P/edits/Probe.edited.txt" "$source"
  ./gw -p "$P" "$task" --no-build-cache -Pkotlin.build.report.output=file \
    -Pkotlin.compiler.execution.strategy="$strategy"
  cp "$P/edits/Probe.original.txt" "$source"
  cmp -s "$P/edits/Probe.original.txt" "$source" || { echo "source not restored: $source"; return 1; }
}

for strategy in in-process daemon; do
  run_probe ":probe:compileKotlinJvm" "$P/probe/src/commonMain/kotlin/probe/Probe.kt" "$strategy"
  run_probe ":probeJvm:compileKotlin" "$P/probeJvm/src/main/kotlin/probe/Probe.kt" "$strategy"
done
```

The task name is part of the pair, not a detail: `:probe:compileKotlinJvm` for the KMP module and
`:probeJvm:compileKotlin` for the JVM module. Settle and measured runs use the **same** task —
switching tasks between them would measure a different compilation.

The report lands in `build-investigation/kt-62686/probe/build/reports/kotlin-build/`; read
`REBUILD_REASON` and `Non-incremental compilation will be performed` / `Incremental compilation
completed` in it.

`--no-build-cache` alone is not enough for the first run: Gradle's up-to-date check would skip the
compiler. `--rerun-tasks` is what forces the compile that creates the incremental state.

## Result (measured 2026-11-05)

| Module | Strategy | Incremental cache | Fallback |
|---|---|---|---|
| `:probe` | `in-process` | bails | `KT-62686` |
| `:probe` | `daemon` | bails | `KT-62686` |
| `:probeJvm` | `in-process` | used | none |
| `:probeJvm` | `daemon` | used | none |

Task times were 0.3–0.6 s in all four cases; the times are irrelevant — the verdict is the report's
rebuild reason.

## Layout

| Path | What |
|---|---|
| `settings.gradle.kts`, `build.gradle.kts` | the probe's own build, no root plugins |
| `gradle.properties` | 2 GB heap for the in-process compiler, build cache off |
| `probe/` | KMP module: `jvm()` target, one file, no other plugin |
| `probeJvm/` | plain JVM module: the same file |
| `edits/Probe.original.txt`, `edits/Probe.edited.txt` | the exact before/after of the one edit, used by both modules |

The edit moves the `MARKS` lookup into a local `val` inside a private function body: no signature,
visibility or call-site change, so it changes what the compiler must recompile and nothing about the
module's API.
