---
title: "FakeClock in the desktopApp UI harness"
status: accepted
date: 2026-10-03
tags: [testing, desktop, clock]
---

# FakeClock in runDesktopAppTest harness

## Context

Desktop flow-тесты (`desktopApp/src/jvmTest`) используют `todayInSystemZone()` для вычисления дат в тестах (например, `AgendaBadgePolicyFlowTest`, `CalendarFlowTest`). Это привязывает тесты к реальному времени хоста.

`CalendarFlowTest` так уже упал в CI: «passed on September 30th, failed on October 1st» — граница месяца.

`FakeClock` существует (`shared/src/commonMain/.../test/fakes/FakeClock.kt`) с API `advance(Duration)`, `setNow(Instant)`, `today(zone)`, но имеет **0 упоминаний** в `desktopApp/src/jvmTest`.

## Decision

Добавить опциональный параметр `fakeClock: FakeClock? = null` в `runDesktopAppTest` (desktopApp harness).

Если передан — подключать через Koin last-wins:
```kotlin
runDesktopAppTest(
    overrides = module { single<Clock> { fakeClock } },
    checkA11y = true,
) { koin -> ... }
```

Если `null` — поведение unchanged (используется `Clock.System` из production binding).

Код в `DesktopAppHarness.kt`:
```kotlin
fun runDesktopAppTest(
    overrides: Module = module {},
    attempt: Int = 1,
    checkA11y: Boolean = false,
    fakeClock: FakeClock? = null,        // NEW
    test: suspend DesktopComposeUiTest.(koin: Koin) -> Unit,
) {
    val clockModule = if (fakeClock != null) {
        module { single<Clock> { fakeClock } }
    } else module {}

    // composeTestScope overrides = overrides + clockModule
}
```

## Rationale

- FakeClock уже существует и используется в shared/commonTest.
- Koin last-wins semantics делает подмену тривиальной.
- Все существующие desktop flow тесты остаются compatible (fakeClock = null по умолчанию).
- date-dependent тесты (agenda, calendar) получают детерминированное время.

## Consequences

- Тесты `AgendaBadgePolicyFlowTest`, `CalendarFlowTest` и другие date-dependent тесты переходят на `FakeClock` с фиксированным `today = 2026-10-14`.
- Backlog-пункт `no-direct-clock-system-kdoc-claims-tests-are-exempt` закрывается: тесты теперь используют FakeClock, а не `Clock.System` напрямую.
- `find-unwired-surfaces` не затрагивается.

## Links

- `shared/src/commonMain/.../test/fakes/FakeClock.kt`
- `desktopApp/src/jvmTest/.../test/helpers/DesktopAppHarness.kt`
- `deferred-backlog.md#fake-clock-unused-in-desktop-harness`
