---
name: singularity-todo-clean-architecture-audit
description: Audit tool to verify that a feature follows clean architecture: presentation depends only on domain (via interfaces), data depends only on domain, no cross-layer imports. Use after creating or refactoring a feature to verify layer boundaries. Runs automated grep checks + manual checklist.
---

# Clean Architecture Audit

Use after creating or refactoring a feature to verify layer boundaries are respected.

## What it checks

```
presentation → domain ← data

presentation/ only imports from: domain/model/, domain/port/, domain/usecase/, core/
data/ only imports from: domain/, core/, platform/
domain/ only imports from: core/, platform/

AgendaEngine layer rule:
feature/agenda/domain/logic/ (pure functions) may be imported from domain/model/ types.
feature/agenda/domain/model/ (data classes + sealed interfaces) has no lower-layer imports.
```

## Automated checks (run in feature directory)

```bash
FEATURE_DIR="shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks"

# 0. Run detekt on the module (fastest way to catch many issues)
echo "=== Run detekt ==="
./gradlew :shared:detekt --no-configuration-cache --no-daemon 2>&1 | tail -5
# Expected: "0 number of total findings" when baseline is clean
# New violations will show with file:line:column

# 1. Presentation must NOT import data implementations
echo "=== Check: presentation does NOT import data ==="
if grep -rn "feature\..*\.data\." "$FEATURE_DIR/presentation/"; then
    echo "❌ FAIL: presentation imports data layer"
else
    echo "✅ PASS"
fi

# 2. Domain must NOT import presentation
echo "=== Check: domain does NOT import presentation ==="
if grep -rn "feature\..*\.presentation" "$FEATURE_DIR/domain/"; then
    echo "❌ FAIL: domain imports presentation"
else
    echo "✅ PASS"
fi

# 3. Domain must NOT import data implementations
echo "=== Check: domain does NOT import data ==="
if grep -rn "feature\..*\.data\." "$FEATURE_DIR/domain/"; then
    echo "❌ FAIL: domain imports data"
else
    echo "✅ PASS"
fi

# 4. Data must NOT import presentation
echo "=== Check: data does NOT import presentation ==="
if grep -rn "feature\..*\.presentation" "$FEATURE_DIR/data/"; then
    echo "❌ FAIL: data imports presentation"
else
    echo "✅ PASS"
fi

# 5. Use collectAsStateWithLifecycle (not collectAsState)
echo "=== Check: uses collectAsStateWithLifecycle ==="
if grep -rn "collectAsState()" "$FEATURE_DIR/presentation/"; then
    echo "❌ FAIL: uses collectAsState() instead of collectAsStateWithLifecycle()"
else
    echo "✅ PASS"
fi

# 6. ViewModels have scopeOverride for tests
echo "=== Check: ViewModels have scopeOverride ==="
grep -rn "scopeOverride" "$FEATURE_DIR/presentation/viewmodel/" || echo "⚠️  WARNING: no scopeOverride found"
```

## Manual checklist

After running automated checks, verify manually:

### Layer dependencies
- [ ] `presentation/viewmodel/` imports only `domain/port/` interfaces (never `data/` implementations)
- [ ] `presentation/screen/` imports only `domain/model/` types
- [ ] `domain/port/` contains only interfaces (no implementations)
- [ ] `domain/usecase/` depends only on `domain/port/` interfaces (not on `data/`)
- [ ] `data/` imports only `core/` + `domain/` (never `presentation/`)
- [ ] Cross-feature imports go through `domain/port/` interfaces (not through feature impl)

### Naming
- [ ] Files in `domain/model/` named by concept, not role (e.g. `Task.kt`, not `TaskModel.kt`)
- [ ] Files in `presentation/viewmodel/` named by screen, not `*ViewModel` suffix
- [ ] State-bearers in `domain/model/` have `*State` suffix
- [ ] `usecase/` files named by action, not `*UseCase` suffix

### DI bindings (in `core/di/`)
- [ ] `single<Repo>` uses interface from `domain/port/`, impl from `data/`
- [ ] `factory { Create... }` from `domain/usecase/`
- [ ] `viewModel { }` or `viewModelOf()` from `presentation/viewmodel/`
- [ ] No feature imports `presentation/` from another feature

### Code quality
- [ ] `collectAsStateWithLifecycle()` in all Screens
- [ ] `scopeOverride: CoroutineScope? = null` in all ViewModels
- [ ] `sealed UiState` with Loading/Content/Error in each ViewModel
- [ ] No `runBlocking` in ViewModel constructors
- [ ] `Either<AppError, T>` or `Result<T>` used for error returns (not exceptions)
- [ ] `just lint` reports 0 new violations (run `just detekt-fix` to auto-fix first)

## How to fix common failures

### "presentation imports data"
```kotlin
// ❌ Wrong — presentation imports TaskRepositoryImpl
import com.singularity.todo.feature.tasks.TaskRepositoryImpl

// ✅ Right — presentation imports interface
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
```

### "domain imports data"
```kotlin
// ❌ Wrong — domain imports Room DAO
import com.singularity.todo.core.database.TaskDao

// ✅ Right — domain only defines interface, Room DAO is in data/
// The impl in data/ imports TaskDao, not domain/
```

### "uses collectAsState()"
```kotlin
// ❌ Wrong
val state by viewModel.state.collectAsState()

// ✅ Right
val state by viewModel.state.collectAsStateWithLifecycle()
```

## Quick audit one-liner

```bash
# Run all checks for a feature
cd /home/max/AndroidStudioProjects/singularity_cllone_kmp
FEATURE="tasks"
./gradlew :shared:detekt --no-configuration-cache --no-daemon 2>&1 | tail -5
grep -rn "feature\.$FEATURE\.data\." "shared/src/commonMain/kotlin/com/singularity/todo/feature/$FEATURE/presentation/" && echo "❌ data imported in presentation" || echo "✅"
grep -rn "feature\.$FEATURE\.presentation" "shared/src/commonMain/kotlin/com/singularity/todo/feature/$FEATURE/domain/" && echo "❌ presentation imported in domain" || echo "✅"
grep -rn "collectAsState()" "shared/src/commonMain/kotlin/com/singularity/todo/feature/$FEATURE/presentation/" && echo "❌ collectAsState used" || echo "✅"
```

## Related Skills

- `singularity-todo-quality-tools` — detekt, ktlint, kover run commands and config format
- `singularity-todo-feature-scaffold` — feature checklist with lint step
- `singularity-todo-kotlin-idioms` — Kotlin idioms that ktlint enforces
