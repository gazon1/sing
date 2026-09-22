---
name: singularity-todo-coroutine-scopes
description: Canonical patterns for CoroutineScope ownership in this KMP project — where scopes live, anti-patterns to avoid, and how to fix hanging tests caused by repository-owned scopes. Use when designing a new repository/wrapper that hosts stateIn, debugging tests that hang with UncompletedCoroutinesError, or when reviewing VM constructor changes.
---

# Coroutine Scope Placement in KMP

This skill documents where `CoroutineScope` instances should live in this project, the anti-patterns that cause hanging tests, and the canonical fix.

## Three distinct concepts (terminology)

These three terms are often conflated. Keep them separate:

| Concept | Lives in | Lifecycle |
|---|---|---|
| **Class-level singleton** | Koin `single { Class() }` | One instance per DI graph, lifetime = application |
| **Shared application scope** | **NOT used in this project** | Would be one scope shared by all consumers |
| **Per-instance background scope** | Created in constructor via `createBackgroundScope()` | Lifetime = lifetime of owning instance |

A class can be a **class-level singleton** (Koin `single`) AND own its own **per-instance background scope**. These are orthogonal.

## Where scopes live in KMP

| Layer | Scope | Lifetime | Created via |
|---|---|---|---|
| Application process | One per long-lived component (`createBackgroundScope()`) | App lifetime | Koin `single { ... createBackgroundScope() }` |
| Activity | `lifecycleScope` | Activity lifetime | Android framework |
| ViewModel | Primary ctor takes `scope: CoroutineScope` | VM lifetime | Koin `viewModel { ... }` via secondary ctor |
| Test | `TestScope` (the receiver of `runTest { }`) or `backgroundScope` | Test lifetime | `kotlinx-coroutines-test` |
| Repository/wrapper | `createBackgroundScope()` injected via constructor | App lifetime (if Koin `single`) | Koin `single { ... createBackgroundScope() }` |

**Repositories do NOT own a `CoroutineScope` field** — they receive one via constructor injection. See "Anti-pattern" below.

## Anti-pattern: repository-owned scope

```kotlin
// DON'T — this is the bug that caused hanging VM tests in 2026-09-17
class CurrentUser(authRepository: AuthRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val userId: StateFlow<UserId> = authRepository.session
        .map { ... }
        .stateIn(scope, SharingStarted.Eagerly, UserId.anonymous)
}
```

### Why this breaks tests

The `stateIn(scope, ...)` collector runs on `Dispatchers.Default` — a real thread pool outside the test dispatcher's `TestScheduler`. `advanceUntilIdle()` advances virtual time only inside the `TestScope`; the real `Default` dispatcher runs on its own scheduler. Test state never updates → `UncompletedCoroutinesError` after timeout.

### Real examples from this project (fixed in 2026-09-17)

- `CurrentUser.kt:24` — auth wrapper owning scope
- `ProfileAwareCurrentUser.kt:24` — profile wrapper owning scope
- `ProfileRepositoryImpl.kt:36` — data store + DAO owning scope

All three had the same root cause.

## Canonical pattern: mandatory scope param

```kotlin
// DO — scope is injected, never created internally
class CurrentUser(
    authRepository: AuthRepository,
    private val scope: CoroutineScope,
) {
    val userId: StateFlow<UserId> = authRepository.session
        .map { ... }
        .stateIn(scope, SharingStarted.Eagerly, UserId.anonymous)
}
```

**No default param.** The compiler forces every call site to pass a scope. Tests cannot accidentally get production `Dispatchers.Default`.

### DI registration

```kotlin
// core/di/CoreDiModule.kt
import com.singularity.todo.core.coroutines.createBackgroundScope

single { CurrentUser(get(), createBackgroundScope()) }
```

The `createBackgroundScope()` call is made **once** when Koin instantiates the singleton.

### `createBackgroundScope()` function

```kotlin
// shared/src/commonMain/kotlin/com/singularity/todo/core/coroutines/BackgroundScope.kt
expect fun createBackgroundScope(): CoroutineScope

// shared/src/{jvmMain,androidMain}/.../BackgroundScope.{jvm,android}.kt
actual fun createBackgroundScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default)
```

**Naming rationale:** `createBackgroundScope()` makes it obvious that each call returns a **new** scope. Not `backgroundScope()` (sounds like shared access) or `applicationScope()` (Android-specific lifecycle association).

**Why `Dispatchers.Default`:** available on all KMP targets (Android, JVM, Native, JS). `Main.immediate` requires UI dispatcher, not present on all targets.

## Lifecycle: honest formulation

The scope created in a constructor is **never explicitly cancelled**. No `close()` method, no `cancel()` call.

- **Koin `single` lifetime = application lifetime → scope lives until process death.**
- **Process death** (Android kills app, JVM exits) reaps the scope naturally.
- **This is NOT a leak** — but it is **an unmanaged lifecycle**. On long-running JVM targets (servers, desktop apps that don't exit), the scope accumulates work until the process exits. For now this trade-off is accepted; if iOS or long-running server targets are added, a `close()` method + lifecycle hook will be required.

**DO NOT** switch a class from Koin `single` to `factory` while using `createBackgroundScope()` — every factory binding creates a new instance with a new scope, none of which is cancelled. Use a consumer-provided scope instead.

## `SharingStarted.Eagerly` — intentional

All three classes (CurrentUser, ProfileAwareCurrentUser, ProfileRepositoryImpl) use `SharingStarted.Eagerly`. This is intentional: the state they host is **hot app-scoped state** (current userId, active profileId) that must be available immediately, never expires.

`WhileSubscribed` would defer upstream collection until a subscriber attaches — undesirable for these signals. Changing to `WhileSubscribed` requires a separate MR with semantic justification.

## Test pattern

### In jvmTest with `runTest`

```kotlin
@Test
fun example() = runTest {
    val vm = FooViewModel(
        deps = deps,
        // ...
        scope = backgroundScope,  // ← test scope, auto-cancelled at teardown
    )
    advanceUntilIdle()
    // assertions...
}
```

`backgroundScope` is the `TestScope.backgroundScope` property — designed for long-running helpers that should outlive the test body. For tests that subscribe to state flows, use `backgroundScope` to avoid `UncompletedCoroutinesError`.

### In commonTest (multiplatform) with direct constructor

`commonTest` doesn't have access to `runTest`'s `TestScope`. Direct constructor calls use `createBackgroundScope()`:

```kotlin
// commonTest/.../ReadToolsProfileAwareTest.kt
val currentUser = CurrentUser(auth, scope = createBackgroundScope())
```

This is acceptable **only** when the test reads `.value` synchronously and never subscribes to the `stateIn` flow. If the test does subscribe, it will hang — there is no `TestDispatcher` controlling `Dispatchers.Default`.

## Preview composables

Preview composables live in `commonMain` (not `commonTest`) because they need real Compose previews in Android Studio. They use Fake factories with `createBackgroundScope()`:

```kotlin
// commonMain/.../ProjectEditorScreen.kt:327
val fakeCurrentUser = FakeProfileAwareCurrentUser(
    fakeAuthRepo, fakeProfileRepo, scope = createBackgroundScope(),
)
```

This creates a new scope per preview render. In Android Studio, preview lifecycle is short — acceptable. **DO NOT** use direct constructor in preview composables — always go through Fake factories so the API is consistent.

## KMP considerations

- `Dispatchers.Default` is part of `kotlinx-coroutines-core` — available on Android, JVM, iOS, Native, JS.
- `expect/actual fun` pattern mirrors `core/platform/Clock.kt` (already in the project).
- No iOS target support yet — when iOS is added, add `iosMain/.../BackgroundScope.ios.kt` actual (probably identical to jvmMain/androidMain).

## When to use this skill

- Designing a new repository/wrapper that hosts `stateIn` — start here
- Debugging tests that hang with `UncompletedCoroutinesError` — start here
- Reviewing a PR that adds `CoroutineScope(SupervisorJob() + Dispatchers.Default)` — flag this anti-pattern
- Writing a new ViewModel constructor — see also `singularity-todo-testable-vm`
- Migrating an existing repository — see also `singularity-todo-vm-migration-playbook` (specifically the "When this playbook is NOT enough" section)

## Post-Phase-11-12: `ProfileAwareCurrentUser` is pure DI (no static singleton)

The companion `ProfileAwareCurrentUser.scopedUserId` / `.current` / `.instance` / `.setInstance()` were **removed** in PR12b. The class is now purely DI-injected. The detekt rule `NoStaticProfileAwareCurrentUser` enforces this.

```kotlin
// ✅ Right — receive via constructor
class CreateTaskTool(
    private val taskRepository: TaskRepository,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,  // injected
)

// ❌ Wrong — static singleton access (detekt blocks this)
class CreateTaskTool(...) {
    val userId = ProfileAwareCurrentUser.scopedUserId.value
}
```

`ProfileAwareCurrentUser` itself **still owns its own `CoroutineScope`** (created via `createBackgroundScope()` in its constructor) — that is the legitimate owner-of-scope pattern documented above. The change in PR12b was about the **static accessor**, not the scope ownership.

For full invariants see `singularity-todo-repository-architecture`.

## See also

- `singularity-todo-testable-vm` — VM constructor pattern (similar scope injection)
- `singularity-todo-vm-migration-playbook` — migration steps; check "When NOT enough" before applying
- `singularity-todo-feature-scaffold` — checklist includes "no repository-owned scope"
- `docs/decisions/2026-09-17-vm-testability-audit.md` — root cause analysis that motivated this skill
