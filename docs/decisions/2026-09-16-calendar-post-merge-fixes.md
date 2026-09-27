---
date: 2026-09-16
status: accepted
tags: [calendar, code-review, compose]
---

# Calendar post-merge fixes

## Context

After completing the Calendar feature (commit `4dd094a`), a code review identified three
issues requiring fixes.

> **Superseded in part (2026-09-27):** the project-level
> `com.singularity.todo.core.platform.Clock` expect/actual object was removed. Use
> `kotlin.time.Clock.System.now()` and `core.platform.todayFlow()` / `todayInSystemZone()`.
> See [2026-09-27-remove-platform-clock-object.md](2026-09-27-remove-platform-clock-object.md).

## Decision 1 — `staticCompositionLocalOf` → `compositionLocalOf`

**File:** `CalendarTheme.kt`

`staticCompositionLocalOf` stores the value once at composition time and does **not** trigger recomposition when the value changes. Since `ProvideCalendarPalette` evaluates `isSystemInDarkTheme()` at call time, switching the system theme (light↔dark) after app startup would leave the calendar with the old palette.

```kotlin
// Before (broken — no recomposition on theme switch)
val LocalCalendarPalette = staticCompositionLocalOf<CalendarPalette> { ... }

// After (correct — recomposes when palette changes)
val LocalCalendarPalette = compositionLocalOf<CalendarPalette> { ... }
```

`compositionLocalOf` is the standard choice when the provided value can change during the composable's lifetime.

---

## Decision 2 — Document `endTime`/`accentColor` as always null

**File:** `CalendarTaskUi.kt`

`CalendarTaskUi.endTime` and `CalendarTaskUi.accentColor` are always `null` because the underlying `Task` domain model has neither field:

- `endTime` requires `startAt`/`endAt` fields on `Task` → **Room migration needed**
- `accentColor` does not exist in the `Task` schema → **Room migration needed**

Without documentation, callers might reasonably expect these fields to be populated. Clear KDoc now marks them as deferred:

```kotlin
/**
 * End time. Always null — Task only has dueTime (single time).
 * endTime requires a Room migration to add startAt/endAt fields.
 */
val endTime: LocalTime? = null
```

`startTime` was also documented to clarify it maps from `Task.dueTime`.

---

## Decision 3 — Delete `CalendarDeps.clock` (replaces original Decision 3)

**Original Decision 3** changed the field type to FQDN `kotlinx.datetime.Clock` to "avoid shadowing." This caused a runtime Koin error because `single { Clock }` in `CoreDiModule.kt:108` registers `com.singularity.todo.core.platform.Clock`, not `kotlinx.datetime.Clock`. Koin resolved `kotlinx.datetime.Clock` → `kotlin.time.Clock` (the same JVM type, just different FQDN), but no binding existed under either name.

**The correct fix is to delete the field entirely.** `CalendarViewModel` never calls `deps.clock.now()` — it uses `todayInSystemZone()` directly (line 60), a top-level `expect/actual` function that needs no DI.

Removing the field:

- Eliminates the DI ambiguity permanently — no Clock type ever has to be resolved
- Follows the existing `AgendaDeps` pattern (`AgendaDeps.kt:16` has no `clock` field; `AgendaViewModel.kt:49` calls `todayInSystemZone()` directly)
- Removes the misleading KDoc about "shadowing" — no shadowing exists, and no field exists

```kotlin
// After — no clock field
data class CalendarDeps(
    val taskRepo: TaskRepository,
    val currentUser: ProfileAwareCurrentUser,
    val logger: Logger,
)
```

The `calendarModule()` Koin definition lost its `clock = get()` line as well. The `CalendarViewModelTest` no longer constructs an anonymous `Clock` object.

---

## Why the original FQDN fix failed

The original "fix" treated the symptom: Koin could not find a binding for the new type. Changing the field type made Koin ask for a different class, but the underlying problem was that the field had no consumer in the first place.

Lesson: when adding a dependency to a constructor, verify it is actually used inside the class body. Dead dependencies pollute the DI graph and create maintenance confusion (e.g. why is this type different from the others?).

---

## Consequences

### Positive
- Theme switching now correctly recomposes the calendar palette
- Future developers understand which fields are stubbed vs. populated
- Dead dependency removed from `CalendarDeps` — DI graph is now consistent
- `CalendarDeps` matches the `AgendaDeps` pattern (project convention)

### Negative
- None

### Deferred
- `endTime` / `accentColor` — blocked on Room migration for `startAt`/`endAt`/`accentColor` fields in `Task`
- `expect object Clock` rename to `PlatformClock` — deferred until a broader cleanup window
- `TaskEditorDeps.clock` is also dead (the file's own KDoc flags it for deletion alongside `TaskEditorViewModel`)

---

## Links

- `CalendarTheme.kt` — fix 1
- `CalendarTaskUi.kt` — fix 2
- `CalendarDeps.kt`, `CalendarDiModule.kt`, `CalendarViewModelTest.kt` — fix 3 (delete)
- `docs/decisions/2026-09-16-calendar-feature.md` — original architecture
