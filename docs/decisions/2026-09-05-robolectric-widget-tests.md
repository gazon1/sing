---
title: "Widget tests via Robolectric androidHostTest — no Koin, direct ViewModel construction"
date: 2026-09-05
tags: [testing, robolectric, koin, ui]
status: accepted
superseded-by: 2026-09-26-ui-testing-deferred
---

## Context

The `androidHostTest` (Robolectric) source set was failing to compile due to Koin setup errors: wrong `singleOf` API, missing imports, `TEST_USER_ID` not found. The instrumented `androidTest` (device) source set works but only runs smoke tests — Espresso cannot interact with JetBrains Compose views (different rendering pipeline). We needed real widget tests for Compose UI that run on JVM via Robolectric.

## Idea

Three approaches were considered:

1. **Koin in tests** — configure a full `testModule` with all-fake dependencies inside `androidHostTest`. Koin's `singleOf` / DSL API differs from production, and `koin-ksp-compiler` is incompatible with Koin 4.x — too many mismatches.

2. **Compose TestRule + manual DI** — create ViewModels directly with fake dependencies, no Koin at all. ViewModels are simple data classes; all dependencies are inject-able interfaces or concrete fakes.

3. **Ultron / Page Objects** — Ultron is a wrapper around Espresso/UI Automator, not Robolectric. Cannot help with JVM-based Compose widget testing.

## Decision

Skip Koin in `androidHostTest` tests. Create each ViewModel directly in test setup with concrete fake implementations. No `startKoin`, no `koin { modules(...) }`.

Pattern used:
```kotlin
private val fakeAuthRepo = FakeAuthRepository(Session.Anonymous(UserId.anonymous))
private val fakeCurrentUser = FakeCurrentUser(fakeAuthRepo)
private val htmlPort = object : MarkdownHtmlPort { ... }

val viewModel = AuthViewModel(fakeAuthRepo)
composeRule.setContent { LoginScreen(onSuccess = {}, onContinueOffline = {}, viewModel = viewModel) }
```

## Rationale

- **No Koin dependency mismatch** — test setup uses plain Kotlin constructor calls, always in sync with production signatures.
- **All fakes are in `commonTest`** — `FakeRepositories.kt`, `FakeCurrentUser.kt` are shared across all test source sets.
- **Robolectric runs on JVM** — `createComposeRule()` works with JetBrains Compose 1.11.1; no AndroidX Compose conflict.
- **Simpler setup** — no `testModule`, no `koin { }` DSL, no module merging issues.

## Consequences

- **JVM args for JDK 21+** — add `--add-opens=java.base/jdk.internal.access=ALL-UNNAMED` to `gradle.properties` (`org.gradle.jvmargs`) AND to `shared/build.gradle.kts` via `afterEvaluate` + `tasks.withType<Test>()` for the test worker process.
- **Robolectric 4.17-beta-4** — `4.16` maxes at SDK 36; `compileSdk=37` requires the beta. The beta is already cached.
- **Fake repo returns empty by default** — widget tests that check `LazyColumn` with `testTag` will fail when repo is empty (state = `Empty`). Test the `EmptyState` text instead, or seed data via `fakeNotesRepo.seed(note)`.
- **`waitForIdle()` is a method, not a function** — do NOT import it. Call `composeRule.waitForIdle()` directly.
- **`Session.Anonymous()` requires `UserId`** — always pass `UserId.anonymous` or `UserId.fromString("...")`.
- **Use `UserId` from `feature.tasks`** — it's defined in `Ids.kt` there, imported explicitly.
- **`Clock` must be passed to `CreateTaskUseCase` / `UpdateTaskUseCase`** — use the singleton `Clock` from `core.platform`.

## Links

- Commit: `feat(tests): add Robolectric widget tests for AuthViewModel, TasksScreen, NotesScreen`
- Files added: `AuthViewModelWidgetTest.kt`, `TasksScreenWidgetTest.kt`, `NotesScreenWidgetTest.kt`, `NoteEditorScreenWidgetTest.kt`
- `shared/src/androidHostTest/kotlin/com/singularity/todo/feature/{auth,tasks,notes}/`
- `gradle/libs.versions.toml` — `robolectric = "4.17-beta-4"`
- `gradle.properties` — `--add-opens` flags for JDK 21+
