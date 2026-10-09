---
name: singularity-todo-detekt-workflow
description: 'Run detekt and ktlint in this KMP project: format, auto-fix, baseline rebuild, and the project''s custom rules. Use before committing or when a lint failure needs diagnosing. For rule authoring see singularity-todo-detekt-rules-authoring; for the full toolchain (ktlint, kover, coverage) see singularity-todo-quality-tools.'
---

> **When to use:** Any lint/formatting work, especially before committing, or when adding new code patterns.

## ⚠️ Auto-correct is inert (ADR 2026-10-09)

`--auto-correct` is **broken** in detekt 2.0.0-alpha.6 running through the Gradle plugin.
`just detekt-fix` **reports only** — it does not auto-fix anything. See:
- `ADR 2026-10-07-detekt-auto-correct-single-task`
- `ADR 2026-10-09-detekt-auto-correct-inert-multi-module`

**To fix violations today:** edit the files manually.

## Workflow

### 1. Report (before commit)

```bash
just detekt-fix
```

Reports all current detekt + ktlint violations. Does NOT auto-fix — violations must
be corrected manually.

```bash
just lint
# equivalent to:
./gw :shared:detekt :desktopApp:detekt :detekt-rules:detekt :androidApp:detekt :mcp-server:detekt
```

**Enforcing** — `ignoreFailures = false` in `shared/build.gradle.kts` and
`desktopApp/build.gradle.kts`. A violation fails the build; the baselines in
`config/detekt/` cover the accepted debt.

### 2. Fix manually

For each reported violation, open the file and fix it by hand. Common patterns:
- **Naming** (FunctionNaming, VariableNaming, etc.): rename
- **Style** (ArgumentListWrapping, ImportOrdering, etc.): reformat by hand
- **Complexity** (LongMethod, TooManyFunctions): split or suppress with `@Suppress`

### 3. Rebuild Baseline (capture accepted debt)

```bash
just detekt-baseline
# or
./gw :shared:detektBaseline :desktopApp:detektBaseline :androidApp:detektBaseline :mcp-server:detektBaseline
```

Captures **current** violations into `baseline-shared.xml` etc. Run after a large
manual-fix pass to freeze accepted debt.

### 4. Adding a new rule

1. Write `XxxRule.kt` + `XxxProvider` in `detekt-rules/src/main/kotlin/com/singularity/todo/detekt/`.
2. **Add the provider to `detekt-rules/src/main/resources/META-INF/services/dev.detekt.api.RuleSetProvider`.** A rule missing from this file never loads.
3. Add the `xxx:` block to `config/detekt/detekt.yml` with `active: true`.
4. Run `./gw :shared:detekt` and read the finding count.
   - **0 findings** → done, the rule is live and enforcing.
   - **> 0** → either fix them, or accept them into the baseline (`just detekt-baseline`)
     and treat the rule as advisory until the tree is clean.

See `singularity-todo-detekt-rules-authoring` for the PSI-level pitfalls.

## Configuration

- **shared**: `shared/build.gradle.kts` + `config/detekt/detekt.yml`
- **desktopApp**: `desktopApp/build.gradle.kts` + shared detekt config
- **mcp-server**: `mcp-server/build.gradle.kts` + `mcp-server/detekt-baseline.xml`
- **androidApp**: `androidApp/build.gradle.kts` + `androidApp/detekt-baseline.xml`

Ktlint rules are in the `ktlint:` section of `config/detekt/detekt.yml`. Key settings:
- `auto_correct: true` — **has no effect** (see ADR above)
- `indent: auto_correct: false` (manual fix needed due to IntelliJ NPE)
- `max-line-length: active: false` (`.editorconfig` owns `max_line_length = 140`)

## See also

- `singularity-todo-quality-tools` — Full quality toolchain overview (detekt + kover + ktlint)
- `ADR 2026-10-09-detekt-auto-correct-inert-multi-module`
