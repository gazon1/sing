---
name: singularity-todo-di-graph-testing
description: Validate Koin DI graph at compile time via koin-compiler-plugin. Use when adding new repositories, ViewModels, AI tools, or platform ports to the DI graph, or after any KOIN-D003 or KOIN-W003 error. Replaces the old runtime checkModules() approach. Version sourced from `koin-compiler-plugin` in libs.versions.toml; applied via `alias(libs.plugins.koin.compiler)`.
---

# Singularity TODO — DI Graph Validation

**Problem this prevents:** Koin `NoDefinitionFoundException` surfaced at runtime when a
Composable first tried to resolve a missing type — on the user's device. The old
runtime `checkModules()` tests had a 55-line hand-written platform module mirror that
could drift, required a manual `externalTypes` list of 16 DAOs, and were not run in CI
for `mcp-server`. The koin-compiler-plugin now catches missing bindings at **compile
time** as a KOIN-D003 error.

## How it works

The [koin-compiler-plugin](https://insert-koin.io/docs/setup/compiler-plugin/)
(applied via `alias(libs.plugins.koin.compiler)`, version from `koin-compiler-plugin` in libs.versions.toml) runs as a Kotlin compiler
plugin (K2). At every build of an app module, it:

1. Detects the `startKoin { modules(...) }` entry point.
2. Assembles the full resolved graph from all visible `shared` definitions (via Gradle
   classpath hints).
3. Validates every `get<T>()` inside every `single { }`, `factory { }`, `viewModel { }`
   block — plus `singleOf(::T)`, `factoryOf`, `viewModelOf` parameter chains.
4. Fails the compilation with a KOIN-D003 error naming the missing type and location.

## Entry points validated

| Module | Entry point | Status |
|---|---|---|
| `androidApp` | `SingularityApp.onCreate` → `startKoin { modules(...) }` | ✅ strictSafety |
| `desktopApp` | `main.kt` → `startKoin { modules(...) }` | ✅ strictSafety |
| `mcp-server` | `Main.bootstrapKoin()` → `startKoin { modules(...) }` | ⚠️ KOIN-W003 (`platformModule(profileId)` is dynamic) |

## KOIN error and warning codes

| Code | Meaning | Action |
|---|---|---|
| **KOIN-D003** | Missing definition: a `get<T>()` call has no binding for `T` | Add the missing binding in the appropriate `*DiModule.kt` |
| **KOIN-W003** | Graph not verifiable: module set is runtime-computed (spread, conditional, variable) | Use list composition `listOf(...) + domainModule()` instead of `*domainModule().toTypedArray()` |
| **KOIN-D004** | Cycle detected through reified DSL (`single<T>()`) | Break the cycle or declare through `singleOf(::T)` |

## Entry point rule

**Never use `*domainModule().toTypedArray()` in `modules()`.** This produces
KOIN-W003. Use list composition instead:

```kotlin
// ❌ KOIN-W003 — spread of runtime list
modules(platformModule(), *domainModule().toTypedArray(), gateModule(url))

// ✅ KOIN-W003-free — stable list composition
modules(listOf(platformModule(), coreLoggingModule()) + domainModule() + listOf(gateModule(url)))
```

The `domainModule()` function returns `List<Module>`. The spread operator converts it to a
vararg, which the plugin cannot resolve at compile time.

## New Room DAO checklist (only 2 places now)

A new `abstract fun xDao(): XDao` on `AppDatabase` compiles and passes CI, but crashes
with `NoDefinitionFoundException` the first time a screen resolves the repository that
needs it. Three DAOs shipped that way (`TagGroupDao`, `ProjectInheritedTagGroupDao`,
`SavedSearchDao`) — see `docs/decisions/2026-09-29-missing-koin-dao-bindings.md`.

With the compiler plugin, the manual `externalTypes` list is **gone** — the plugin
detects all `get<AppDatabase>().xDao()` calls automatically. Only two files need
editing:

1. **`PlatformModule.android.kt`** — add `single { get<AppDatabase>().xDao() }`
2. **`PlatformModule.jvm.kt`** — add the same, unless the DAO is genuinely Android-only
   (e.g. `CalendarSyncTaskMapDao`; JVM uses `NoopCalendarProvider`)

**No third file.** The plugin resolves the full graph per target, so `get<AppDatabase>().xDao()`
in `androidMain` is verified against `PlatformModule.android` and the JVM equivalent against
`PlatformModule.jvm`.

## New binding checklist

When adding a new repository, use case, or ViewModel:

1. Register it in the appropriate `*DiModule.kt` (e.g. `TasksDiModule.kt`).
2. If it is a **platform-specific** implementation (Android-only, JVM-only), add the
   binding to the appropriate `PlatformModule.{android,jvm}.kt` — not commonMain.
3. If the new type is an **interface** with a concrete implementation, bind the interface:
   `single<Port> { Impl(get(), get()) }` — not `singleOf(::Impl)`, unless `Impl`
   already implements `Port`.
4. If a class needs a platform-specific dep (e.g. `Context` on Android), use
   `get<Context>()` inside the factory lambda — the plugin resolves it through
   the `androidContext()` call in `SingularityApp`.

## AI tools and PromptExecutor

AI tools registered via `aiToolsModule()` may depend on `PromptExecutor` from Koog.
Koog is JVM-only — on Android the dependency must be nullable:

```kotlin
// commonMain — nullable so Android builds without Koog
class MyViewModel(
    private val promptExecutor: PromptExecutor? = null,
    // ...
)
```

The JVM `aiToolsModule` binds the real executor; the Android one binds `null`.
The compiler plugin validates the JVM graph; Android gets a null at runtime.

## KOIN-W003 acceptable locations

`mcp-server` uses `modules(listOf(platformModule(profileId)) + domainModule())`.
`platformModule(profileId)` is a function that takes a `profileId: String?` — the
plugin cannot resolve a runtime parameter at compile time, so it produces KOIN-W003.
This is **acceptable**: the function body is static (`profileId` is ignored), and
the graph is fully static. The warning documents a known limitation; it does not
mean the graph is broken.

## Anti-patterns

- **Don't use `singleOf(::Impl)` when `Impl` implements an interface** — it registers
  the concrete type, not the interface. Use `single<Port> { Impl(get(), get()) }`.
- **Don't use `MainScope()` on Android** — use `createBackgroundScope()`. `MainScope()`
  is Android-main-thread-specific and will fail in non-Android contexts.
  `createBackgroundScope()` is the platform-expected scope via `expect/actual`.
- **Don't bind `CoroutineScope` via `MainScope()`** — `MainScope()` creates a scope
  tied to `Dispatchers.Main`, which is not available in all contexts. Use
  `single<CoroutineScope> { createBackgroundScope() }`.

## Bug found during plugin adoption (MR-0)

The plugin revealed a latent bug during spike: `TaskDaoArchiveRepositoryImpl` did **not**
implement the `ArchiveRepository` interface it was supposed to. `singleOf(::TaskDaoArchiveRepositoryImpl)`
registered the concrete class, but `ArchiveViewModel` asked for `ArchiveRepository` via
`get()`. Fixed by adding `: ArchiveRepository` to the class declaration and `override`
to `archiveCompletedTasks()`.

This is exactly the class of bug the plugin is designed to catch.

## Files

| File | Role |
|---|---|
| `gradle/libs.versions.toml` | `koin-compiler-plugin = "1.2.1"` (aliased as `koin-compiler`) |
| `shared/build.gradle.kts` | `alias(libs.plugins.koin.compiler)` |
| `shared/src/commonMain/.../core/di/Modules.kt` | `domainModule()` |
| `shared/src/androidMain/.../core/di/PlatformModule.android.kt` | Android bindings |
| `shared/src/jvmMain/.../core/di/PlatformModule.jvm.kt` | JVM bindings |
| `androidApp/.../SingularityApp.kt` | Android entry point |
| `desktopApp/.../main.kt` | Desktop entry point |
| `mcp-server/.../Main.kt` | MCP entry point |

See ADR `docs/decisions/2026-10-02-koin-compiler-plugin-dsl-validation.md` for full
decision history including the KOIN-W003 limitation.
