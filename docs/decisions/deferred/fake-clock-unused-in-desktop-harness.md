---
title: "Fake Clock Unused In Desktop Harness"
date: 2000-01-01
status: CLOSED
tags: ["deferred", "selector-and-tag-identity"]
---

**Tracked as:** #108
**OpenSpec change:** `openspec/changes/selector-and-tag-identity/`

**Found in:** MR-0, свип desktop harness и FakeClock.

**Symptom:** `FakeClock` существует (`shared/src/commonMain/.../test/fakes/FakeClock.kt`) с API `advance(Duration)`, `setNow(Instant)`, `today(zone)`. Имеет **0 упоминаний** в `desktopApp/src/jvmTest`. `runDesktopAppTest` не принимает clock-параметр. Все desktop flow-тесты используют `todayInSystemZone()` → реальное время хоста → date-dependent тесты флакиют на границах месяца/недели.

`CalendarFlowTest` так уже падал: «passed on September 30th, failed on October 1st».

**Status: CLOSED (2026-10-09).** `runDesktopAppTest` уже принимает `clock: Clock?` параметр и подключает в Koin через last-wins (DesktopAppHarness.kt §clock). `CalendarFlowTest` уже передаёт `clock = CLOCK` (FakeClock с фиксированным 2026-09-16) на каждый вызов. Дефект не требовал кода — он уже был реализован, и issue #108 закрыта как уже решённая.

---
