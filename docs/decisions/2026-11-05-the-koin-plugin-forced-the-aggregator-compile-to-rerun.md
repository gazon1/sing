---
title: "The Koin compiler plugin, not Compose, is what made the aggregator compile tasks re-run on every build"
date: 2026-11-05
status: accepted
tags: ["build", "performance", "koin", "di", "kmp"]
---

# The Koin compiler plugin, not Compose, is what made the aggregator compile tasks re-run on every build

## Context

`ADR 2026-10-29-the-compose-plugin-breaks-incremental-compilation` reported that
`:shared` recompiles from scratch on every Gradle invocation, and attributed it to
the Compose compiler plugin. Its evidence was a four-module A/B: two modules with
the Compose plugin recompiled, two without it were `UP-TO-DATE`.

That A/B could not distinguish Compose from Koin, because the two "Compose" modules
(`:shared`, `:desktopApp`) also apply the Koin compiler plugin, and the two controls
(`:adr-core`, `:core-base`) apply **neither** plugin:

| Module | Compose | Koin | Second build |
|---|---|---|---|
| `:shared` | yes | yes | recompiles |
| `:desktopApp` | yes | yes | recompiles |
| `:adr-core` | no | no | UP-TO-DATE |
| `:core-base` | no | no | UP-TO-DATE |

The `--info` line that the first ADR quotes is the same one this ADR starts from:

```
Task ':shared:compileKotlinJvm' is not up-to-date because:
  Task.upToDateWhen is false.
Caching disabled for task ':shared:compileKotlinJvm' because:
  'Task outputs cacheable' not satisfied
```

Both predicates are disabled by one caller. It is the Koin plugin.

## Idea

Read the plugin sources instead of inferring from module names. The Gradle plugin
is `koin-compiler-gradle-plugin` 1.2.1 (`koin-compiler-plugin` in the catalog).
Its `KoinGradlePlugin.configureStrictSafety` contains exactly two lines that
produce the observed messages:

```kotlin
task.outputs.upToDateWhen { !(effective.value && extension.compileSafety.get()) }
task.outputs.cacheIf { !(effective.value && extension.compileSafety.get()) }
```

`effective` is true when the plugin's aggregator auto-detection finds the token
`startKoin`, `koinApplication` or `@KoinApplication` in any source file of the
compilation — **including comments and string literals**, because
`looksLikeAggregator` reads the raw text of every file. `:shared` contained five
such files before the change:

- `core/log/LogBootstrap.kt` — a KDoc comment
- `core/refresh/RefreshableContent.kt` — a comment
- `feature/calendarSync/di/CalendarSyncDiModule.kt` — a comment
- `jvmMain/.../mcp/BuildTheGraph.kt` — a real entry point
- `jvmMain/.../mcp/ToolServerMain.kt` — a comment

The Compose plugin's jar contains no such call on a Kotlin compile task; its only
`upToDateWhen` is in its file-`Download` task. The Compose compiler plugin was
never the cause.

## Decision

1. **The Koin plugin stays.** Its `compileSafety` is the compile-time DI check
   (full-graph at an entry point since 1.1.0), and the measurements below show
   that removing the plugin does not make `:shared` incremental anyway.
2. **`:shared` opts out of the aggregator re-run** with
   `strictSafety = false` and `strictSafetyForceOff = true`.
3. **The production entry point moved out of `:shared`.** The MCP server's
   `BuildTheGraph` no longer starts Koin; a `ToolServerKoinStartup` port is passed
   by the launchers (`:mcp-server`'s `Main.kt` and `:desktopApp`'s `main.kt`).
   The composition root belongs to the process entry point, not to a library used
   by two processes.
4. **The flag remains for the test compilation only.** Without it,
   `compileTestKotlinJvm` reports `Task.upToDateWhen is false` too: the test
   sources contain Koin graph fixtures (`koinApplication { … }`) and a helper that
   names the entry point in its own scan. A test compilation is not an aggregator,
   so `strictSafetyForceOff` is a genuine misfire acknowledgement here — its
   documented purpose.
5. **`:desktopApp` keeps `strictSafety`** (its `startKoin` is a real aggregator and
   the place where the full graph can be checked). `:androidApp`'s AGP compilation
   is not detected at all (measured `UP-TO-DATE`), so nothing changes there.

`strictSafetyForceOff` is required because, since 1.1.0, a plain
`strictSafety = false` is ignored when the detector fires. The flag's
documentation frames it as a comment/string-literal misfire; that is exactly what
remains in `:shared` after the move. `compileSafety` stays enabled, but since
Koin 1.1.0 it is a full-graph check at an entry point, not a per-module one; see
Rationale.

## Rationale

`strictSafety` is the *freshness* net: it forces the aggregator's compile task to
re-run so the full assembled graph is re-validated after a change in a transitive
module's DSL lambda body. It is **not** the per-module dependency check. Since
Koin 1.1.0 per-module validation is removed; full-graph validation runs only at an
entry point. `:shared`'s production entry point (the MCP server) was already
reported as unverifiable:

```
[Koin][KOIN-W003] Graph not verifiable at compile time: the entry point loading
platformModule is loaded with a dynamically-computed module set
```

So the forced recompile was not buying a validation that was happening; it was
buying a recompile. The graphs that matter are still validated at the app entry
points, which keep `strictSafety`:

- `:androidApp` and `:desktopApp` each have a real entry point and the app's module
  list.
- `AndroidGraphResolutionTest` resolves the Android platform graph at runtime.
- `ToolSurfaceWiringTest` and the `:mcp-server` end-to-end test exercise the MCP
  entry point.
- `compileSafety` is still enabled, but since Koin 1.1.0 the per-module check is
  gone: the only compile-time DI check is the full-graph validation at an entry
  point. `:shared` has no verifiable entry point, so its DI errors — missing
  definition (`KOIN-D001`), cycle (`KOIN-D004`), `parametersOf` shape
  (`KOIN-D005`/`D006`), missing property (`KOIN-P001`) — are caught by the app
  entry points and the runtime graph tests, not by `:shared` itself. That is the
  trade this ADR makes, and it is the reason the app modules keep `strictSafety`.

## Measurements (2026-11-05, warm daemon, same machine)

Median of 3 runs, each with the task outcome recorded.

| Scenario | Runs (s) | Median | Task state |
|---|---|---|---|
| M0 `:shared` compile + test compile, no change | 6.12 / 0.89 / 0.82 | 0.89 | `UP-TO-DATE` every run |
| M1 `:desktopApp:compileKotlin`, no change (5 runs) | 8.82 / 3.50 / 2.64 / 2.34 / 2.12 | 2.64 | executes: Koin aggregator |
| M1 `:desktopApp:compileTestKotlin`, no change (5 runs) | 18.15 / 9.31 / 7.42 / 7.32 / 7.17 | 7.42 | executes: `koinApplication` fixtures |
| M1 `:androidApp:compileDebugKotlin`, no change | 7.23 / 0.74 / 0.71 | 0.74 | `UP-TO-DATE` (not detected) |
| M1 `:mcp-server:compileKotlin`, no change | — | 1.32 | executes: its launcher is a real aggregator |
| B1 body edit in `:shared` | 99.83 / 69.41 / 67.49 | 69.41 | executes; `Non-incremental … KT-62686` |
| B2 public signature edit in `:shared` | 76.45 / 75.49 / 77.70 | 76.45 | executes; `Non-incremental` |
| A/B: B1 with the Koin plugin removed | 75.23 / 74.36 / 78.47 | 75.23 | executes; `Non-incremental … KT-62686` |
| B1 `:shared` with `-Pkotlin.compiler.execution.strategy=daemon` | 84.11 / 60.35 / 47.46 | 60.35 | incremental (no `KT-62686`) |

The before/after no-op comparison on one test class
(`core.auth.AuthDomainTest`) was 120.28 s / 122.38 s before the flag and
5.85 s / 1.48 s after it; the second run reports every task `UP-TO-DATE`.

B1/B2 measure `:shared:compileKotlinJvm` only (B2 adds `:desktopApp:compileKotlin`).
The 120 s figure is a narrow *test* run — compile plus a test JVM — so it is not
directly comparable to B1/B2.

The A/B with the Koin plugin removed still ran under `in-process`, so it isolates
the task-state cause from Koin but not the incremental-compilation cause from the
execution strategy. The probe below isolates the strategy.

B5 (stability contract) was checked separately: appending a line to
`config/compose/stability_config.conf` makes `:shared:compileKotlinJvm` report
`Input property 'composeStabilityConfiguration' file … has changed` and run. The
merge already registered the file on every `KotlinCompilationTask`; no
`@InputFile` fix is needed.

The arch suite is red in this tree and at `HEAD`: the same five methods fail
(`ViewModelTestCoverageTest`, `TestTagCoverageTest`, `UiAutomationSelectorTest`,
and two in `TextFieldRewriteContractTest`), 232 tests at `HEAD` against 234 here.
They are pre-existing test drift (a moved `ProjectEditorUiState`, an untagged
desktop test, two missing `mapTestTagsAsResourceIds`, a stale
`ArchiveViewModel` entry), not this change.

The full populations were compared the same way. `:desktopApp:test
-Ptest.tags=fast,slow` reports 303 tests / 115 failed / 4 skipped in **both**
trees, with the same named Compose UI timeout tests. `:shared:jvmTest` reports
4,809 tests: 3 failures at `HEAD` (`BackupRemovalPathTest` plus two
`ProcessTreeTest` methods) and 2 here (the same `BackupRemovalPathTest` plus one
`ProcessTreeTest` method) — the process-tree class is flaky, and no failure is
new.

## Second cause: the `in-process` strategy on a KMP module

The in-process strategy (`ADR 2026-10-22-gradle-memory-budget`, D1) was recorded
as not costing incremental compilation. That was checked against the
`setIncremental` flag, not against the compiler's `KT-62686` bailout. Measured on
a standalone KMP project (Kotlin 2.4.10, JVM target, no Koin, no KSP, no Room):

| Probe | Strategy | Edit |
|---|---|---|
| bare KMP | `daemon` (default) | incremental, 1.16 s |
| KMP + Compose compiler plugin | `daemon` | incremental, 1.22 s |
| KMP + Compose compiler plugin | `in-process` | `KT-62686`, non-incremental |

`:shared` uses `in-process`, so it takes the third row. The plain-JVM
`:desktopApp` under `in-process` is incremental, so the trigger is the strategy
**on a KMP module**, not either alone. On `:shared`, switching to `daemon`
removes `KT-62686`; the edit drops from 67–99 s (in-process) to 47–60 s
(daemon). The fixed cost of the 1,200-file module remains.

The switch is not taken here: `daemon` adds a Kotlin daemon JVM that
ADR 2026-10-22 removed to fit the 6 GB Gradle daemon and the 12 GB per-worktree
ceiling. It is a separate memory-budget decision, and the measured gain (~13 %)
is smaller than the split would give on the same edit.

## Consequences

- **A repeat build of an unchanged commit is free again; an edit is not.** The
  no-op run is 0.82–0.89 s with every compile task `UP-TO-DATE`. The ordinary
  "edit → test" loop still pays the full `:shared` compile (~69 s median), because
  `in-process` makes a KMP module non-incremental (see "Second cause"). The first
  ADR's win is the no-op half only.
- **An edit still compiles `:shared` non-incrementally; the trigger is the
  `in-process` strategy on a KMP module, not Koin, Compose, or KMP alone.**
  Removing the Koin plugin under in-process changes nothing (75.23 s median, same
  `KT-62686`). The probe is incremental under `daemon` and non-incremental under
  `in-process`; `:shared` under `daemon` loses the message and drops to 47–60 s.
  The remaining ~50 s is the module's IC fixed cost, which is what the split
  would address.
- **The `:shared-ui` split is not implemented; edit cost was not evaluated.** It
  would not change task state or the `in-process` strategy's effect, but an edited
  module's cost is proportional to its size, and a smaller UI module would compile
  faster. The split was reverted once because the two modules formed a dependency
  cycle (`:shared` carried the DI aggregator and UI-consuming code). It is the
  remaining lever on edit cost: revisit it if the edit loop becomes the
  bottleneck, and move the UI-consuming code out first.
- **`:desktopApp` is a separate case.** It is a plain JVM module, and there the
  Koin plugin *is* the IC trigger (with the plugin removed, an edit compiles
  incrementally). It keeps `strictSafety` because it can verify its graph; its
  no-op cost is 2.64 s for `compileKotlin` and 7.42 s for `compileTestKotlin`
  (5-run medians), recorded above. The test compile is the larger half because
  its `koinApplication` fixtures trip the same detector.
- **`:androidApp` is not affected**: the plugin's detector does not fire for AGP's
  compilation.
- **`:mcp-server` is now a real aggregator**, because its launcher owns the
  composition root. Its compile task executes on every build (1.32 s median); the
  module is a one-file launcher, so the check is cheap and is in the right place.
- The `:shared` compile task is cacheable again, and a second checkout with the
  shared local build cache restores it: `:shared:compileKotlinJvm FROM-CACHE`
  (also `:shared:kspKotlinJvm`, `:core-base`, `:adr-core`). A remote cache on CI
  is not measured; the local restore is the claim.
- The 2026-10-29 ADR is kept as the record of the misdiagnosis, with a correction
  pointer; its status is `superseded`. The `gradle.properties` comment and the
  memory-budget ADR's D1 amendment are corrected.
- Not verified here: the instrumented Android tests and the `:androidApp` debug
  build. `:androidApp:checkDebugAarMetadata` fails on a pre-existing
  `compose 1.13.0-beta01` requirement for a newer `compileSdk`, unrelated to this
  change; the device is attached but that failure precedes the test task.
  `:shared`'s Android host tests pass.

## Rejected alternatives

- **`:shared-ui` split.** Not the cause of the task-state problem, and its
  edit-cost benefit was not measured. Revisit if the edit loop becomes the
  bottleneck.
- **`strictSafety = true` on `:shared`.** It recompiles the whole module for an
  entry point the plugin itself cannot verify (`KOIN-W003`).
- **Removing the Koin plugin.** Measured: no IC gain on `:shared` under
  `in-process` (the strategy is the limit; see "Second cause"), and it loses the
  compile-time full-graph check at the app entry points.
- **Rewording the comments only.** The real `startKoin` in `BuildTheGraph.kt`
  remained; the port move removes it.
- **Moving the whole `mcp` package to `:mcp-server`.** Works, but is 30 files and
  a new `desktopApp → :mcp-server` edge; the injected port gets the same result
  for two call sites.

## Upstream

- `InsertKoinIO/koin-compiler-plugin#32` — why `strictSafety` exists: cross-module
  DSL lambda edits are invisible to Kotlin IC, so the aggregator's compile is
  forced.
- Koin compiler-plugin options documentation — `strictSafetyForceOff` is
  "for a confirmed misfire"; both options are needed.
- `KT-82893` — `UNSAFE_INCREMENTAL_CHANGE_KT_62686`, open/backlog. It is the
  related KMP IC report, but it is not what `:shared` hits under `daemon`: the
  probe with `daemon` is incremental. The trigger measured here is `in-process`
  on a KMP module.
- Version check: the catalog pins the Koin plugin 1.2.1; 1.2.2 (2026-11-04)
  deprecates `strictSafetyForceOff` and honours `strictSafety = false` with a
  warning. The upgrade is a separate, measured change; it does not address
  `KT-82893`.
- Filed: `InsertKoinIO/koin-compiler-plugin#145` — the compiler plugin turns a
  plain `kotlin("jvm")` module non-incremental on every edit (`:desktopApp`
  measured), and the aggregator detector matches comments. The `:shared` case is
  the `in-process` strategy on KMP, not Koin.
- Follow-up task: upgrade the Koin plugin 1.2.1 → 1.2.2 (deprecates
  `strictSafetyForceOff`, honours `strictSafety = false` with a warning) and
  re-run M0/B1; it is a separate change, not part of this one.

## Links

- `ADR 2026-10-29-the-compose-plugin-breaks-incremental-compilation` — the
  misattribution this corrects
- `koin-compiler-gradle-plugin` 1.2.1, `org/koin/compiler/plugin/KoinGradlePlugin.kt`,
  `configureStrictSafety` and `looksLikeAggregator`
- `shared/build.gradle.kts` — the `koinCompiler { }` block and its comment
- `shared/src/jvmMain/.../mcp/ToolServerKoinStartup.kt` — the port
- `mcp-server/src/main/.../Main.kt`, `desktopApp/src/main/.../main.kt` — the
  composition roots
- `shared/src/jvmTest/.../arch/KoinEntryPointIsWhereItSaysTest.kt` — the guard
- `gradle.properties` — the execution-strategy comment
