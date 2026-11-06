# Stage 0 — baseline and criteria (no moves)

Date: 2026-11-05. Commit: `ce143f36f` ("fix(build): separate the Koin task-state cause from the
in-process IC cause") plus the uncommitted `androidApp` `compileSdkMinor = 1` fix. No file has been
moved for the cut.

This file is the comparison base for Stage 5. Values marked **measured** come from the runs that
produced the ADRs; values marked **captured now** come from this stage's run.

## Environment

| Item | Value |
|---|---|
| Kotlin | 2.4.10 |
| Gradle | 9.8.2 |
| KSP | 2.3.13 |
| Room | 3.0.3 |
| AGP | 9.5.0 |
| Compose Multiplatform | 1.12.2 |
| material3 | 1.13.0-beta01 |
| JDK | 21 |
| Android SDK | `platforms;android-37.1` installed via `/home/max/Android/Sdk/cmdline-tools/latest/bin` |

## Commands

```bash
./gw :shared:compileKotlinJvm -Pcoverage.enabled=false --info --profile \
  -Pkotlin.build.report.output=file \
  -Pksp.incremental.log=true
```

- Kotlin Build Report lands in root `build/reports/kotlin-build/` (the task's `dir` property is
  deprecated; do not look in `shared/build/reports`).
- KSP log: `shared/build/kspCaches/jvm/jvmMain/logs/`.
- Scenarios: UI source, ViewModel, repository implementation, Room entity, DAO, no-op. Each run
  records the task graph, every task's outcome (`UP-TO-DATE` / `FROM-CACHE` / executed), wall time,
  the KSP dirty ratio and the Kotlin Build Report `REBUILD_REASON`.

## Measured baseline (adopted from the runs behind the ADRs, same commit)

| Scenario | Result |
|---|---|
| No-op | all tasks `UP-TO-DATE` (the Koin task-state fix; ADR 2026-11-05) |
| UI / ViewModel / repository impl | KSP dirty set **0.07 %** (`DebugInfo.kt` only); `kspKotlinJvm` **15.06 s**; `compileKotlinJvm` **70.33–82.82 s**; **1,456 sources** (1,382 hand-written + 74 generated); `REBUILD_REASON: KT-62686` |
| Room entity / DAO | KSP and compile execute (expected) |
| Koin A/B on `:shared` (with vs without the compiler plugin) | 75.23 s vs 69.41 s; both `KT-62686` — the plugin is not the trigger |
| Compiler strategy (`daemon` vs `in-process`) | 47–60 s vs 67–99 s; both `KT-62686` — `in-process` is not the trigger |

## Probes that established the cause

| Probe | Result |
|---|---|
| Bare KMP | incremental (KMP alone is not the cause) |
| KMP + isolating KSP | dirty 33 %, KSP 0.066 s, compile incremental |
| KMP + KSP + Room (1 entity, 2 impls) | dirty 25 %, KSP 0.168 s, compile incremental |
| KMP + Koin compiler plugin | compile 0.393 s, incremental |

Conclusion: Room's aggregating `@Database` output regenerates all DAO impls; the KMP Kotlin IC falls
back to `KT-62686` and recompiles the whole `:shared` source set. The cut must keep Room KSP from
**executing** on ordinary UI/feature edits.

## Compose stability baseline (captured now)

The `@Immutable` annotations are still in place. `reportsDestination` / `metricsDestination` were
wired temporarily on `:shared` and `:desktopApp` and the compile was rerun with `--rerun-tasks`:

```bash
./gw :shared:compileKotlinJvm :desktopApp:compileKotlin --rerun-tasks \
  -Pcoverage.enabled=false --console=plain
```

- `reportsDestination` produced `singularity-todo:shared-{classes,composables}.txt|csv` and
  `singularity-todo:desktopApp-{classes,composables}.*`; `metricsDestination` produced the
  `*-module.json` files.
- Copied to `build-investigation/compose-stability-before/{shared,desktopApp}/`.
- Baseline classification: `Task`, `Project`, `TagGroup`, `Note` are `stable` in the shared report
  with the `@Immutable` annotations still in place; the desktop composables report is the
  consumer-side reference for the Stage 2 comparison.
- The temporary build-file edit is reverted after the capture; the report is the Stage 2 comparison
  base, not a permanent feature.

## Android baseline

- `material3 1.13.0-beta01` requires `compileSdk 37.1`; downgrading to `1.12.0-alpha03` fails
  (`ExposedDropdownMenu` unresolved).
- Fixed locally with `sdkmanager "platforms;android-37.1"` and `compileSdkMinor = 1` on `:androidApp`
  only (the KMP library extensions do not expose `compileSdkMinor`). `:androidApp:assembleDebug` is
  green.
- CI does **not** install `platforms;android-37.1` yet; Stage 4 adds the step.

## Gate baseline (pre-existing, not caused by this cut)

- 5 architecture findings, 15 detekt findings, and the doc gates are red at the base commit. They are
  pre-existing; the cut must not add to them, and Stage 4 must not make a gate pass by scanning zero
  files.
