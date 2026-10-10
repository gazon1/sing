---
title: "Agenda Section Add Button Noop"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status (re-verified 2026-10-04):** CLOSED and verified 2026-10-04. `AgendaPresets.kt` carries 15 `prefill = SectionPrefill` sites (the entry said 13) and `AgendaViewModel.kt:190` resolves `relativeDueDate` through the injected clock, so `section.prefill ?: return` can no longer fire. Subsumed by #26.

**Found in:** MR-0, при написании тест-плана agenda-views (кодовая разведка).

**Symptom:** кнопка «+» в заголовке секции в `AgendaScreen` при тапе вызывает `AgendaIntent.CreateInSection` → `handleCreateInSection(sectionId)`. Функция делает `scope.launch { handleCreateInSection(intent.sectionId) }`, внутри:
```kotlin
val section = definition.sections.find { it.effectiveId == sectionId } ?: return
val sectionPrefill = section.prefill ?: return   // ← early return, prefill == null
```
Ни один preset в `AgendaPresets` не задаёт `SectionPrefill`; `Section.prefill` всегда `null`. Тап на «+» silently no-op.

**Status: RESOLVED** (MR-1, 2026-10-03). `AgendaPresets` now sets `prefill` on
**13 sections** across every preset (`AgendaPresets.kt:42-202`): `Inbox`/
`Today`/`Upcoming`/custom all carry a `SectionPrefill`, so the early return at
`section.prefill ?: return` no longer fires. The prefill dates are
`RelativeBucket` values, not constants — see `section-prefill-dynamic-date`
below, which this fix depended on.

**Try next:** добавить `prefill = SectionPrefill.Date` в каждую секцию Inbox/Today/Upcoming с `RelativeBucket`-compatible датой.

---
