# KT-62686 — the fallback is KMP's JVM compilation, not the execution strategy

Date: 2026-11-05. Tree: `gazon1/elephantfish` at `a4fbed7a0` (`origin/main` was 7 commits ahead;
none of those commits touch this build's Kotlin, Gradle or `:shared` configuration).

This directory answers https://github.com/gazon1/sing/issues/2600. The question was: after Room KSP
was isolated into `:core-database`, a one-file edit in `:shared` still recompiles the whole module
with `REBUILD_REASON: Incremental compilation might be incorrect (KT-62686)`. What triggers it?

## Answer

**Kotlin's incremental compiler gives up on a KMP module's JVM compilation, with or without any
plugin, and under either execution strategy.** Compose, Koin and Room/KSP are not required to
reproduce the fallback; the fallback also reproduces in a one-file KMP module, and under both
execution strategies:

| Configuration | Kotlin plugin | Strategy | Result |
|---|---|---|---|
| `:shared` (1,237 `.kt` in the iteration) | multiplatform + Compose + Koin + KSP | `in-process` | **non-incremental**, `KT-62686` |
| `:shared` | same, same patch | `daemon` | **non-incremental**, `KT-62686` |
| `:core-domain` (44 `.kt`) | multiplatform, no Compose/KSP/Koin | `in-process` | **non-incremental**, `KT-62686` |
| `:core-database` (83 `.kt`) | multiplatform + Room/KSP | `in-process` | **non-incremental**, `KT-62686` |
| `probe` (1 `.kt`) | multiplatform, nothing else | `in-process` | **non-incremental**, `KT-62686` |
| `probe` | same, same edit | `daemon` | **non-incremental**, `KT-62686` |
| `probeJvm` (1 `.kt`, same source) | `kotlin("jvm")` | `in-process` | incremental |
| `probeJvm` | same, same edit | `daemon` | incremental |

The last two rows are the control: one file, one body-only edit, same Kotlin 2.4.10, same Gradle
9.8.2, same jvmToolchain, same strategy. They isolate the difference to the KMP JVM compilation
configuration versus the plain JVM compilation configuration — the plugin and the compilation model
it creates change together, so this does not name a mechanism inside the Gradle plugin. The JVM-only
module compiles incrementally; the KMP module does not.

So the earlier attribution in `gradle.properties:77-83`, ADR
`2026-11-05-the-koin-plugin-forced-the-aggregator-compile-to-rerun` and the 2026-11-05 amendment in
ADR `2026-10-22-gradle-memory-budget` — "`in-process` makes an edited KMP module non-incremental;
`daemon` removes `KT-62686`" — does not hold: `daemon` does not remove it. `baseline.md`'s row
("both `KT-62686` — `in-process` is not the trigger") was the correct one.

## What is measured and what is inferred

Measured (Build Report fields, per run, in `report-extracts/`):

- `REBUILD_REASON: Incremental compilation might be incorrect (KT-62686)` or its absence;
- `Non-incremental compilation will be performed: …` / `Incremental compilation completed`;
- `Classpath changes info passed from Gradle task: NoChanges`;
- the `Source changes: Known(modified=[…])` set and the compile-iteration size;
- the `Compiling using the … strategy` line, so a property that did not apply cannot pass;
- task wall time and the task's own reported time.

Inferred (not proven here, and not needed for the decision):

- *why* the KMP JVM compilation bails. The report shows `Process SignatureChanged(fqName =
  .kotlin_module)` in both the fallback and the incremental runs, so that line alone is not the
  discriminator; the compiler-internal reason needs an upstream answer.
- whether this is a Kotlin 2.4.10 regression or longstanding KMP behaviour. The repository has no
  measurement on an older Kotlin, and changing the version was out of scope.

## Files

| File | What it holds |
|---|---|
| `reproducibility.md` | Stage 1 — the `:shared` A/B, two independent cycles, one patch |
| `module-probes.md` | Stage 2 — `:core-domain`, `:core-database`, and the minimal probes |
| `probe/` | the committed minimal reproducer (its own Gradle build, never in `settings.gradle.kts`) |
| `report-extracts/` | short extracts of the Build Reports (head, bailout lines, REBUILD_REASON) — not the full reports |
| `edit-shared.patch`, `edit-domain.patch`, `edit-database.patch` | the one body-only edit per module, applied with `git apply` and reversed with `git apply -R` |
| `run-summary.txt` | task outcomes and strategy lines of every run, extracted from the `--info` logs |
| `logs/*.log` | the raw `--info` Gradle logs (git-ignored via `**/logs/` — 1.5 MB and regenerable with the commands above) |

Full Kotlin Build Reports stay in `build/reports/kotlin-build/` and
`probe/build/reports/kotlin-build/`; they are `**/build/`-ignored like every other build output. The
committed evidence is therefore `report-extracts/` (the report extracts), `run-summary.txt` (the task
outcomes) and the documents in this directory — everything a reader needs to see that the verdict
came from the Build Report and not from wall-clock guesswork.

## The protocol (every row above)

```bash
# 1. own settle state for this strategy (--rerun-tasks forces a real compile; --no-build-cache does not)
./gw :shared:compileKotlinJvm --rerun-tasks --no-build-cache -Pcoverage.enabled=false \
  -Pkotlin.build.report.output=file -Pkotlin.compiler.execution.strategy=<strategy> --info
# 2. the same command without --rerun-tasks must report UP-TO-DATE
# 3. git apply --check <patch> && git apply <patch>
# 4. measured run: the step-1 command without --rerun-tasks
# 5. git apply -R <patch>
```

`--no-build-cache` alone does not force a compile — it leaves Gradle's up-to-date check in place, so
a "settle" step can return `UP-TO-DATE` without ever invoking the compiler under the strategy under
test. `--rerun-tasks` is what makes step 1 real.

## Decision

No production change in this task. `kotlin.compiler.execution.strategy=in-process` is **not** the
cause, so the memory-budget trade-off in `ADR 2026-10-22-gradle-memory-budget` never had to be
re-opened; the Compose/Koin/KSP isolation work already done stands, and a further module split for
this symptom is not justified by these measurements. The next step is upstream: `probe/` is the
minimal reproducer to attach to a Kotlin issue, since the trigger is inside the compiler's KMP JVM
incremental path rather than in this build.
