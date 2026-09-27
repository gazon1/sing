---
name: singularity-todo-quality-tools
description: Run detekt, ktlint, and kover on the Singularity Todo KMP project. Use whenever you need to check code quality, auto-fix formatting, generate coverage reports, or run the full check pipeline. Covers detekt 2.0.0-alpha.3 (Kotlin 2.3.21 compatible), ktlint bundled via detekt-rules-ktlint-wrapper, and kover 0.9.9 for coverage.
---

# Quality Tools — detekt, ktlint, kover

## Tool stack

| Tool | Version | Role |
|---|---|---|
| **detekt** | 2.0.0-alpha.3 | Static analysis: logic, complexity, naming, performance, exceptions |
| **ktlint** | via `detekt-rules-ktlint-wrapper` | Formatting, imports, whitespace — runs inside detekt |
| **kover** | 0.9.9 | Code coverage for `shared` (all KMP source sets) + `desktopApp` |

**Why detekt 2.x alpha?** — Stable 1.23.8 was compiled against Kotlin 2.0.21 and refuses to run under Kotlin 2.3.21. 2.0.0-alpha.3 explicitly targets Kotlin 2.3.21.

## Key conventions

- `detekt 2.x` removed `detektFormat` — auto-fix is **merged into the `detekt` task** itself when `autoCorrect=true` in the ktlint config section
- ktlint rules are configured under the `ktlint:` top-level key in `config/detekt/detekt.yml`, **not** in the `style:` section
- ktlint rule IDs are **kebab-case** (e.g. `no-wildcard-imports`, `max-line-length`)
- Baseline files at `config/detekt/baseline-{shared,desktopApp}.xml` absorb existing violations — **do not edit them manually**
- Configuration cache is **ON** (`gradle.properties`) — all tools are CC-compatible

## Just recipes

```bash
just lint            # run detekt analysis (shared + desktopApp) — report-only
just detekt-fix      # auto-fix detekt rules + ktlint formatting (in-place) ✅ USE THIS BEFORE COMMIT
just detekt-baseline # regenerate baseline files (after large auto-fix pass)
just coverage        # kover XML reports → shared/build/reports/kover/
just coverage-html   # kover HTML reports → shared/build/reports/kover/
just tcheck          # full pipeline: tests + assembleDebug + lint
```

> **Critical:** `detekt-fix` requires `--auto-correct` flag. Without it, ktlint only reports violations without fixing them. The recipe was fixed in PR 1.4 to include this flag — older branches may not have it.

## Direct Gradle commands

```bash
# detekt
./gradlew :shared:detekt :desktopApp:detekt                    # check (report-only)
./gradlew :shared:detekt --rerun-tasks                        # force rerun
./gradlew :shared:detektBaseline :desktopApp:detektBaseline  # generate baselines

# kover
./gradlew :shared:koverXmlReport :desktopApp:koverXmlReport   # XML
./gradlew :shared:koverHtmlReport :desktopApp:koverHtmlReport # HTML
./gradlew :shared:koverGenerateArtifact :desktopApp:koverGenerateArtifact # generate merged artifact

# Full check (no adb)
SKIP_ADB=1 ./check.sh
```

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
    alias(libs.plugins.kover)              // optional, for coverage
}
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

**4. Add kover config (optional):**
```kotlin
kover {
    reports {
        total {
            html { onCheck = true }
            xml { onCheck = true }
        }
    }
}
```

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

| Module | detekt | kover | CI job |
|--------|--------|-------|--------|
| `shared` | ✅ | ✅ (xml+html onCheck) | ci.yml test-and-check |
| `desktopApp` | ✅ | ✅ (xml+html onCheck) | ci.yml test-and-check |
| `mcp-server` | ✅ (74 findings baseline) | ✅ | ci.yml mcp-server-check |
| `androidApp` | ✅ | ❌ | ci.yml test-and-check (assemble only) |

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
You are running the wrong detekt version. Stable 1.23.x supports Kotlin 2.0.x. For Kotlin 2.3.x use **detekt 2.0.0-alpha.3** (compiled against Kotlin 2.3.21).

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
| `shared/src/**/build/reports/kover/report.xml` | kover XML coverage report |
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
