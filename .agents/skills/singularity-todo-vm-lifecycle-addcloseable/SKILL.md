# singularity-todo-vm-lifecycle-addcloseable

## Purpose

Migrate a ViewModel from manual `override fun onCleared() { scope.cancel() }` to
`AutoCloseableCoroutineScope` + `ViewModel.addCloseable()` (lifecycle 2.8+).

Use this skill when:
- Creating a **new** Tier-1 ViewModel (only cleanup is scope cancellation)
- Reviewing a PR that adds a new VM with manual `onCleared()`
- Auditing existing VMs for missing cleanup

---

## Key Facts

- **`AutoCloseableCoroutineScope`** at `core/coroutines/AutoCloseableCoroutineScope.kt`
  - implements `kotlin.AutoCloseable` + `kotlinx.coroutines.CoroutineScope`
  - `close()` calls `cancel()` on the coroutine context
  - companion `invoke()` creates one backed by `createBackgroundScope()`
- **`testScope(scope)`** at `core/coroutines/testScope.kt` wraps a `CoroutineScope`
  (e.g. `TestScope`) in `AutoCloseableCoroutineScope` for use in test factories
- **Lifecycle 2.8+ required** — project uses `2.9.4` ✓
- **`kotlin.AutoCloseable`** (Kotlin 2.0 commonMain expect interface) is **NOT**
  `java.io.Closeable` — they are different types in KMP commonMain
- Pattern avoids `ViewModel(scope)` constructor ambiguity by using `init { addCloseable(scope) }`

---

## Migration Procedure

### Step 1 — Identify Tier

Read the VM's `onCleared()` body:

```kotlin
override fun onCleared() {
    scope.cancel()              // Tier-1 → migrate
    super.onCleared()
}
```

```kotlin
override fun onCleared() {
    scope.cancel()              // Tier-1
    someOtherCleanup()         // Tier-2 → keep onCleared
    super.onCleared()
}
```

**Tier-1** (only `scope.cancel()`): migrate.
**Tier-2** (extra cleanup beyond cancellation): keep `onCleared()`.

### Step 2 — Migrate Tier-1 VM

**In the VM file:**

1. Add import:
   ```kotlin
   import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
   ```

2. Change constructor parameter:
   ```kotlin
   // Before
   private val scope: CoroutineScope,
   
   // After
   private val scope: AutoCloseableCoroutineScope,
   ```

3. Add `init` block after constructor parameters:
   ```kotlin
   ) : ViewModel() {
       init {
           addCloseable(scope)
       }
   ```

4. Update production secondary constructor:
   ```kotlin
   // Before
   scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),

   // After
   scope = AutoCloseableCoroutineScope(),
   ```

5. Remove `override fun onCleared() { scope.cancel(); super.onCleared() }`

6. Remove unused imports: `kotlinx.coroutines.CoroutineScope`,
   `kotlinx.coroutines.Dispatchers`, `kotlinx.coroutines.SupervisorJob`,
   `kotlinx.coroutines.cancel`. **Keep** `Dispatchers` if any `Dispatchers.X`
   is still used in the file.

**Full before/after:**

```kotlin
// BEFORE
class MyViewModel(
    private val deps: MyDeps,
    private val scope: CoroutineScope,
) : ViewModel() {
    constructor(deps: MyDeps) : this(deps, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    ...
    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }
}

// AFTER
class MyViewModel(
    private val deps: MyDeps,
    private val scope: AutoCloseableCoroutineScope,
) : ViewModel() {
    init { addCloseable(scope) }
    constructor(deps: MyDeps) : this(deps, AutoCloseableCoroutineScope())
    ...
    // onCleared removed
}
```

### Step 3 — Update Test Factory

If the VM has a test file, find the `createVm` / factory function:

```kotlin
// Before (test)
return MyViewModel(deps, ..., scope = scope)

// After (test)
return MyViewModel(deps, ..., scope = testScope(scope))
```

Add import if not already present:
```kotlin
import com.singularity.todo.core.coroutines.testScope
```

**Why not pass `TestScope` directly?** `TestScope` does not implement
`kotlin.AutoCloseable` — `testScope()` wraps it.

### Step 4 — Verify

```bash
./gradlew :shared:compileKotlinJvm
./gradlew :shared:jvmTest
```

---

## Common Mistakes

### `java.io.Closeable` instead of `kotlin.AutoCloseable`

```kotlin
// WRONG — will cause ClassCastException or compilation failure
import java.io.Closeable

// CORRECT
// (no import needed — AutoCloseableCoroutineScope already implements kotlin.AutoCloseable)
```

`kotlin.AutoCloseable` (Kotlin 2.0 commonMain) and `java.io.Closeable` (Java)
are incompatible type hierarchies in KMP. Always use `kotlin.AutoCloseable`.

### `kotlinx.coroutines.CoroutineContext` in expect/actual

```kotlin
// WRONG — kotlinx.coroutines.CoroutineContext is a type alias, can't use in expect
expect fun foo(): kotlinx.coroutines.CoroutineContext

// CORRECT
expect fun foo(): kotlin.coroutines.CoroutineContext
```

### Constructor ambiguity

`ViewModel(scope)` where `scope: AutoCloseableCoroutineScope` hits ambiguity between
`ViewModel(CoroutineScope)` and `ViewModel(AutoCloseable...)`. **Always use**
`init { addCloseable(scope) }` instead of passing scope to the constructor.

### Scope leak (Tier-1 but no `onCleared`)

Some VMs have `private val scope: CoroutineScope` but **no `onCleared()` override**.
This is a real leak — the scope is never cancelled. Treat it the same as Tier-1:
add `init { addCloseable(scope) }` and remove the field (or change type to
`AutoCloseableCoroutineScope`).

---

## Leaked Scopes Found (historical)

| VM | Issue | Fix |
|---|---|---|
| `NotePreview` | `scope: CoroutineScope`, no `onCleared()` | `addCloseable(scope)` |
| `NoteEditor` | `scope: CoroutineScope`, no `onCleared()` | `addCloseable(scope)` |

---

## Exemptions

`AccountSettingsViewModel` — no coroutine scope, pure passthrough VM. No migration needed.

---

## ADR Reference

- [2026-09-21-auto-closeable-coroutine-scope](../decisions/2026-09-21-auto-closeable-coroutine-scope.md) — full decision record
- [2026-09-18-vm-scope-cancellation-oncleared](../decisions/2026-09-18-vm-scope-cancellation-oncleared.md) — superseded original ADR
