---
title: "Project reminders get their own table rather than a nullable task_id"
date: 2026-09-30
status: accepted
tags: [reminders, projects, database, migration, room]
---

## Context

`ProjectDetailContent.kt:254` carried `onSetReminder = { /* TODO: wire once
project-reminder domain is implemented */ }`. Everything around it already worked: the
reminder sheet opened, `ReminderPickerSheet` emitted a `ReminderOffset`, the domain
event type existed. Only the write and the storage were missing, so a user could open a
perfectly good "set reminder" picker, choose an offset, and watch it do nothing.

This is the one item in Phase 2 that required a schema change, so the storage decision
is the part worth writing down.

## The choice

`task_reminders` already exists and already has a nullable `view_id` for a
cross-cutting association. The obvious cheap move was to add a nullable `project_id`
and let one table carry both. The alternative — which is what shipped — is a separate
`project_reminders` table.

## Decision

A **separate `project_reminders` table**, schema version **22 → 23**, purely additive,
so Room's AutoMigration generates the `CREATE TABLE` with no hand-written spec.

## Rationale

`task_reminders.task_id` is `NOT NULL`. Relaxing it is not a one-line migration: SQLite
cannot drop a `NOT NULL` constraint in place, so Room has to rebuild the table — create
new, copy, drop, rename — which is the most failure-prone shape an auto-migration takes,
on the table that also holds every task reminder. A migration that corrupts reminders
would be far worse than one extra table.

The naming cost is real but bounded. `TaskReminderEntity` would have become a type named
for tasks that also stored projects, and every read would have to branch on which
`NULL` meant "legitimate" and which meant "corrupt" — a distinction with no way to
distinguish them at runtime.

The one thing the shared table *would* have bought is a single fire path. That cost is
paid explicitly in the two-table design and is the reason the model is deliberately
smaller than `Reminder` rather than a copy of it:

- no `type` (gentle / annoying) — a project reminder does not escalate
- no `offsetMinutes` — the offset is the authoring input, not stored state
- no `recurringPattern` — it fires once

A column that is always null is a column that eventually gets read as meaningful. The
narrower table makes the semantics impossible to misread.

**Offset in, instant out.** The user picks an offset from the due date; the repository
stores an absolute `fireAt`, because that is what an alarm scheduler needs. The offset
is the *authoring* form. `ProjectDetailViewModel` converts back for the picker, so
editing a project's due date re-anchors the alarm instead of leaving one that fires at
the stale instant. Two tests pin this round trip, including the case where the two
drift apart.

## Consequences

- Schema 23. Migration is additive and needs no spec; Room generated and validated it.
- A project with **no due date** cannot have a reminder — there is nothing to anchor to.
  The intent reports an error rather than storing a reminder the user cannot reason
  about. Test covers it.
- Re-picking an offset **updates in place**: the handler reuses the existing id rather
  than minting a new row per change, so repeated edits do not pile up alarm rows. Test
  covers it.
- New entity, DAO, repository port + Room impl, `FakeProjectRemindersRepository` and a
  `FakeProjectReminderDao`. No use case — consistent with `PassThroughUseCase`, and with
  `ReminderRepository`, which has none either.
- `ProjectDetailViewModel` gained a `ProjectRemindersRepository` dependency, so its test
  factory takes a fake.
- `reminderOffsetMinutes` is stored on `ProjectDetailUi` as a raw minute count. When the
  `ReminderOffset` enum changes, a stored count that matches no member renders as "no
  selection" rather than a value the picker cannot display.
- **The alarm is scheduled, not fired.** The data model, persistence and UI are wired,
  but no `AlarmManager` / `at` job registers a `ProjectReminder` yet, and no
  `ACTION_PROJECT_REMINDER_FIRE` receiver exists. The fire path is the remaining half
  and is deliberately a follow-up: it touches the manifest and the boot-catch-up logic,
  and shipping it alongside the migration would have made this change much harder to
  review. Until it exists, a set reminder persists correctly and then never fires —
  which is worth knowing, and is why this ADR is explicit about it.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/reminders/ProjectReminder.kt`
- `.../reminders/ProjectRemindersRepository.kt`
- `.../core/database/Entities.kt` (`ProjectReminderEntity`), `Daos.kt` (`ProjectReminderDao`)
- `.../core/database/AppDatabase.kt` (version 23), `.../core/di/CoreDiModule.kt`
- `.../projects/presentation/viewmodel/ProjectDetailViewModel.kt` (`setReminder`)
- `.../jvmTest/.../ProjectDetailViewModelTest.kt` — 4 reminder tests
- Same family: `2026-09-29-destroyed-but-not-deleted-callbacks.md`
