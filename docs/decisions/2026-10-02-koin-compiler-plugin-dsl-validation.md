---
title: "Compile-time Koin DI graph validation via koin-compiler-plugin 1.2.1"
date: 2026-10-02
status: accepted
tags: [koin, di, compiler-plugin, kotlin]
---

## Context

`AndroidKoinGraphValidationTest` and `KoinGraphValidationTest` (JVM) were the
last line of defence for Koin DI integrity. They started a `koinApplication` with
the real module set and called `get<T>()` on a handful of types — a runtime smoke
test that could not catch every missing binding, only the ones exercised by that
specific test. The tests themselves had problems:

- `KoinGraphValidationTest` duplicated 55 lines of `platformModule()` as a hand-written
  mirror that could drift from production without the test failing.
- `AndroidKoinGraphValidationTest` required a manual `externalTypes` list of 16 DAO
  types; adding a Room DAO required editing 3 files (`PlatformModule.android`,
  `PlatformModule.jvm`, `externalTypes`) or the test silently passed.
- Neither ran in CI for the `mcp-server` module.
- The tests were excluded from normal unit-test runs (`jvmTest`, `androidHostTest`)
  and only executed as part of `check.sh` step 0 — meaning they degraded to
  infrastructure noise.

Meanwhile, the underlying problem (missing binding → process crash) was demonstrated
by [2026-09-29-missing-koin-dao-bindings](2026-09-29-missing-koin-dao-bindings.md).

## Decision

Adopt **koin-compiler-plugin 1.2.1** for compile-time DI graph validation. The
plugin runs as a Kotlin compiler plugin (K2) and validates all Koin DSL forms
against the full resolved graph at compile time.

**What is validated** (from plugin 1.2.0 release notes):

- Classic lambda DSL (`single { Foo(get()) }`) — every `get()` call inside a
  lambda is validated as a resolution site.
- Constructor shorthand (`singleOf(::T)`, `factoryOf`, `viewModelOf`) — the
  referenced constructor's parameters become real requirements.
- Reified DSL (`single<T>()`) from `org.koin.plugin.module.dsl` — placeholder
  replaced at compile time.
- `create(::function)` parameter validation.
- `bind<I>()` and `binds(...)` multi-binding.

**What is NOT validated** (documented limitations):

- Runtime-computed module sets (`modules(if (x) A else B)`,
  `modules(listOfModules.toTypedArray())`) produce KOIN-W003 instead of a guarantee.
  **Rule: never use `*list.toTypedArray()` in `modules()`; use list composition
  `listOf(...) + domainModule()` instead.**
- Cycle detection through classic lambda DSL (`single { Service(get()) }`) does not
  expose constructor relationships; a circular dependency through such a definition
  goes undetected. Declare with `single<T>()`, `singleOf(::T)` or `create(::T)`
  for cycle detection.
- Modules without an entry point (`startKoin`, `koinApplication`,
  `@KoinApplication`) are not verified in isolation — their definitions are
  validated only at the app-level compile step where the full graph is assembled.
  **This means `shared` is validated through `desktopApp`, `androidApp` and
  `mcp-server` compilation, not standalone.**

**Why not reified DSL (`single<T>()`)?**

The reified DSL functions (`single<T>()` from `org.koin.plugin.module.dsl`) are
placeholders that throw `NotImplementedError` at runtime if the compiler plugin
is not applied. The classic DSL (`single { Foo(get()) }`) has full runtime
behaviour without the plugin — it just lacks compile-time safety. Keeping the
classic DSL avoids a hard runtime dependency on the plugin being present everywhere.
We use classic DSL throughout; the plugin adds safety without requiring migration.

**Entry points validated in this project:**

| Module | Entry point | Verified |
|---|---|---|
| `androidApp` | `SingularityApp.onCreate` → `startKoin { modules(...) }` | ✅ strictSafety |
| `desktopApp` | `main.kt` → `startKoin { modules(...) }` | ✅ strictSafety |
| `mcp-server` | `Main.bootstrapKoin()` → `startKoin { modules(...) }` | ⚠️ KOIN-W003 (`platformModule(profileId)` is dynamic) |

## Rationale

Compile-time validation is strictly superior to runtime for this class of bug:
a missing binding becomes a **compiler error** rather than a device crash.
The plugin was evaluated via spike on `feat/koin-compiler-plugin`:

- `BUILD SUCCESSFUL` on `:shared:compileKotlinJvm`, `:desktopApp:compileKotlin`,
  `:androidApp:compileDebugKotlin` with Kotlin 2.3.21 (warning: proceeds with
  2.3.20 adapter; not a hard fail).
- Sabotage test confirmed KOIN-D003 fires on desktopApp and androidApp when a
  binding is removed; mcp-server produces W003 (acceptable, dynamic param).

## Consequences

### Changes made

1. **Build config**: `id("io.insert-koin.compiler.plugin") version "1.2.1"` applied
   to `:shared`, `:desktopApp`, `:androidApp`, `:mcp-server`. The plugin ID
   requires `id()` syntax (not `alias(libs.plugins.koinCompiler)`) because the
   hyphenated plugin ID fails with the version catalog's generated accessor.
2. **Entry points**: all three `startKoin { modules(...) }` calls refactored from
   `*domainModule().toTypedArray()` (KOIN-W003) to list composition
   `listOf(...) + domainModule() + listOf(...)`.
3. **Bug found and fixed during spike**:
   - `TaskDaoArchiveRepositoryImpl` did not implement `ArchiveRepository` interface —
     added `implements ArchiveRepository`, `override archiveCompletedTasks()`.
   - `PlatformModule.android` had no `CoroutineScope` binding — added
     `single<CoroutineScope> { createBackgroundScope() }`.
   - `AndroidPomodoroTaskListProvider` used `MainScope()` instead of
     `createBackgroundScope()` — fixed.
4. **Tests removed**:
   - `KoinGraphValidationTest.kt` (148 lines, JVM)
   - `AndroidKoinGraphValidationTest.kt` (130 lines, Robolectric)
5. **Dead dependencies removed**: `koin-test` from all 4 modules,
   `koin-annotations-runtime` from `shared` and `mcp-server`.
6. **check.sh**: added `:mcp-server:compileKotlin` as step 6/7.

### Known issues

- **KOIN-W003 in mcp-server**: `platformModule(profileId)` is dynamically computed
  (the `profileId` parameter is ignored in the current implementation; the
  `profileModule` is inlined into `domainModule()` as literal `module {}` blocks).
  The warning is acceptable — production MCP DI is static; the parameter is a
  no-op vestige of an earlier design.
- **Kotlin 2.3.21 compatibility warning**: plugin proceeds with 2.3.20 adapter.
  This is a soft warning, not a hard fail. No action needed unless compilation
  actually breaks.
- **KSP 2.3.11 vs Kotlin 2.3.21 mismatch**: KSP version does not track Kotlin
  version (KSP 2.3.11 for Kotlin 2.3.21). Room 3 KSP works (FROM-CACHED);
  non-Room KSP processors may silently skip. Tracked separately.
- **Cycle detection gap**: classic DSL does not support cycle detection. If a
  circular dependency is introduced through `single { Foo(get()) }` form, it will
  not be caught at compile time. Use `singleOf(::T)` for cycle-prone registrations.
- **Test harness `DesktopAppHarness`**: uses a runtime `overrides: Module = module {}`
  parameter loaded last into `koinApplication`. This produces a KOIN-W003 in
  test compilation — acceptable, documented, no action needed.

## Links

- Plugin releases: [1.2.0](https://github.com/InsertKoinIO/koin-compiler-plugin/releases/tag/1.2.0),
  [1.2.1](https://github.com/InsertKoinIO/koin-compiler-plugin/releases/tag/1.2.1)
- Official docs: <https://insert-koin.io/docs/setup/compiler-plugin/>
- ADR [2026-09-29-missing-koin-dao-bindings](2026-09-29-missing-koin-dao-bindings.md)
  — the incident that motivated runtime tests, now superseded
- Skill `singularity-todo-di-graph-testing` — now obsolete; replaced by compile-time validation
