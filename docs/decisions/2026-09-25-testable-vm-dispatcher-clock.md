---
title: "Testable VMs — CoroutineDispatcher injection, Clock in DI, RecordingHttpClient"
date: 2026-09-25
tags: [testing, coroutines, viewmodel, koin, di]
status: accepted
---

## Context

Post-audit findings (2026-09-25) identified three categories of production code that actively harm testability:

### Category A: Hardcoded dispatchers in fakes

`FakeProfileAwareCurrentUser` (in `FakeRepositories.kt`) defaults its `CoroutineScope` to `createBackgroundScope()`, which uses `Dispatchers.Default` on JVM/Android. Test code that injects `StandardTestDispatcher(testScheduler)` cannot make `advanceUntilIdle()` work because the fake owns its own scope on `Default`.

Result: `TaskDetailViewModelTest` uses 16 real `delay()` calls totaling ~1.4 seconds to work around the non-deterministic collector scheduling. `CalendarViewModelTest` has flaky "may not transition from Loading" failures.

### Category B: `Clock.System.now()` in UI and DI code

Five locations bypass the `single<Clock> { Clock.System }` DI binding in `CoreDiModule.kt:206`:

| File | Line | Usage |
|---|---|---|
| `NotePreviewScreen.kt` | 438, 474, 475 | `Clock.System.now()` in relative-time formatting and `@Preview` |
| `TaskEditorSheetsHost.kt` | 123 | `Clock.System.now()` as recurrence anchor date |
| `ProjectDetailContent.kt` | 104 | `val clock: Clock = Clock.System` in composable |
| `CalendarDiModule.kt` | 30 | `Clock.System.now()` baked into `CalendarViewModel` factory `today` |

Problem: tests cannot freeze time. Tests for `NotePreviewScreen` previews and `CalendarViewModel` use real wall-clock time.

### Category C: `java.io.File` in KMP `commonMain`

`AttachmentThumbnail.kt:26` used `java.io.File(localPath)` — JVM-only API in a KMP common source set. Found and hot-fixed in this audit cycle.

### Category D: Public mutable state in domain objects

`EditorSession.titleFieldValue` was a public `var`. Fixed to `private set` + `updateTitleFieldValue()` method (this audit cycle).

## Decision

### 1. `FakeProfileAwareCurrentUser` accepts `CoroutineDispatcher` parameter

In `shared/src/commonMain/kotlin/com/singularity/todo/test/fakes/FakeRepositories.kt`:

```kotlin
fun FakeProfileAwareCurrentUser(
    authRepository: AuthRepository,
    profileRepository: ProfileRepository,
    scope: CoroutineScope = createBackgroundScope(),
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) = FakeProfileAwareCurrentUserImpl(
    authRepository,
    profileRepository,
    CoroutineScope(SupervisorJob() + dispatcher),
)
```

Tests pass `StandardTestDispatcher(testScheduler)` as `dispatcher`. After this, `advanceUntilIdle()` in tests drains all collectors deterministically → real `delay()` calls become unnecessary.

### 2. `Clock` consumed from DI in UI/DI code

Replace `Clock.System.now()` calls in production code with `get<Clock>().now()`. The `single<Clock> { Clock.System }` binding already exists in `CoreDiModule.kt:206`.

For `CalendarDiModule.kt:30`, the fix is:
```kotlin
viewModel { (year, month, mode) ->
    val deps: CalendarDeps = get()
    CalendarViewModel(
        ...
        today = deps.clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date,
    )
}
```

For `NotePreviewScreen.kt` preview and formatting, pass `Clock` as a composable parameter or use `remember { Clock.System }` for previews only.

### 3. `RecordingHttpClient` for external HTTP side-effects (without MockK)

Pattern (no MockK — BAN list):

```kotlin
class RecordingHttpClient(
    private val scriptedResponses: Map<HttpRequest, HttpResponse> = emptyMap(),
) : HttpClient {
    val recorded = mutableListOf<HttpRequest>()

    override suspend fun execute(req: HttpRequest): HttpResponse {
        recorded += req
        return scriptedResponses[req] ?: error("No scripted response for $req")
    }
}
```

Use in `SyncRepositoryTest` and `AiProviderTest` to verify URL/headers/body without mocking libraries.

### 4. Split `CoreDiModule.kt` (288 lines → ~70 lines per module)

Split into:
- `coreModule()` — true cross-cutting: coroutine scope, settings, clock, ids
- `authModule()` — session, auth repository
- `syncModule()` — sync engine, HLC, API client, repository
- `backupModule()` — backup/restore
- `settingsModule()` — settings ViewModel

Register with `includes()`:
```kotlin
fun coreModule() = module {
    includes(authModule(), syncModule(), backupModule(), settingsModule())
}
```

## Rationale

- **Virtual time works only when all coroutine scopes are controlled.** If any fake or production code hardcodes `Dispatchers.Default`, `advanceUntilIdle()` misses events from uncontrolled scopes.
- **`Clock` injection is the standard pattern** — the `single<Clock> { Clock.System }` binding already exists; most consumers just don't use it.
- **`RecordingHttpClient` avoids MockK** entirely (project BAN list), while still allowing interaction testing for HTTP.
- **CoreDiModule split reduces cognitive load** and makes DI easier to navigate and test in isolation.

## Consequences

- **Always** inject `CoroutineDispatcher` into fakes that own a `CoroutineScope`. Use `StandardTestDispatcher(testScheduler)` in tests.
- **Always** use `get<Clock>()` in DI modules and UI code instead of `Clock.System.now()`.
- **Never** hardcode `Dispatchers.Default` or `Dispatchers.Unconfined` in production ViewModels.
- **Never** use `java.io.File` in `commonMain` — use `FileSystem` port or pass paths as `String`.
- **Never** expose public mutable properties on domain objects — use `private set` + mutation methods.
- **Always** use `RecordingHttpClient` (or similar scripted-response pattern) for HTTP verification in tests without MockK.
- `NotesListViewModel` has 3 `Dispatchers.Unconfined` usages that should be replaced with `scope.launch { ... }` — tracked separately in PR-3.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/coroutines/BackgroundScope.kt` — `createBackgroundScope()` expect/actual
- `shared/src/commonMain/kotlin/com/singularity/todo/core/di/CoreDiModule.kt:206` — `single<Clock>`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/di/CalendarDiModule.kt:30` — `Clock.System.now()` in factory
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/presentation/viewmodel/NotesListViewModel.kt:185,198,207` — `Dispatchers.Unconfined`
- `shared/src/commonMain/kotlin/com/singularity/todo/test/fakes/FakeRepositories.kt` — `FakeProfileAwareCurrentUser` factory functions
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/attachments/AttachmentThumbnail.kt` — hot-fixed `java.io.File` → `String`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/EditorSession.kt` — `titleFieldValue` encapsulated
