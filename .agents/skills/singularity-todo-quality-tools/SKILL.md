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
just lint            # run detekt analysis (shared + desktopApp)
just detekt-fix     # auto-fix detekt rules + ktlint formatting (in-place)
just detekt-baseline # regenerate baseline files
just coverage        # kover XML reports → shared/build/reports/kover/
just coverage-html   # kover HTML reports → shared/build/reports/kover/
just tcheck          # full pipeline: tests + assembleDebug + lint
```

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

## Adding a new module (e.g. mcp-server)

1. Add plugins to module's `build.gradle.kts`:
   ```kotlin
   alias(libs.plugins.detekt)
   alias(libs.plugins.kover)
   ```

2. Add detekt config:
   ```kotlin
   detekt {
       config.setFrom(rootProject.file("config/detekt/detekt.yml"))
       baseline = rootProject.file("config/detekt/baseline-mcpServer.xml")
       ignoreFailures = true
   }

   dependencies {
       detektPlugins(libs.detekt.formatting)  // ktlint
   }
   ```

3. Add kover config:
   ```kotlin
   kover {
       reports {
           total {
               xml { onCheck = true }
               html { onCheck = true }
           }
       }
   }
   ```

4. Create `config/detekt/baseline-mcpServer.xml` (copy from desktopApp template)

5. Update `.just/tests/mod.just` — add the new module to the recipes

6. Update this skill's just recipes table if you add new modules

## Promoting from report-only to fail-on-violation

When the codebase is clean enough to enforce violations:

1. Set `ignoreFailures = false` in both `shared/build.gradle.kts` and `desktopApp/build.gradle.kts`
2. Update `check.sh` to remove `|| echo` fallback:
   ```bash
   ./gradlew :shared:detekt :desktopApp:detekt --no-daemon
   ```
3. Commit as a separate PR with a note in the decision record (`docs/decisions/2026-09-15-detekt-ktlint-kover-setup.md` — `ignoreFailures` consequence note)

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

### Configuration cache errors
```
error writing value of type 'ExistingNamedDomainObjectProvider'
```
This is a known detekt 2.x + Gradle 9.x CC incompatibility. Use `--no-configuration-cache` flag or wait for a fix in a later detekt version.

### "detekt was compiled with Kotlin X but is currently running with Y"
You are running the wrong detekt version. Stable 1.23.x supports Kotlin 2.0.x. For Kotlin 2.3.x use **detekt 2.0.0-alpha.3** (compiled against Kotlin 2.3.21).

## Files modified by quality tools

| File pattern | What happens |
|---|---|
| `shared/src/**/build/reports/detekt/detekt.xml` | detekt XML report (CI artifact) |
| `shared/src/**/build/reports/kover/report.xml` | kover XML coverage report |
| Kotlin source files | `detekt --rerun-tasks` auto-fixes in-place |

## Related skills

- `singularity-todo-feature-scaffold` — includes lint checklist in new feature PRs
- `singularity-todo-clean-architecture-audit` — runs detekt as part of architecture audit
- `singularity-todo-kotlin-idioms` — covers Kotlin idioms that ktlint enforces
- `docs/decisions/2026-09-15-detekt-ktlint-kover-setup.md` — decision record with full rationale
