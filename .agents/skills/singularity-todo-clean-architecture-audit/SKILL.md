---
name: singularity-todo-clean-architecture-audit
description: 'Audit tool to verify that a feature follows clean architecture: presentation depends only on domain (via interfaces), data depends only on domain, no cross-layer imports. Use after creating or refactoring a feature to verify layer boundaries. Runs automated grep checks + manual checklist.'
---

# Clean Architecture Audit

Use after creating or refactoring a feature to verify layer boundaries are respected.

> **Automated since 2026-09-26:** layer rules 1–3, the Koog-import ban, the
> `java.io.File` ban, the `*RepositoryImpl`-only-in-DI rule and the `*Blocking`-method
> ban run as hard-failing Konsist tests in
> `shared/src/jvmTest/kotlin/com/singularity/todo/arch/ArchitectureTest.kt`
> (part of `:shared:jvmTest` → check.sh → CI). The grep checks below remain useful for
> pinpointing *which import* inside a single feature directory violates a rule when the
> Konsist test fails. Allowlist changes require an ADR update — see
> `docs/decisions/2026-09-26-konsist-architecture-tests.md`.

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

# 6. ViewModels use canonical 4-arg constructor (deps, state, scope, sharingStarted)
echo "=== Check: ViewModels use canonical pattern ==="
# Canonical: class Vm(deps, state, scope, sharingStarted) — scopeOverride MUST BE ABSENT
if grep -rn "scopeOverride" "$FEATURE_DIR/presentation/viewmodel/"; then
    echo "❌ FAIL: scopeOverride found — scopeOverride is FORBIDDEN, use AutoCloseableCoroutineScope pattern"
else
    echo "✅ PASS: no scopeOverride (canonical pattern)"
fi

# 7. NO static ProfileAwareCurrentUser access (Phase 12b invariant)
echo "=== Check: no static ProfileAwareCurrentUser access ==="
if grep -rn "ProfileAwareCurrentUser\.scopedUserId\|ProfileAwareCurrentUser\.current\|ProfileAwareCurrentUser\.instance\|ProfileAwareCurrentUser\.Companion" shared/src/ --include="*.kt" | grep -v Binary; then
    echo "❌ FAIL: static ProfileAwareCurrentUser access found (use constructor injection)"
else
    echo "✅ PASS"
fi

# 8. NO watchById().first() one-shot reads (Phase 12a anti-pattern)
echo "=== Check: no watchById().first() anti-pattern ==="
if grep -rn "watchById.*\.first()\|watchByIdForUser.*\.first()" "$FEATURE_DIR"; then
    echo "❌ FAIL: watchById().first() found (use getById() instead)"
else
    echo "✅ PASS"
fi

# 9. DAO mutations include userId in WHERE clause (Phase 12a invariant)
echo "=== Check: DAO mutations include userId ==="
# Find DAO @Query UPDATE/DELETE statements that lack 'user_id' guard
# Skip known-safe tables (syncOutbox, settings, etc.) and the new *ForUser variants
if grep -B1 "UPDATE.*SET\|DELETE FROM" shared/src/commonMain/kotlin/com/singularity/todo/core/database/Daos.kt | grep "suspend fun" | grep -v "ForUser\|syncOutbox\|settings\|app_state\|preferences\|llm_usage" | grep -v "WHERE.*user_id"; then
    echo "❌ FAIL: DAO mutation without user_id WHERE clause — see singularity-todo-repository-architecture"
else
    echo "✅ PASS"
fi
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
- [ ] Use cases injected as constructor params into VMs (not registered as factory/viewModel)
- [ ] `viewModelOf(::Vm)` for no-param VMs, `viewModel { (p) -> Vm(get(), p) }` for VMs with runtime parameters (NOT `factory {}` — memory leak)
- [ ] `CalendarSyncViewModel` uses `factory<>` (not `viewModelOf`) — it's NOT a ViewModel subclass
- [ ] `koinViewModel()` in Compose for no-param, `koinViewModel { parametersOf(p) }` for param VMs
- [ ] `koinInject()` only for non-ViewModel dependencies (repos, ports, services)
- [ ] No feature imports `presentation/` from another feature

### Code quality
- [ ] `collectAsStateWithLifecycle()` in all Screens
- [ ] Canonical 4-arg ViewModel constructor: `(deps, state, scope, sharingStarted)`
- [ ] `sealed UiState` with Loading/Content/Error in each ViewModel
- [ ] No `runBlocking` in ViewModel constructors
- [ ] `Either<AppError, T>` or `Result<T>` used for error returns (not exceptions)
- [ ] `just lint` reports 0 new violations (fix manually; auto-fix is broken — see ADR 2026-10-09)

### Repository auth-safety (Phase 12a — added 2026-09-24)
- [ ] All new DAO mutations include `userId: String` in WHERE clause AND return `Int`
- [ ] Repository impl propagates `currentUser.scopedUserId.value.value` into every DAO call
- [ ] `require(rows > 0)` enforces ownership in every DAO mutation
- [ ] No `dao.watchById(id).first()` for one-shot reads — use `dao.getById(id)`
- [ ] No static `ProfileAwareCurrentUser.scopedUserId` / `.current` / `.instance` (detekt enforces)

See `singularity-todo-repository-architecture` for full invariants.

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
cd /absolute/path/to/your/clone   # or just stay in the repo root
FEATURE="tasks"
./gradlew :shared:detekt --no-configuration-cache --no-daemon 2>&1 | tail -5
grep -rn "feature\.$FEATURE\.data\." "shared/src/commonMain/kotlin/com/singularity/todo/feature/$FEATURE/presentation/" && echo "❌ data imported in presentation" || echo "✅"
grep -rn "feature\.$FEATURE\.presentation" "shared/src/commonMain/kotlin/com/singularity/todo/feature/$FEATURE/domain/" && echo "❌ presentation imported in domain" || echo "✅"
grep -rn "collectAsState()" "shared/src/commonMain/kotlin/com/singularity/todo/feature/$FEATURE/presentation/" && echo "❌ collectAsState used" || echo "✅"
```

## Related Skills

- `singularity-todo-quality-tools` — detekt, ktlint, kover run commands and config format
- `singularity-todo-detekt-workflow` — auto-fix + baseline rebuild workflow
- `singularity-todo-feature-scaffold` — feature checklist with lint step
- `singularity-todo-kotlin-idioms` — Kotlin idioms that ktlint enforces
- `singularity-todo-repository-architecture` — DAO `*ForUser`, atomic bootstrap, no static `ProfileAwareCurrentUser`
- `singularity-todo-koin-dsl` — canonical Koin 4.x DSL (viewModelOf vs factory, koinViewModel vs koinInject)
- `singularity-todo-testable-vm` — canonical VM pattern: 4-arg constructor, no combine/stateIn
- `docs/decisions/2026-09-24-combine-statein-policy.md` — combine+stateIn policy reconciliation
