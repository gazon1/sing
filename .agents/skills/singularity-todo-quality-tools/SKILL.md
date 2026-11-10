---
name: singularity-todo-quality-tools
description: Run detekt, ktlint, and kover on the Singularity Todo KMP project. Use whenever you need to check code quality, auto-fix formatting, generate coverage reports, or run the full check pipeline. Covers detekt 2.0.0-alpha.6 (Kotlin 2.4.10 compatible), the standalone ktlint CLI 1.8.0 for formatting, and kover 0.9.9 for coverage.
---

# Quality Tools — detekt, ktlint, kover

## Tool stack

| Tool | Version | Role |
|---|---|---|
| **detekt** | 2.0.0-alpha.6 | Static analysis: logic, complexity, naming, performance, exceptions |
| **ktlint** | 1.8.0 (standalone CLI) | Formatting, imports, whitespace — a separate tool, not a detekt plugin |
| **kover** | 0.9.9 | Code coverage for `shared` (all KMP source sets) + `desktopApp` |

**Why detekt 2.x alpha?** — Stable 1.23.8 was compiled against Kotlin 2.0.21 and refuses to run under Kotlin 2.4.10. 2.0.0-alpha.6 explicitly targets Kotlin 2.4.10 (2.0.0-alpha.3 was for Kotlin 2.3.21).

## Key conventions

- ktlint is a **standalone CLI** (pinned in `mise.toml`), not a detekt plugin: detekt owns the semantics, ktlint owns the layout, and neither one corrects the other's findings
- ktlint rules are configured in **`.editorconfig`** (`ktlint_*` keys), not in `config/detekt/detekt.yml` — that file holds the semantic rules only
- ktlint rule IDs are **kebab-case** (e.g. `no-wildcard-imports`, `max-line-length`), and they are switched off with `ktlint_standard_<rule-id> = disabled`
- Baseline files at `config/detekt/baseline-{shared,desktopApp}.xml` absorb existing violations — **do not edit them manually**
- Configuration cache is **ON** (`gradle.properties`) — all tools are CC-compatible
- When a tool reports something this page does not explain — a detekt config error, a ktlint rule that does not fire, a red CI run, a module to add — the failure tables and the walkthroughs are in [`REFERENCE.md`](REFERENCE.md)

## Just recipes

```bash
just lint            # run ktlint and detekt — enforcing
just ktlint          # check the formatting with the ktlint CLI (seconds)
just ktlint-fix      # format the tree with the ktlint CLI
just detekt-fix      # report detekt violations (--auto-correct is broken; see ADR 2026-10-09)
just detekt-baseline # regenerate baseline files (manual-fix pass)
just coverage        # aggregated kover XML → build/reports/kover/
just coverage-html   # aggregated kover HTML → build/reports/kover/html/
just tcheck          # full pipeline: tests + assembleDebug + lint
```

> **⚠️ Auto-fix is broken:** `--auto-correct` is inert in detekt 2.0.0-alpha.6 (Gradle plugin).
> `just detekt-fix` reports violations only — fix them manually. See ADR 2026-10-09.

## Direct Gradle commands

```bash
# ktlint — the formatting, as a standalone tool
python3 scripts/ktlint-lint.py                                  # check (enforcing)
python3 scripts/ktlint-lint.py --format                         # format

# detekt
./gradlew :shared:detekt :desktopApp:detekt                    # check (enforcing)
./gradlew :shared:detekt --rerun-tasks                        # force rerun
./gradlew :shared:detektBaseline :desktopApp:detektBaseline  # generate baselines

# kover — always via the ROOT aggregate task, never a per-project report
./gradlew koverReport          # XML: runs every JVM test task, then merges
./gradlew koverHtml      # HTML
just coverage                  # = ./gradlew koverReport

# Full check
./check.sh
```

## Changing a gate

**A gate is changed in one place.** `scripts/ci/static-gates.sh` is the single registry of
every gate that needs no JVM and no build output, and both `ci.yml`'s `static` job and
`check.sh` invoke it, so a gate cannot exist on one surface and be forgotten on the other.
The `gate` line declares its mode, `blocking` or `advisory`; `|| true` and `set +e` on such
a line are rejected by Part D of the meta-gate, so a gate cannot be quietly softened.

The rest of the CI shape — how a gate is declared, the gates that need a binary, the local
toolchain bootstrap, the three workflows, the meta-gate's parts A–G, the two traps,
reading a red CI run, adding a new module, the troubleshooting tables and the files the
quality tools touch — is in [`REFERENCE.md`](REFERENCE.md).

## Config files

| File | Purpose |
|---|---|
| `config/detekt/detekt.yml` | Shared detekt rules — the semantics (single source of truth) |
| `config/detekt/baseline-shared.xml` | Baseline of current violations for `shared` module |
| `config/detekt/baseline-desktopApp.xml` | Baseline of current violations for `desktopApp` module |
| `.editorconfig` | ktlint formatting preferences (`max_line_length = 120`, `ktlint_code_style = ktlint_official`) |

## Config format for ktlint rules (`.editorconfig`)

ktlint reads **`.editorconfig`**, and there are no ktlint rules in
`config/detekt/detekt.yml` any more:

```ini
[*.{kt,kts}]
max_line_length = 120
ktlint_code_style = ktlint_official
ktlint_standard_backing-property-naming = disabled   # the canonical `_state`
```

**Common ktlint rule IDs** (kebab-case; the key that switches one off is
`ktlint_standard_<rule-id> = disabled`):

- `no-wildcard-imports` — forbids `import foo.*`
- `max-line-length` — line length (set `max_line_length = 120` in `.editorconfig`)
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
./gradlew :koverVerify                       # enforce INSTRUCTION/BRANCH/LINE floors (27/20/27)
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
the loop's `gate script tests` step and in CI:

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

`scripts/check-test-task-inputs.py` (in the loop's `static gates` step and in CI) fails when a
path-valued system property points outside its module with no input covering it.

**Proving the fix.** An input declaration that was not probed is a comment. Edit the file
the test reads, re-run the *same* command, and confirm the task is no longer
`UP-TO-DATE`. Note the trap in the proof itself: the `--tests` filter is a task input, so
the first run after changing the filter always executes — a probe needs two runs with an
identical command, or it proves nothing.

ADR `2026-10-05-test-task-external-inputs`.
