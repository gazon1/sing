# ADR: Calendar Post-Merge Fixes

**Date:** 2026-09-16
**Status:** Accepted
**Context:** Post-implementation review of the Calendar feature — three targeted fixes applied before first usage.

---

## Context

After completing the Calendar feature (commit `4dd094a`), a code review identified three issues requiring fixes:

1. **`staticCompositionLocalOf` instead of `compositionLocalOf`** — palette wouldn't recompose on theme switch
2. **`endTime`/`accentColor` always null without explanation** — misleading API surface
3. **`Clock` shadowing in `CalendarDeps`** — `kotlinx.datetime.Clock` type conflicted with project's `expect object Clock`

---

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

## Decision 3 — FQDN for `kotlinx.datetime.Clock` in `CalendarDeps`

**File:** `CalendarDeps.kt`

`CalendarDeps.clock` parameter has type `kotlinx.datetime.Clock` (the stdlib clock interface). However, the project defines `expect object Clock` in `core/platform/Clock.kt` which shadows the stdlib type in imported scope.

```kotlin
// Before — Clock resolves to expect object Clock (no .System member)
import kotlinx.datetime.Clock
val clock: Clock  // ← expect object Clock, not kotlinx.datetime.Clock

// After — unambiguous FQDN
val clock: kotlinx.datetime.Clock
```

Full rename of `expect object Clock` to `PlatformClock` was considered but rejected:
it would require updating 40+ files across the codebase. The FQDN approach is a targeted fix with zero collateral.

---

## Consequences

### Positive
- Theme switching now correctly recomposes the calendar palette
- Future developers understand which fields are stubbed vs. populated
- No shadowing ambiguity in `CalendarDeps`

### Negative
- None

### Deferred
- `endTime` / `accentColor` — blocked on Room migration for `startAt`/`endAt`/`accentColor` fields in `Task`
- `expect object Clock` rename to `PlatformClock` — deferred until a broader cleanup window

---

## Links

- `CalendarTheme.kt` — fix 1
- `CalendarTaskUi.kt` — fix 2
- `CalendarDeps.kt` — fix 3
- `docs/decisions/2026-09-16-calendar-feature.md` — original architecture
