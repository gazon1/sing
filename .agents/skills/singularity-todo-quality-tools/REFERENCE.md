# Quality tools — reference

Load this when something in the quality toolchain has to be looked up: how a gate is wired
into CI, what a detekt or ktlint failure means, how a new module is added, which files the
quality tools touch. The method — the tool stack, the conventions, the recipes — lives in
[`SKILL.md`](SKILL.md).

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

**Gates that need a binary.** Three gates shell out to a tool that is not part
of the Python venv: `actionlint` and `yamllint` (inline in the registry) and
`lychee` (behind `scripts/check-doc-links.sh`). CI installs them pinned through
`.github/actions/setup-python-tools`; locally they must be on `PATH`, and a
missing one is a **loud failure, not a skip** — a gate that cannot run looks
exactly like a gate that has nothing to report.

Locally the whole gate toolchain comes from **one uv bootstrap**, the same
shape CI uses:

```bash
bash scripts/ci/setup-local-tools.sh                      # uv venv + the pinned tools
export PATH="$HOME/.venvs/singularity-todo-$(basename "$PWD")/bin:$PATH"   # the venv's bin
bash scripts/ci/preflight-gate-tools.sh                   # what is still missing
```

`scripts/ci/setup-local-tools.sh` creates `$HOME/.venvs/singularity-todo-$(basename "$PWD")`
(one venv per checkout — override with `SINGULARITY_TOOLS_VENV`), installs the local `scripts/src` package **editable**
plus `yamllint==1.38.0` into it, and puts the pinned
`lychee-bin==0.24.2`, `shellcheck-py==0.11.0.1` and the `actionlint` v1.7.7
release binary in a **shared store**, one copy per version on the machine
(`~/.venvs/_tools/<tool>-<version>/bin/<tool>`), with the venv's own copies as
symlinks into it. The binaries are identical in every checkout and expensive
(`lychee` alone is 303 MB — one copy per checkout was 8.9 GB of the same bytes),
so they are shared; the checkout's own code is not, and never is.

Two details that are not negotiable:

- **The local package must be *installed*, not merely on `PYTHONPATH`.** The
  gate scripts' own unit tests run the scripts with a minimal environment
  (`env={'PATH': ...}`), so a package reachable only through `PYTHONPATH` is
  invisible to them and they fail with `ModuleNotFoundError` — which reads like
  a broken test rather than a missing install. R2.3 of
  `scripts/check-script-conventions.py` forbids the manual `PYTHONPATH`
  workaround for the same reason.
- **uv, not `pip`.** CI moved to uv, and four earlier local recipes each failed
  on their own terms (`pip install` under a uv-managed interpreter,
  `uv pip install --system` on an externally managed interpreter,
  `--break-system-packages` on a permission error, `--prefix "$HOME/.local"`
  installing something the interpreter could not import). A venv on `PATH`
  answers all four.

`check.sh` runs `scripts/ci/preflight-gate-tools.sh` before the gate suite, so a missing
tool is named — with the fix command — instead of surfacing as a traceback from
inside a gate.

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

**Why everything device-related lives in `scripts/src/emulator/`.** The
`android-emulator-runner` action kills the emulator the moment its `script:`
step returns, and it runs each LINE of `script:` as a separate command, so
variables and `if`/`for` blocks do not survive between lines. Boot, install and
flow execution therefore have to be one script file, not a `script: |` block.
The nightly that booted in one step and ran `adb` in the next addressed a
device that no longer existed. That reasoning is why `scripts/ci/e2e-shard.sh`
was replaced rather than repaired: the whole lifecycle now lives in
`scripts/run-maestro-e2e.py` over `scripts/src/emulator/`.

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

**2. Add `dependencies` block (no `detektPlugins` — formatting is ktlint's):**

```kotlin
dependencies {
    // ... existing deps ...
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

The ktlint rules live in **`.editorconfig`**, not in `config/detekt/detekt.yml` — detekt owns the semantics only. Run `just ktlint` (or `python3 scripts/ktlint-lint.py`) and read the rule ID it reports: rule IDs are **kebab-case** (`no-wildcard-imports`), not PascalCase.

### "Property 'ktlint' is misspelled" error

```
Property 'ktlint' is misspelled or does not exist.
Allowed properties: [comments, complexity, config, console-reports, coroutines, empty-blocks, exceptions, naming, performance, potential-bugs, processors, style]
```

This is a **stale config**: a `ktlint:` section was left in `config/detekt/detekt.yml`. The formatting layer is the standalone ktlint CLI — the section does not belong in detekt's config any more. Delete it and configure ktlint in `.editorconfig`.

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
