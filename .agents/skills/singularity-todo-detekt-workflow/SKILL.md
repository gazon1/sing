---
name: singularity-todo-detekt-workflow
description: Run detekt and ktlint in this KMP project: format, auto-fix, baseline rebuild, and the project's custom rules (PassThroughUseCase, NoStateIn, NoRunBlocking, NoViewModelScopeInProduction, NoRealDelayInTest, KDoc enforcement). Use before committing or when a lint failure needs diagnosing. For rule authoring see singularity-todo-detekt-rules-authoring; for the full toolchain (ktlint, kover, coverage) see singularity-todo-quality-tools.
---

> **When to use:** Any lint/formatting work, especially before committing, or when adding new code patterns.

## Workflow

### 1. Format + Fix (before commit)

```bash
just detekt-fix
```

This runs `./gradlew :shared:detekt :desktopApp:detekt --auto-correct`. The `--auto-correct` flag is **required** — without it, ktlint only reports violations without fixing them.

> **Note:** `just detekt-fix` recipe was fixed in PR 1.4 to include `--auto-correct`. If working on a pre-1.4 branch, run the gradle command directly.

### 2. Verify (after fix)

```bash
just detekt
# or
./gradlew :shared:detekt :desktopApp:detekt
```

Report-only mode. `ignoreFailures=true` in all modules, so this never fails the build.

### 3. Rebuild Baseline (after formatting pass)

```bash
just detekt-baseline
# or
./gradlew :shared:detektBaseline :desktopApp:detektBaseline
```

Captures **current** violations into `baseline-shared.xml` and `baseline-desktopApp.xml`. Run after a large auto-fix pass to freeze the baseline.

### 4. Promote to Strict Mode (follow-up PR)

After enough violations are fixed manually, remove `ignoreFailures = true` from module's `build.gradle.kts`:

```kotlin
detekt {
    buildUponDefaultConfig = true
    // ignoreFailures = true  // REMOVE this line
    // violations now fail the build
}
```

## Violation Categories

### Auto-fixable (via `detekt-fix`)

| Rule | Count | Type |
|------|-------|------|
| FunctionNaming | ~394 | naming |
| FunctionSignature | ~146 | style |
| ArgumentListWrapping | ~77 | style |
| Wrapping | ~64 | style |
| MaximumLineLength | ~64 | style |
| StatementWrapping | ~48 | style |
| ClassSignature | ~47 | style |
| ImportOrdering | ~33 | style |

### Manual fixes required

| Rule | Count | Type |
|------|-------|------|
| NoUnusedImports | 41 | naming |
| BackingPropertyNaming | 40 | naming |
| LongMethod | 34 | complexity |
| UnusedParameter | 30 | naming |
| TooManyFunctions | 13 | complexity |
| CyclomaticComplexMethod | 12 | complexity |

### Known categories (deferred)

- Pure read-through VMs (combine+stateIn pattern)
- Legacy code awaiting per-feature split
- Known tech debt items tracked in plan

## Configuration

- **shared**: `shared/build.gradle.kts` + `config/detekt/detekt.yml`
- **desktopApp**: `desktopApp/build.gradle.kts` + shared detekt config
- **mcp-server**: `mcp-server/build.gradle.kts` + `mcp-server/detekt-baseline.xml`
- **androidApp**: `androidApp/build.gradle.kts` (detekt added in PR 1.4)

Ktlint rules are in the `ktlint:` section of `config/detekt/detekt.yml`. Key settings:
- `auto_correct: true` for most rules
- `indent: auto_correct: false` (manual fix needed due to IntelliJ NPE)
- `max-line-length: active: false` (`.editorconfig` owns `max_line_length = 140`)

## Testing auto-fix

After running `detekt-fix`, verify compilation is not broken:

```bash
./gradlew :shared:compileKotlinJvm :shared:compileTestKotlinJvm
```

Then run tests for affected modules:
```bash
./gradlew :shared:jvmTest
```

## See also

- `singularity-todo-quality-tools` — Full quality toolchain overview (detekt + kover + ktlint)
