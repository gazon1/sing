---
name: singularity-todo-quality-tools
description: Run detekt, ktlint, and kover on the Singularity Todo KMP project. Use whenever you need to check code quality, auto-fix formatting, generate coverage reports, or run the full check pipeline. Covers detekt 2.0.0-alpha.6 (Kotlin 2.4.10 compatible), ktlint bundled via detekt-rules-ktlint-wrapper, and kover 0.9.9 for coverage.
---

# Quality Tools — detekt, ktlint, kover

## Tool stack

| Tool | Version | Role |
|---|---|---|
| **detekt** | 2.0.0-alpha.6 | Static analysis: logic, complexity, naming, performance, exceptions |
| **ktlint** | via `detekt-rules-ktlint-wrapper` | Formatting, imports, whitespace — runs inside detekt |
| **kover** | 0.9.9 | Code coverage for `shared` (all KMP source sets) + `desktopApp` |

**Why detekt 2.x alpha?** — Stable 1.23.8 was compiled against Kotlin 2.0.21 and refuses to run under Kotlin 2.4.10. 2.0.0-alpha.6 explicitly targets Kotlin 2.4.10 (2.0.0-alpha.3 was for Kotlin 2.3.21).

## Key conventions

- `detekt 2.x` removed `detektFormat` — auto-fix is **merged into the `detekt` task** itself when `autoCorrect=true` in the ktlint config section
- ktlint rules are configured under the `ktlint:` top-level key in `config/detekt/detekt.yml`, **not** in the `style:` section
- ktlint rule IDs are **kebab-case** (e.g. `no-wildcard-imports`, `max-line-length`)
- Baseline files at `config/detekt/baseline-{shared,desktopApp}.xml` absorb existing violations — **do not edit them manually**
- Configuration cache is **ON** (`gradle.properties`) — all tools are CC-compatible

## Just recipes

```bash
just lint            # run detekt analysis (shared + desktopApp) — enforcing
just detekt-fix      # report detekt + ktlint violations (--auto-correct is broken; see ADR 2026-10-09)
just detekt-baseline # regenerate baseline files (manual-fix pass)
just coverage        # aggregated kover XML → build/reports/kover/
just coverage-html   # aggregated kover HTML → build/reports/kover/html/
just tcheck          # full pipeline: tests + assembleDebug + lint
```

> **⚠️ Auto-fix is broken:** `--auto-correct` is inert in detekt 2.0.0-alpha.6 (Gradle plugin).
> `just detekt-fix` reports violations only — fix them manually. See ADR 2026-10-09.

## Direct Gradle commands

```bash
# detekt
./gradlew :shared:detekt :desktopApp:detekt                    # check (enforcing)
./gradlew :shared:detekt --rerun-tasks                        # force rerun
./gradlew :shared:detektBaseline :desktopApp:detektBaseline  # generate baselines

# kover — always via the ROOT aggregate task, never a per-project report
./gradlew koverReport          # XML: runs every JVM test task, then merges
./gradlew koverHtml      # HTML
just coverage                  # = ./gradlew koverReport

# Full check (no adb)
SKIP_ADB=1 ./check.sh
```

## The CI/CD shape, and how to change a gate

**One registry, two callers.** `scripts/ci/static-gates.sh` is the single list of
every gate that needs no JVM and no build output. `ci.yml`'s `static` job and
`check.sh` both invoke it, so a gate cannot exist on one surface and be
forgotten on the other — which was the actual cause of two separate incidents
here. Adding a gate is one line:

```bash
gate blocking "room schema integrity" python3 scripts/check-room-schema-integrity.py
gate advisory "openspec stale" python3 scripts/check-openspec-stale.py
```

`advisory` is the only non-blocking mode and it surfaces as a warning
annotation plus a summary row. `|| true` on a `gate` line is **rejected** by
Part D of the meta-gate, so a gate cannot be quietly softened by pasting an
idiomatic shell expression instead of declaring the mode. `set +e` is rejected
the same way. A `|| true` *inside a helper function* is fine and is not flagged
— `n=$(grep -c … || true)` captures a count, it does not suppress a verdict.

**Three workflows, one required check.**

| Workflow | Jobs | Notes |
|---|---|---|
| `.github/workflows/ci.yml` | `static`, `tests`, `android` (free/pro), `ci-gate` | `ci-gate` is the only branch-protection check. No path filters, on purpose: a required check skipped by a filter stays pending forever |
| `.github/workflows/e2e.yml` | `plan`, `build-apk`, `e2e[shard]`, `nightly-alert` | Maestro. The APK is built once and shared |
| `.github/workflows/release.yml` | `meta`, `android`, `desktop`, `publish` | Tag-triggered; `workflow_dispatch` is a dry run |

`tests` is deliberately **not** split further. Three couplings inside the old
monolith made a six-leaf split measure the wrong thing, and it was withdrawn for
that reason (`docs/decisions/2026-10-05-ci-checks-run-in-parallel.md`): the
`$RUN_STARTED` stamp feeding the count and coverage floors, the flake comparison
against the previous run's artifact, and kover's report needing a test run.
All three live in one job on purpose.

**Why everything device-related lives in `scripts/ci/e2e-shard.sh`.** The
`android-emulator-runner` action kills the emulator the moment its `script:`
step returns, and it runs each LINE of `script:` as a separate command, so
variables and `if`/`for` blocks do not survive between lines. Boot, install and
flow execution therefore have to be one script file, not a `script: |` block.
The nightly that booted in one step and ran `adb` in the next addressed a
device that no longer existed.

**Where the meta-gate reaches.** `scripts/check-gate-wiring.py` has seven parts
and a change to it is a change to the contract of every gate at once:

| Part | Question it answers |
|---|---|
| A | Is every configured Gradle check task named by a gate? |
| B | Can each registered script gate actually fail? (sabotage + restore) |
| C | Is a Gradle check task capable of failing (`ignoreFailures`)? |
| D | Does every non-blocking step declare itself advisory? |
| E | Is every CI/local asymmetry declared with a reason? |
| F | Does every registered gate have a positive control? |
| G | Is the shared registry itself invoked by **both** `ci.yml` and `check.sh`? |

Part G exists because moving the gates into the registry left that file
load-bearing and unchecked: renaming it, or deleting one caller, would drop the
whole gate suite out of CI with nothing failing.

**Two traps worth knowing before you touch a gate.**

- A control that silently stops sabotaging is reported as a *passing* control.
  `room-schema-integrity`'s control replaced `SCHEMA_VERSION = 37` by literal
  while the tree had moved to 38; `replace` found nothing, the gate was handed
  an untouched file, correctly passed, and the control reported "it cannot
  detect this". Match by regex and `assert` the match count, as the
  `test-runs` and `traceability-ratchet` controls do.
- `openspec validate --all --strict` prints `Totals: N failed` and **exits 0**
  when those failures are WARNING-level (e.g. "requirement text is very long").
  Read the exit code, not the summary, when deciding whether a gate is green.

## Reading CI test failures

A red CI run prints only `See the report at: <workspace path>` — a path that
lives on the runner. `scripts/fetch-ci-failures.sh` downloads the run's test
artifacts and prints the failing test names with their stack frames:

```bash
scripts/fetch-ci-failures.sh                 # latest CI run on this branch
scripts/fetch-ci-failures.sh 37201155917     # a specific run
scripts/fetch-ci-failures.sh --keep          # keep the extracted files
```

Exit code `1` means failing tests were found (the summary is still printed),
`2` means the run has no downloadable artifacts. CI uploads the browsable HTML
report (`shared/build/reports/tests/**`) and the JUnit XML
(`**/build/test-results/**`) with `if: always()`, so a failed run still has
them; the coroutine `build/diagnostics/**` bundles are a separate upload and
are only present when a coroutine test actually died.

## Config files

| File | Purpose |
|---|---|
| `config/detekt/detekt.yml` | Shared detekt + ktlint rules (single source of truth) |
| `config/detekt/baseline-shared.xml` | Baseline of current violations for `shared` module |
| `config/detekt/baseline-desktopApp.xml` | Baseline of current violations for `desktopApp` module |
| `.editorconfig` | ktlint formatting preferences (`max_line_length = 140`) |

## Config format for ktlint rules (detekt 2.x)

ktlint rules live under the **`ktlint:` top-level key**, not inside `style:`:

```yaml
ktlint:
  active: true
  code_style: ktlint_official
  auto_correct: true
  no-wildcard-imports:
    active: true
  max-line-length:
    active: false          # disabled; .editorconfig owns this
```

**Common ktlint rule IDs** (kebab-case):
- `no-wildcard-imports` — forbids `import foo.*`
- `max-line-length` — line length (set `max_line_length = 140` in `.editorconfig`)
- `indent` — indentation (default: 4 spaces, respects `.editorconfig`)
- `no-semi` — no semicolons
- `no-trailing-spaces`
- `final-newline` — trailing newline at end of file

## TOML editing safety (gradle/libs.versions.toml)

`libs.versions.toml` uses TOML which does **not support** duplicate section/key names. Before editing versions/libraries/plugins:

```bash
# Always verify before staging edits
git diff gradle/libs.versions.toml
```

**Common mistake:** adding a second `[plugins]` or `[libraries]` section. Use the correct TOML structure:
- All version pins go in `[versions]`
- All library declarations go in `[libraries]`
- All plugin declarations go in `[plugins]` (single section, no duplicates)

**After any change:**
```bash
./gradlew :shared:detekt --no-configuration-cache --no-daemon 2>&1 | grep -i 'TOML\|error' | head -5
```
If you see "TOML syntax error" or "plugins previously defined at line X" — you created a duplicate section.

## Adding a new module (e.g. mcp-server, androidApp)

**1. Add plugins to module's `build.gradle.kts`:**
```kotlin
plugins {
    alias(libs.plugins.kotlinJvm)         // or kotlinMultiplatform, androidApplication, etc.
    alias(libs.plugins.detekt)            // MUST be listed in root build.gradle.kts with apply=false
}
// Do NOT add kover here. It is applied to every project by the settings-level
// `org.jetbrains.kotlinx.kover.aggregation` plugin, and declaring it in a module
// fails the build with "an extension already registered with that name".
```

**2. Add `dependencies` block with `detektPlugins`:**
```kotlin
dependencies {
    // ... existing deps ...
    detektPlugins(libs.detekt.formatting)  // ktlint formatting rules (REQUIRED)
}
```

**3. Add inline detekt config (do NOT use `config.setFrom`):**
```kotlin
detekt {
    buildUponDefaultConfig = true
    ignoreFailures = true   // keep true until baseline is clean
    source.setFrom(
        "src/main/kotlin",
        "src/test/kotlin"
    )
}
```

**4. Coverage needs no per-module config.**
The instrumentation filter and the single report live in `settings.gradle.kts`. A
module that wants out of coverage adds itself to `skipProjects(...)` there, with a
comment saying why. Do not add a `kover { reports { } }` block — it produces a second,
differently-scoped number for the same code.

**5. New test task? Add it to the root `koverReport` in `build.gradle.kts`.**
Kover only merges the `Test` tasks that are already in the task graph; it never
depends on them itself. A test task nobody added there is a test task whose
execution never reaches the coverage number.

**5. Generate baseline:**
```bash
./gradlew :module:detektBaseline
```

**6. Add module to CI workflow** (`.github/workflows/ci.yml`):
```yaml
- name: Check module
  run: |
    ./gradlew :module:compileKotlin --no-daemon
    ./gradlew :module:detekt --no-daemon
    ./gradlew :module:test --no-daemon
  continue-on-error: true
```

## Current modules with quality gates

| Module | detekt | in aggregated kover | CI job |
|--------|--------|----------------------|--------|
| `shared` | ✅ | ✅ `jvmTest` + `testAndroidHostTest` | ci.yml test-and-check |
| `desktopApp` | ✅ | ✅ `test` | ci.yml test-and-check |
| `mcp-server` | ✅ (74 findings baseline) | ✅ `test` | ci.yml mcp-server-check |
| `androidApp` | ✅ | ✅ `test` (unit tests only) | ci.yml test-and-check (assemble only) |
| `detekt-rules` | — | ❌ `skipProjects` (build tooling, suite is red) | — |

## Custom rules (`detekt-rules` module)

The `detekt-rules/` module provides project-specific rules loaded via `META-INF/services/dev.detekt.api.RuleSetProvider`:

| Rule | RuleSet | What it bans |
|---|---|---|
| `NoRealDelayInTestRule` | `no-real-delay-in-test` | `delay(N>1)` in test sources |
| `NoViewModelScopeInProductionRule` | `no-viewmodel-scope` | `viewModelScope.launch/async/cancel` in production |
| `NoRunBlockingRule` | `no-run-blocking` | `runBlocking` in production |
| `NoStateInRule` | `no-state-in` | `.stateIn(...)` in production VMs (exempts `@OptIn(CombineStateInReadThrough)`) |
| `NoStaticProfileAwareCurrentUserRule` | `no-static-profile-aware-current-user` | static/global `ProfileAwareCurrentUser` |
| `PassThroughUseCaseRule` | `pass-through-use-case` | a `UseCase` with no real logic |
| `KDocEnforcementRules` | `kdoc-enforcement` | ViewModel / repository interface without KDoc |
| `NoFactoryViewModelRule` | `no-factory-viewmodel` | `factory { Vm(...) }` / `factoryOf(::Vm)` for a ViewModel |
| `MviViewModelRules` | `mvi-viewmodel` | `VmScopePosition`, `VmCloseable`, `ShadowedState` |

**All of these are enforcing** — `ignoreFailures = false` in `shared/build.gradle.kts` and
`desktopApp/build.gradle.kts` since PR 3.3. A new rule is warning-level until its violation
count reaches zero across `shared` + `desktopApp`; from then on it fails the build.

See `ADR 2026-09-25-detekt-test-rules.md` and `2026-09-26-detekt-rules-activation-audit.md`.

## Adding a custom rule

1. Write `XxxRule.kt` + `XxxProvider` in `detekt-rules/src/main/kotlin/com/singularity/todo/detekt/`.
2. Register the provider in `detekt-rules/src/main/resources/META-INF/services/dev.detekt.api.RuleSetProvider`
   — **a rule missing from that file never runs**, which is how `NoFactoryViewModelRule`
   stayed dormant until 2026-09-27.
3. Add the `xxx:` block to `config/detekt/detekt.yml` with `active: true`.
4. Run `./gradlew :shared:detekt` and check the finding count. Non-zero → keep it as a
   warning until the tree is clean, or fix the violations.
5. Add the rule to the table above.

Details: `singularity-todo-detekt-rules-authoring` skill.

## Common issues

### detekt 2.x config errors (wrong property names)
```
Property 'complexity>TooManyFunctions>allowedFunctions' is misspelled
```
In detekt 2.x, `TooManyFunctions` uses `allowedFunctionsPerFile` (not `allowedFunctions`). Always use the **exact property names** from `config/validation` errors.

### ktlint rules not firing
```
0 number of total findings
```
Check that `ktlint:` is a **top-level** key in `detekt.yml` (not nested under `style:`). ktlint rules require their own section. Rule IDs are **kebab-case** (`no-wildcard-imports`), not PascalCase.

### "Property 'ktlint' is misspelled" error
```
Property 'ktlint' is misspelled or does not exist.
Allowed properties: [comments, complexity, config, console-reports, coroutines, empty-blocks, exceptions, naming, performance, potential-bugs, processors, style]
```
The `detekt-formatting` plugin (ktlint wrapper) is **not registered** in the module's `dependencies {}`. Add:
```kotlin
dependencies {
    detektPlugins(libs.detekt.formatting)
}
```

### Configuration cache errors
```
error writing value of type 'ExistingNamedDomainObjectProvider'
```
This is a known detekt 2.x + Gradle 9.x CC incompatibility. Use `--no-configuration-cache` flag or wait for a fix in a later detekt version.

### "detekt was compiled with Kotlin X but is currently running with Y"
You are running the wrong detekt version. Stable 1.23.x supports Kotlin 2.0.x. For Kotlin 2.4.x use **detekt 2.0.0-alpha.6** (compiled against Kotlin 2.4.10).

### "Plugin not found" for detekt in submodule
```
Plugin [id: 'detekt'] was not found in any of the following sources
```
You used `id("detekt")` instead of `alias(libs.plugins.detekt)`. The `detekt` plugin ID is registered in `gradle/libs.versions.toml` under `[plugins]` and must be referenced via the version catalog:
```kotlin
// WRONG
id("detekt")

// RIGHT
alias(libs.plugins.detekt)
```

## Files modified by quality tools

| File pattern | What happens |
|---|---|
| `shared/src/**/build/reports/detekt/detekt.xml` | detekt XML report (CI artifact) |
| `build/reports/kover/report.xml` | aggregated kover XML coverage report (root project) |
| Kotlin source files | `detekt --rerun-tasks` auto-fixes in-place |

## Related skills

- `singularity-todo-feature-scaffold` — includes lint checklist in new feature PRs
- `singularity-todo-clean-architecture-audit` — runs detekt as part of architecture audit
- `singularity-todo-detekt-workflow` — detailed auto-fix + baseline rebuild workflow
- `singularity-todo-kotlin-idioms` — covers Kotlin idioms that ktlint enforces
- `singularity-todo-koin-dsl` — Koin 4.x DSL canonical patterns
- `singularity-todo-worktree-isolation` — git worktree isolation for refactoring branches


## Auto-correct convergence

`detekt --auto-correct` may need **two passes**: one rule's fix (e.g. Indentation
re-wrapping) can trigger another (NoSemicolons), and vice versa. Run the pass
twice and verify the second run reports zero auto-fixable findings before
committing.

## Coverage is a gated number (2026-10-04)

Coverage is configured **once, at settings level** (`settings.gradle.kts`), and the
report is aggregated across every module's test JVM. It instruments only
`com.singularity.todo.*` — not a performance tweak: the IntelliJ coverage runtime
keeps one `ClassData` per loaded class, and the Koog classpath alone contributes
3,000+ of them (ADR `2026-09-25-test-jvm-heap-default`).

```bash
./gradlew koverReport -Ptest.tags=fast,slow   # ~10 min, runs the tests then merges
python3 scripts/check-coverage.py            # floor: see config/docs/coverage-baseline.txt
python3 scripts/check-coverage.py --if-present
```

### Three things that will waste your time if you do not know them

1. **Use the root `koverReport`, never `:koverXmlReport`.** Kover's aggregation
   plugin picks up the `Test` tasks that are *already in the task graph* and only
   orders them with `mustRunAfter` — it never depends on them. `:koverXmlReport` on a
   clean checkout succeeds and writes a nearly empty report.
2. **`kover { }` in `settings.gradle.kts` does not enable coverage.** You must call
   `enableCoverage()`; the extension is `convention(false)` and the block compiles and
   runs without it. The tell is that `:koverXmlReport` does not exist as a root task.
3. **Do not declare the kover plugin in a module.** The settings plugin applies it to
   every project; a module-level `id("org.jetbrains.kotlinx.kover")` fails with
   "an extension already registered with that name", and `KoverProjectExtension` here
   has no `currentProject { }` wrapper.

The report is `build/reports/kover/report.xml` (root project) — not `xml-report.xml`,
which the CI upload step used for months while carrying nothing. It is measured over
our own packages only: a total across the whole report is dominated by third-party
bytecode and drifts with dependency bumps. `detekt-rules` is `skipProjects`-ed, since
custom detekt rules are build tooling, not application code.

`koverReport` instruments every test task and roughly triples the runtime, so it is
not in the default `./check.sh` path — CI runs it in its own job.

## The gates are tested too

`scripts/tests/` holds unit tests for the gate scripts themselves, run by
`check.sh` step 8 and in CI:

```bash
python3 -m unittest discover -s scripts/tests    # ~20 ms, no JVM
```

A regression inside `check-test-runs.py` would disable the executed-count floor
silently — the same failure shape the gate exists to catch, one level up. The tests
cover the failure direction (a drop, a skipped test, a missing report) as much as
the passing one.

See ADR `2026-10-04-measurement-integrity` and spec
`openspec/specs/test-execution-integrity`.

## A test that reads a file must make the task depend on it

Several architecture tests walk the filesystem rather than asserting over compiled
classes — `MaestroFlowTagsTest` reads every Maestro flow, `TestTagCoverageTest` reads
four source trees. A path opened at runtime is an input to the test task whether or not
it feeds the compiler, and Gradle cannot see that difference:

```bash
./gradlew :shared:jvmTest --tests '…MaestroFlowTagsTest'   # UP-TO-DATE, BUILD SUCCESSFUL
# edit a flow to carry an unknown id:
./gradlew :shared:jvmTest --tests '…MaestroFlowTagsTest'   # still UP-TO-DATE, still green
```

A blocking gate reporting a previous run's verdict about a file it never re-read. This
is the failure mode where the stale answer looks like a pass.

**Writing such a test:** take the root as a `systemProperty` from the module's build
file. Do not walk up from another root to find it — a derived path is one no build file
mentions, so no check can see it. Then declare it:

```kotlin
systemProperty("maestro.root", layout.projectDirectory.dir("../Maestro").asFile.absolutePath)
inputs.dir(layout.projectDirectory.dir("../Maestro"))
    .withPropertyName("maestroFlows")
    .withPathSensitivity(PathSensitivity.RELATIVE)
```

`scripts/check-test-task-inputs.py` (in `check.sh` step 10b and in CI) fails when a
path-valued system property points outside its module with no input covering it.

**Proving the fix.** An input declaration that was not probed is a comment. Edit the file
the test reads, re-run the *same* command, and confirm the task is no longer
`UP-TO-DATE`. Note the trap in the proof itself: the `--tests` filter is a task input, so
the first run after changing the filter always executes — a probe needs two runs with an
identical command, or it proves nothing.

ADR `2026-10-05-test-task-external-inputs`.
