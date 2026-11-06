# Stage 5 — benchmark after the module split

Date: 2026-11-05. Tree: `origin/main` `de57c5d7c` plus the rebased cut (`:core-domain`,
`:core-database`) and the Stage 4 gate/CI wiring. The measurement method matches
`build-investigation/baseline.md`: `:shared:compileKotlinJvm` with
`-Pkotlin.build.report.output=file`, read through the Kotlin Build Report's `REBUILD_REASON`
and the Gradle task outcomes.

## Protocol

- `--no-build-cache` on every scenario, so a restored task cannot hide or fabricate a fresh
  compile.
- A settle build first; then exactly one file is edited; then only
  `:shared:compileKotlinJvm` is run.
- Two runs per scenario; the table reports both and the median.
- Full Build Reports are kept in `build-investigation/stage5-reports/full/` (gitignored, not
  committed because of size). The committed evidence is
  `stage5-reports/README.md`, the per-run Gradle logs, and the extracted
  `stage5-reports/summary-*.txt`.

## Results (no build cache)

| Scenario | Room KSP | `:core-database:compileKotlinJvm` | `:shared:compileKotlinJvm` | Wall (r1 / r2, median) | Kotlin IC |
|---|---|---|---|---|---|
| UI edit (`TaskCreateScreen.kt`) | `UP-TO-DATE` | `UP-TO-DATE` | executed | 85 / 72 s, **78.5 s** | `KT-62686` |
| ViewModel edit (`TaskCreateViewModel.kt`) | `UP-TO-DATE` | `UP-TO-DATE` | executed | 71 / 61 s, **66 s** | `KT-62686` |
| Repository edit (`TaskRepositoryImpl.kt`) | `UP-TO-DATE` | `UP-TO-DATE` | executed | 62 / 68 s, **65 s** | `KT-62686` |
| Room entity edit (`Entities.kt`) | executed | executed | r1 executed, r2 `UP-TO-DATE` | 104 / 41 s, 72.5 s | `KT-62686` in r1 |

All `:shared` runs report `Classpath changes: NoChanges` and recompile the whole `:shared`
source set. The entity row is a boundary check, not a `:shared` timing benchmark: a
comment-only entity edit does not change the ABI, so r2 leaves `:shared` `UP-TO-DATE`; r1
still falls back when the KSP output refresh makes `:shared` compile. A real schema/ABI change
is not attempted here because it would require a migration and a schema export.

The earlier 34 s entity figure is discarded: it came from a `FROM-CACHE` result and measured
neither a real `:shared` compile nor a real entity change.

## Baseline comparison, with its limits

The baseline recorded 70–83 s for UI/ViewModel/repository edits, with KSP executing for
15.06 s. The post-split no-cache medians are 65–78.5 s, and the ranges overlap. The only
effect the measurements support is the KSP time no longer being paid; a confident compile-time
speed-up would need the same repeated protocol on the baseline tree. This does not change the
`KT-62686` conclusion, which reproduces with the cache disabled.

## What the split achieved

- Room KSP is isolated: UI, ViewModel and repository edits leave
  `:core-database:kspKotlinJvm` `UP-TO-DATE`, and it does not execute.
- A Room entity edit does execute KSP and compiles `:core-database`, as expected.
- The module boundary is correct in Gradle, in the architecture gates, in pre-commit and in
  test-impact (Stage 4).

## What it did not achieve

The original goal — no broad Kotlin IC recompilation caused by `KT-62686` — is **not met**.
With KSP up to date and `Classpath changes: NoChanges`, a one-file UI, ViewModel or repository
edit still reports `REBUILD_REASON: Incremental compilation might be incorrect (KT-62686)` and
recompiles the full `:shared` source set. The remaining cause is therefore **not Room KSP
execution**.

The Stage 0 probes already excluded bare KMP, Koin and the in-process strategy. The remaining
suspects are the Compose compiler plugin (applied to the whole module, and a plain ViewModel
edit still falls back), compiler arguments, generated-source inputs and the KMP compilation
configuration. That is a separate investigation, not another module move: `:core-data`,
repository moves and new abstractions are not justified by this result. The task is filed as
https://github.com/gazon1/sing/issues/2600.

## Post-rebase gates

- `:core-domain:jvmTest`, `:shared:archTest`, `:shared:jvmTest` — green.
- `:core-domain:detekt`, `:core-database:detekt` — green; `:shared:detekt` — the same 4
  pre-existing findings.
- Schema gate and its 52 + 48 tests, `changed-modules` 48 tests, `check-test-runs` 56 tests,
  workflow exit-status and env-context gates — green.
- `:androidApp:assembleDebug`, `:desktopApp:assemble` — green; `platforms;android-37.1` is
  installed locally and the CI composite action is wired into every Android build job.
- `easement check` — red with the two pre-existing test-helper violations recorded in
  `build-investigation/gates-baseline.md`; `easement.yml` was not edited.
