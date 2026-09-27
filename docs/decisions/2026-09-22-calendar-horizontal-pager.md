---
title: Calendar HorizontalPager (MR-1)
date: 2026-09-22
status: accepted
---

# Calendar HorizontalPager (MR-1)

## Context

MR-1 of the 6-MR calendar modernization plan calls for a swipeable month-view calendar.
The original plan targeted `kizitonwose/Calendar-Compose` as the month grid library.

During MR-1 implementation, a fundamental incompatibility was discovered:
kizitonwose ships as an Android-only AAR bundled with AndroidX Compose 1.7.2, while the
project uses **JetBrains Compose Multiplatform 1.12.0**. These two Compose distributions
cannot coexist in the same JVM process or Android APK — the AndroidX AAR pulls in
AndroidX Compose runtime that conflicts with JB Compose's own runtime.

Three options were evaluated:

- **Option A**: HorizontalPager (JB Compose foundation) + custom month grid — pure commonMain
- **Option B**: kizitonwose wrapped in an `expect/actual` boundary, AndroidX-only on Android, stub on JVM
- **Option C**: Abandon MR-1, keep hand-rolled projection

## Decision

**Option A** — HorizontalPager + custom month grid — was chosen.

### What was built

- `YearMonth` data class in `CalendarDateMath.kt`: pure identity for pager pages
- Pure pager math functions:
  - `monthPageRange(anchor, span=120)` → `(YearMonth, YearMonth)` range
  - `yearMonthForPage(anchor, page, span)` → `YearMonth`
  - `pageForYearMonth(anchor, target, span)` → `Int?` (null = out of range)
- `MonthPageChanged(YearMonth)` intent in `CalendarIntent.kt`
- `MonthPageChanged` handler in `CalendarViewModel` with **deduplication**: updates `anchor`
  only when `newAnchor != _calendarState.value.anchor` to avoid spurious Room re-subscribes
- `MonthGridView` rewritten with `HorizontalPager` from JB Compose foundation:
  - `beyondViewportPageCount = 1` (Compose 1.12.0 naming)
  - `pageCount = 240` (±120 months = ±10 years)
  - Fixed weekday-header row above the pager (not inside it)
  - Two `LaunchedEffect` blocks:
    1. **Settled-page commit**: `pagerState.currentPage` → `MonthPageChanged` intent (with dedupe)
    2. **External jump**: when `monthAnchor` param changes externally (e.g. mini-calendar pick),
       `animateScrollToPage(page)` to animate the pager to the target page
- `headerLabelOverride` pattern in `CalendarTopBar`: accepts optional override string so
  `CalendarContent` can derive the live header from `pagerState.currentPage` without a VM round-trip
- `pagerState: PagerState? = null` parameter on `CalendarContent` — hoisted for testability

### What was removed

- kizitonwose dependency from `shared/build.gradle.kts` (was added during exploration, removed)
- No kizitonwose in `libs.versions.toml` (never needed after TOML syntax fix)

## Rationale

- **No new Android-only dependency**: everything lives in `commonMain`, works on JVM desktop
- **JB Compose foundation only**: `HorizontalPager` + `PagerState` are part of Compose Multiplatform
- **Testable**: pure date math is unit-tested (15 new tests); pager state is hoisted and injectable
- **Predictable swipe range**: ±10 years is enough for any realistic use; longer jumps go through mini-calendar
- **External jump is animated**: mini-calendar picks trigger smooth `animateScrollToPage`, not a jarring snap

## Consequences

- Month-grid cells are still hand-rolled (no kizitonwose `MonthView`). Week/Day remain unchanged.
- The `pageCount = 240` is fixed at compile time. Users navigating beyond ±10 years from today
  will land on the nearest edge page; this is acceptable per the plan.
- kizitonwose remains available for future exploration if AndroidX/JB compatibility is resolved.

## Links

- MR-1 branch: `feature/calendar-mr1-kizitonwose`
- MR-0 ADR: `2026-09-22-outbox-workmanager-refactor.md`
- Original plan: `docs/decisions/DIGEST.md` §Calendar Modernization
