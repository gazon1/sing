---
title: Tech Debt Mini-PRs — September 2024
date: 2026-09-24
status: accepted
tags: [tech-debt, deprecation, android]
epic: chore/tech-debt-mini-prs
---

# Tech Debt Mini-PRs — September 2024

> **Superseded in part (2026-09-27):** the project-level `com.singularity.todo.core.platform.Clock` expect/actual object was removed. Use `kotlin.time.Clock.System.now()` (inject `Clock` for tests) and `core.platform.todayFlow()` / `todayInSystemZone()`. See [2026-09-27-remove-platform-clock-object.md](2026-09-27-remove-platform-clock-object.md).


## Context

Three targeted fixes from the accumulated tech debt backlog (ADRs `2026-09-23-deprecation-tech-debt`, `2026-09-23-ota-deferred-items`).

## Decision

Apply three independent, low-risk changes:

### MR-2.1: `anchorDate.monthNumber` (Int → `kotlinx.datetime.Month`)

**Source:** `kotlinx-datetime 0.8.0` deprecation of `monthNumber`.

| File | Change |
|---|---|
| `CalendarScreen.kt:29` | `anchorDate.monthNumber` → `anchorDate.month` |
| `CalendarDiModule.kt` | parameter type `Int` → `Month` |

`LocalDate.month` returns `Month` directly; no conversion needed. `CalendarDiModule` accepts `Month` and passes it to `LocalDate(year, month, 1)`, which has an overload taking `Month`.

### MR-2.2: Dead `kotlinx.datetime.Clock` DI binding removed

**Source:** `2026-09-23-deprecation-tech-debt.md` §C.

Verified: `single<kotlinx.datetime.Clock> { kotlin.time.Clock.System }` in `CoreDiModule.kt:194` has **zero DI consumers**. All 15+ clock-using sites inject `com.singularity.todo.core.platform.Clock` (the `expect`/`actual` pair) directly.

| File | Change |
|---|---|
| `CoreDiModule.kt:194` | Deleted dead binding |
| `core/platform/Clock.kt` | Added `@Deprecated("Use kotlinx.datetime.Clock directly...")` |

Production call sites (15+ files) not migrated — out of scope for a mini-PR.

### MR-2.3: `AppUpdateGate.tryOfferUpdate` → `tryOfferUpdateOnMain`

**Source:** `2026-09-23-ota-deferred-items.md` §2.

`tryOfferUpdate` calls `Handler.post {}` and assumes the caller is on the main thread. The only call site is `MainActivity.onResume`, which is always main-thread. The rename makes the constraint explicit.

| File | Change |
|---|---|
| `AppUpdateGate.kt` | Renamed to `tryOfferUpdateOnMain`, KDoc updated with main-thread constraint + `Dispatchers.Main` suggestion |
| `MainActivity.kt:67` | Call site updated |

## Consequences

- No breaking changes to public API
- All three changes are additive-renames only
- MR-2.2 note: `expect object Clock` remains for backward compatibility; production code should use `kotlinx.datetime.Clock` directly

## Links

- `2026-09-23-deprecation-tech-debt.md` — source for MR-2.1, MR-2.2
- `2026-09-23-ota-deferred-items.md` — source for MR-2.3
- `2026-09-24-datastore-catch-fix-together.md` — preceding MR-0
