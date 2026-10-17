---
name: singularity-todo-koin-defaults
description: Use when fixing, auditing, or registering a new Koin binding (singleOf/factoryOf/viewModelOf) and the target class has constructor parameters with Kotlin default arguments. Covers detection via SingleOfKotlinDefaultsTest, the fix (explicit lambda), and the allowlist mechanism.
---

# Koin `*Of` ignores Kotlin default arguments

**The problem:** `singleOf(::Class)`, `factoryOf(::Class)`, and `viewModelOf(::Class)`
resolve constructors **reflectively** and do not apply Kotlin's default argument values.
Every constructor parameter must be resolvable from the Koin graph, whether or not it has a
default. A parameter with a default that is not in the graph (e.g. `baseDelayMs: Long =
30_000L`) causes `NoDefinitionFoundException` at runtime, far from the line that is wrong.

**Root cause:** Koin's `*Of` functions use a `KClass` reference and reconstruct the
constructor via reflection. Kotlin defaults are a compile-time language feature; reflection
operates at runtime on the JVM and sees only the raw parameter list.

**Compiler plugin blind spot:** `koin-compiler-plugin` validates the DI graph at compile
time (KOIN-D003), but the constructor reference `::Class` is well-typed, so the plugin
sees every parameter as satisfied by the reference itself. The missing definition only
appears when the reference is reflectively expanded at resolution time.

## Detection: `SingleOfKotlinDefaultsTest`

`SingleOfKotlinDefaultsTest` in `shared/src/jvmTest/kotlin/com/singularity/todo/arch/`
parses every `singleOf`/`factoryOf`/`viewModelOf` call site, resolves the target class's
primary constructor, and checks for Kotlin defaults. A test failure names the file, line,
and the offending parameter.

```bash
./gradlew :shared:jvmTest --tests "com.singularity.todo.arch.SingleOfKotlinDefaultsTest"
```

## The fix: explicit lambda

```kotlin
// ❌ singleOf — Kotlin defaults are ignored by Koin's reflection
singleOf(::PatchRetryPolicy)

// ✅ single { } — Kotlin constructor is called, defaults are applied
single { PatchRetryPolicy() }
```

For `viewModelOf` with Kotlin defaults, use `viewModel { }` explicitly:

```kotlin
// ❌ viewModelOf — fails to apply crashReporter and scope defaults
viewModelOf(::AccountSettingsViewModel)

// ✅ viewModel { } — Kotlin constructor called with all defaults
viewModel { AccountSettingsViewModel(get(), get()) }
```

## Allowlist mechanism

`SingleOfKotlinDefaultsTest` has an `ALLOWLIST` of class names that are verified safe
by hand inspection. These are either:
- **No-arg constructors** — `SavedAgendaSeedStore`, `StubAttachmentUploadService`,
  `StubRemoteBackupService` (test fakes with no-arg constructors).
- **False positives** — class name appearing in a comment (e.g. `Impl` in an enum).

To add an allowlist entry, add a comment explaining why the match is a false positive
or why the default is acceptable:

```kotlin
private val ALLOWLIST = setOf(
    // SavedAgendaSeedStore has a no-arg constructor used in tests.
    "SavedAgendaSeedStore",
    // Impl is a value in an enum, not a class constructor call.
    "Impl",
)
```

## When to use `*Of` vs explicit lambda

| Form | Use when |
|---|---|
| `singleOf(::Class)` | Class constructor has **no** Kotlin defaults AND implements exactly one interface (or none). |
| `factoryOf(::Class)` | Same as `singleOf` for factory-scoped bindings. |
| `viewModelOf(::Class)` | ViewModel constructor has **no** Kotlin defaults. |
| `single { Class() }` | Class has Kotlin defaults, or needs named arguments. |
| `viewModel { Class(get(), get()) }` | ViewModel has Kotlin defaults, or multiple constructors. |

## Real defect found by the sweep

`PatchRetryPolicy` in `SyncDiModule.kt`:
```kotlin
open class PatchRetryPolicy(
    private val baseDelayMs: Long = 30_000L,   // no Long in the graph
    private val maxDelayMs: Long = 60 * 60 * 1000L,
    val maxAttempts: Int = 10,
)
```
`singleOf(::PatchRetryPolicy)` broke every test that resolved `AgendaViewModel`, because
the resolution chain `AgendaViewModel → TaskRepository → SyncRepository → SyncEngine →
PatchRetryPolicy` failed at the last step. Fixed by switching to `single { PatchRetryPolicy() }`.

## Files

| File | Role |
|---|---|
| `shared/src/jvmTest/kotlin/com/singularity/todo/arch/SingleOfKotlinDefaultsTest.kt` | Arch test |
| `shared/src/commonMain/kotlin/com/singularity/todo/core/di/SyncDiModule.kt` | Bug location |
| `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncOutbox.kt:152` | `PatchRetryPolicy` class |
| `shared/src/commonMain/kotlin/com/singularity/todo/core/di/Modules.kt` | Two latent `viewModelOf` cases fixed here |
| `docs/decisions/2026-10-16-koin-singleof-ignores-kotlin-defaults.md` | Full ADR |

## Links

- ADR `docs/decisions/2026-10-16-koin-singleof-ignores-kotlin-defaults.md`
- Skill `singularity-todo-di-graph-testing` — KOIN-D003 compile-time validation
- Skill `singularity-todo-koin-dsl` — canonical Koin 4.x patterns
