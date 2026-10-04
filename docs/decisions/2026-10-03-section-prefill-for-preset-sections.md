---
title: "Section prefill for preset sections: the current decision and the work it defers"
status: accepted
date: 2026-10-03
tags: [agenda, ui, scope]
---

# SectionPrefill для пресет-секций: текущее решение и future work

## Context

Кнопка "+" в секциях Inbox/Today/Upcoming молча no-op, потому что `section.prefill == null`
для всех секций в `AgendaPresets`. При нажатии `handleCreateInSection` делает `return` сразу
после `val sectionPrefill = section.prefill ?: return`.

`SectionPrefill` содержит:
- `sectionId: String` — ключ для DraftStore
- `title: String?` — подсказка заголовка
- `dueDate: LocalDate?` — подсказка даты

## Idea

Добавить `prefill` во все секции Inbox/Today/Upcoming. Минимально: `sectionId + title`.
Для секций с конкретной датой (Today, Tomorrow) — захардкодить дату из AGENDA_SEED
(8 октября 2026 = среда), т.к. пресеты создаются при инициализации object в compile time
и не имеют доступа к `Clock`.

Future work: динамический prefill через `RelativeBucket`-aware `DueDateOption` enum
или функцию `AgendaPresets.createWithContext(clock: Clock)`.

## Decision

- Добавить `SectionPrefill(sectionId = <id>, title = <name>)` во все секции Inbox/Today/Upcoming
- Для секций "Today" и "Tomorrow" добавить `dueDate` из расчёта AGENDA_SEED:
  - Today (2026-10-03, среда) → `LocalDate(2026, 10, 3)`
  - Tomorrow → `LocalDate(2026, 10, 4)`
  - This Week → `LocalDate(2026, 9, 28)` (Monday)
  - Next Week → `LocalDate(2026, 10, 5)` (Monday next)
- Для Overdue, Yesterday, This Month, No Date — только title prefill (без даты)
- `sectionId` = `effectiveId` секции (например, "today", "tomorrow")
- Оставить KDoc-комментарий про future work

## Rationale

- Пресеты — `object` с `val` полями, инициализируются один раз при запуске JVM
- `kotlin.time.Clock` не доступен в этой точке без рефакторинга
- Хардкод сегодняшней даты из AGENDA_SEED —常温 для dev-окружения; в prod
  Sections without date prefill still open create screen with empty draft, which is strictly
  better than silent no-op.
- Future work: add `DueDateOption.Relative(RelativeBucket)` or make presets factory
  with clock parameter.

## Consequences

- "+" кнопка в секциях Inbox/Today/Upcoming перестаёт быть no-op
- Draft создаётся с section-specific key (`section_create_draft_<sectionId>`)
- Тесты MR-2/MR-3 смогут использовать `handleCreateInSection` напрямую
- Тесты MR-3 (PresetsCatalogTest) проверят что prefill не-null для всех секций

## Links

- K1 в `docs/plans/2026-10-03-agenda-views-test-plan.md`
- `deferred-backlog.md`: section-prefill-dynamic-date
