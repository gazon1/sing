# Stage 5 — benchmark after the module split

Date: 2026-11-05. Tree: `origin/main` `de57c5d7c` plus the rebased cut (`:core-domain`,
`:core-database`) and the Stage 4 gate/CI wiring. Measurement method matches
`build-investigation/baseline.md`: `:shared:compileKotlinJvm` with
`-Pkotlin.build.report.output=file`, reading the Kotlin Build Report's `REBUILD_REASON`
and the Gradle task outcomes.

## Results

| Scenario | Room KSP | `:shared:compileKotlinJvm` | Wall | Kotlin IC | Notes |
|---|---|---|---|---|---|
| No-op | `UP-TO-DATE` | `FROM-CACHE` (cached tree) | 3 s | — | control |
| No-op, `--no-build-cache` | executed once | `UP-TO-DATE` | 29 s | — | settles the IC state |
| UI edit (`TaskCreateScreen.kt`) | `UP-TO-DATE` | executed | 61–63 s | `KT-62686` | the whole `:shared` source set is dirty |
| ViewModel edit (`TaskCreateViewModel.kt`) | `FROM-CACHE` | executed | 69 s | `KT-62686` | not a Compose file |
| Repository edit (`TaskRepositoryImpl.kt`) | `UP-TO-DATE` | executed | 70 s | `KT-62686` | not a Compose file |
| Room entity edit (`Entities.kt`) | executed | `FROM-CACHE` | 34 s | cached report | `:core-database` compiled; expected |

The no-cache control for the UI edit (`--no-build-cache`, after a no-cache settle) reproduces
the same result: `KT-62686`, 61 s. The fallback is not a build-cache artifact.

## What the split achieved

- Room KSP is isolated: UI, ViewModel and repository edits leave
  `:core-database:kspKotlinJvm` `UP-TO-DATE`/`FROM-CACHE`, and it does not execute.
- A Room entity edit does execute KSP and compiles `:core-database`, as expected.
- The module boundary is correct in Gradle, in the architecture gates, in pre-commit and in
  test-impact (Stage 4).

## What it did not achieve

The original goal — no broad Kotlin IC recompilation caused by `KT-62686` — is **not met**.
With KSP up to date and `Classpath changes: NoChanges`, a one-file UI/ViewModel/repository
edit still reports `REBUILD_REASON: Incremental compilation might be incorrect (KT-62686)` and
recompiles the full `:shared` source set in ~61–70 s, against the baseline's 70–83 s. The split
saves the KSP run (~15 s) but not the recompilation.

The remaining cause is therefore **not Room KSP execution**. The Stage 0 probes already
excluded bare KMP, Koin and the in-process strategy; the remaining suspects are the Compose
compiler plugin (it is applied to the whole module, and a plain ViewModel edit still falls back)
and the module's compile configuration. That is a separate investigation, not another module
move: `:core-data`, repository moves and new abstractions are not justified by this result.

## Post-rebase gates

- `:core-domain:jvmTest`, `:shared:archTest`, `:shared:jvmTest` — green.
- `:core-domain:detekt`, `:core-database:detekt` — green; `:shared:detekt` — the same 4
  pre-existing findings.
- Schema gate and its 52 + 48 tests, `changed-modules` 48 tests, `check-test-runs` 56 tests,
  workflow exit-status and env-context gates — green.
- `:androidApp:assembleDebug`, `:desktopApp:assemble` — green; `platforms;android-37.1` is
  installed locally and the CI composite action is wired into every Android build job.
- `easement check` — red with 2 pre-existing violations (a `desktopApp ↔ shared` cycle and a
  `shared → desktopApp` import; both in test helpers, both present on the base tree). The
  gate arrived with the rebase; no `easement.yml` edit was made.
