---
title: "Section Prefill Dynamic Date"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status (re-verified 2026-10-04):** CLOSED and verified 2026-10-04. The hardcoded `LocalDate(2026, 10, 3)` is gone from `AgendaPresets.kt`; `SectionPrefill.relativeDueDate: RelativeBucket?` resolves through `todayAt(deps.clock)` at `AgendaViewModel.kt:190`.

**Found in:** MR-1, `AgendaPresets.kt`. `SectionPrefill.dueDate` is `LocalDate` — a
compile-time constant in an `object`. `Today` section uses `LocalDate(2026, 10, 3)`
which matches the AGENDA_SEED but not the actual date.

**Checks already performed:**
- `handleCreateInSection` correctly maps `LocalDate` → `DueDateOption.Custom`
- Draft is saved to `DraftStore` with correct key
- `TaskCreateViewModel` correctly reads the draft back

**Ruled out:** Runtime `Clock` is not accessible from `object` initializer.

**Status: RESOLVED** (2026-10-04). Neither suggested fix was needed — the third
one was. `SectionPrefill` gained a `relativeDueDate: RelativeBucket?` field
alongside the old `dueDate`, so a preset stores a *rule* rather than a date, and
the date is resolved at the moment the user taps «+»:

- `AgendaDefinition.kt` — `SectionPrefill.relativeDueDate`
- `Clock.kt` — `todayAt(clock, zone)`, the one place that turns an injected
  `Clock` into a `LocalDate`
- `AgendaViewModel.handleCreateInSection` resolves through the injected clock

`AgendaPresets` stays an `object` with no constructor parameter, because the
resolution happens at use, not at initialisation — which is exactly the point
the "Ruled out" note above was circling.

Pinned by `SavedAgendaEditFlowTest` — *"create in section prefills a due date
relative to the injected clock"* — which drives the VM with a `FakeClock` set
away from the host's real date. Teeth verified: restoring the hardcoded
`LocalDate` constant turns it red.

---
