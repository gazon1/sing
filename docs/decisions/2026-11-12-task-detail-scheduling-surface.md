---
title: One scheduling surface on Task Detail — dates, time, reminder, deadline, someday
date: 2026-11-12
status: accepted
slug: task-detail-scheduling-surface
---

# One scheduling surface on Task Detail — dates, time, reminder, deadline, someday

## Context

The task detail screen shows a due date and nothing else about *when*. The rest of
the scheduling facts exist in the domain and have no surface at all:

| Fact | Where it lives | Where the user sees it |
|---|---|---|
| due date / due time | `Task.dueDate`, `Task.dueTime` | one row |
| reminder | `task_reminders` rows, `Reminder.offsetMinutes` | nothing — `ui.reminders` is assembled by the coordinator and read by no UI |
| deadline | `Task.endDate` | nothing — no intent writes it, no row reads it |
| someday | `Task.someday` | nothing — `ToggleSomeday` is declared, routed and handled, and no dispatch site emits it |
| recurrence | `Task.recurrence` | its own row and its own sheet |

Three of the five are unreachable, and the two that are reachable are reachable in
different ways: the due date opens a Material3 `DatePicker`, the due *time* has a
sheet variant (`TaskEditorSheet.Time`) that **nothing ever opens**, and the
recurrence has a sheet of its own with its own chrome.

Two accepted documents disagree about what to build:

- `docs/research/task-detail-ux/implementation/target-ux.md` §2 puts the reminder in
  its **own** sheet (`Reminder` variant, saved on `Добавить`, cleared by `Удалить`),
  and puts the deadline **in the same date sheet** behind a flag icon (§4).
- `docs/research/task-detail-ux/implementation/wireframes/task-detail.md` §6 draws one
  sheet `Дата и время` with the time row **and** the reminder row inside it, and §8
  adds a separate `+ Ещё` parameter sheet with `+ Напоминание` and `+ Крайний срок`.

Issue #2876 asks for the conflict to be resolved **in writing before the code**, and
for the clear/cancel semantics to be stated rather than implied.

## Idea

Three ways to give the screen one scheduling experience.

1. **One sheet with everything.** Due date, time, reminder, deadline and someday in a
   single sheet. One place to look, one place to undo. But the sheet has to hold two
   calendars (the due date and the deadline) or a calendar plus a time dial, and at
   360×800 dp that is a scroll of five sections whose primary actions fall below the
   fold — the exact failure the acceptance criteria name.
2. **Four scattered rows, each with its own sheet.** Due date, time, reminder and
   deadline as four rows on the detail screen, each opening a small sheet. Coherent in
   the sense that the rows are neighbours — but the due date and its time become two
   facts with two sheets, which is what the user has to hold in their head today, and
   the deadline would be one tap further from the date it bounds.
3. **A planning group of rows on the detail screen, and two sheets that own the
   facts.** The rows are the discoverable surface: every scheduling fact is on the
   task screen, one tap away, with its value and a way to clear it. The sheets own
   the editing: one *date sheet* for the due date, its time, the deadline and the
   `someday` preset, and one *reminder sheet* for the reminder. Everything that is a
   date goes in one sheet; the reminder, which is not a date, gets its own.

Option 3 is what this ADR takes.

## Decision

### The surface

The task detail shows three planning rows, in this order, next to each other:

- **Дата** — always visible, whatever else is set. Reads the date and the time, or
  `Когда-нибудь` when `someday == true`, or `Добавить дату` when neither is set.
- **Напоминание** — always visible. Reads the offset (`За 15 минут`), or
  `Напоминание` when no reminder is set, or `Уведомления выключены` when the system
  permission is denied. When a reminder *is* set and the permission is denied, the row
  reads the offset **and** the notice (`За 15 минут · Уведомления выключены`): the
  reminder is there and it cannot fire, and a row that swapped its value for a warning
  would hide the thing the user came to check.
- **Крайний срок** — always visible. Reads the deadline, or `Добавить срок` when
  unset, and appends `Просрочен` when the deadline is before today and the task is
  open.

All three are visible on one screen at 360×800 dp: they are three rows of the
existing attribute list, and no row is a hint sentence — an empty row is a row that
opens a sheet, which is how the deadline and the reminder become reachable at all.

### The reminder gets its own sheet — the wireframe loses

`target-ux.md` §2/§4 and the wireframe §6 disagree, and the ADR resolves it in favour
of the **separate reminder sheet**. Three reasons:

- The reminder is not a date. It is a row in another table, written by another slot,
  and it has two gates of its own (the scheduler's capability and the system
  notification permission). A sheet that edits a date and a sheet that edits a
  reminder have different contracts: the date sheet edits two dates and a time and
  commits all of them together, the reminder sheet writes one offset and nothing
  else.
- The deadline lives in the date sheet, so the date sheet already carries a second
  calendar and a time control. A third section in it — with its own save button and
  its own permission notice — is what makes a sheet that does not fit.
- `TaskRemindersSlot` owns the write, the alarm and the capability gate
  (`docs/decisions/2026-10-07-reminders-capability-gate.md`). The sheet supplies an
  offset and nothing else. Keeping it in its own sheet keeps that boundary visible.

### The date sheet owns the date, the time, the deadline and `someday`

One sheet, opened from the **Дата** row and from the **Крайний срок** row (the
latter opens it on the deadline). It carries:

- the month grid, with `Сегодня` and `Завтра` presets;
- the `Когда-нибудь` preset, which sets `someday` and clears the due date;
- the time control;
- the deadline row, which switches the grid to the deadline.

The sheet edits **one date at a time**. It has a *focused fact* — the due date or
the deadline — and the focused row is marked; the grid, the presets and the
`Очистить` action all act on it. Two calendars do not fit on a 360×800 screen
next to a time control and three actions, so the sheet edits the two dates in
turn instead of side by side. The consequence is that a control's meaning must
not change with the focus, and that is why the presets follow the focus:
`Сегодня` and `Завтра` set **the focused fact**, not always the due date — a
preset that always set the due date would put tomorrow's due date on the screen
while the user is picking a deadline. `Когда-нибудь` belongs to the due date
alone (`someday` means *no date*, so it cannot mean "no deadline"), and it is
offered only while the due date is focused. It is a toggle: tapping it a second
time takes the task out of the `someday` bucket and leaves it with no date at
all, rather than inventing one.

The grid is the app's own (`MultiDatePickerSheet`'s), not Material3's `DatePicker`:
the Material3 calendar is a 600 dp block that pushes the action row off a 360×800
screen, and the app's grid is compact enough to leave the `Сохранить` / `Отмена` /
`Очистить` row on the screen.

Recurrence stays out of it. It has its own sheet, its own chrome and its own
semantics (a rule, not a moment), and the issue excludes it.

### The clear and cancel semantics, stated

Every sheet applies its change **only** on its confirm button. `Отмена`, a
swipe-down and a back gesture write nothing: the persisted values are exactly what
they were when the sheet opened.

Clearing is per fact, and the facts are independent:

| Action | Due date | Due time | Reminder | Deadline | `someday` |
|---|---|---|---|---|---|
| `Очистить` in the date sheet, date focused | cleared | cleared | **kept** | **kept** | cleared |
| `Очистить` in the date sheet, deadline focused | kept | kept | kept | cleared | kept |
| `✕` on the date row | cleared | cleared | **kept** | **kept** | cleared |
| `✕` on the reminder row | kept | kept | cleared | kept | kept |
| `✕` on the deadline row | kept | kept | kept | cleared | kept |
| the `Когда-нибудь` preset | cleared | cleared | **kept** | **kept** | set |

`Очистить` clears the fact the sheet is focused on, and only that fact: the due
date (with its time, because a time without a date is not a moment) or the
deadline. It is not a "clear everything" button — a button that meant that
would take the deadline with the date, and the deadline is a promise the user
did not withdraw.

The rule behind the table: **the due date and its time are one fact, and the
reminder, the deadline and `someday` are facts of their own.** Clearing the due date
does not erase the reminder and does not erase the deadline — the user asked to
remove a moment, not a promise and not a bound. There is no domain invariant that
requires the opposite: `Reminder.fireAt` is computed once, when the reminder is
written, and stored; a task whose due date is cleared keeps its reminder row and its
already-computed fire time.

`someday` and the due date are exclusive, because `someday` means *no date*: setting
a date clears `someday`, and the `Когда-нибудь` preset clears the date and the time
and sets `someday`. Clearing the date leaves the task with no date at all — it does
not silently move the task into the `someday` bucket.

### The permission denial is a fact, not a silent failure

When the system notification permission is denied, the reminder row reads
`Уведомления выключены` and offers a settings action that opens the app's
notification settings. The reminder is not written in that state: a stored reminder
that cannot fire is the same lie as an unarmed alarm on the desktop
(`2026-10-07-reminders-capability-gate.md`).

The settings action needs the platform's notification settings screen, which the
repository does not have. It arrives as a port — `NotificationSettingsPort` — with an
Android implementation that opens `ACTION_APP_NOTIFICATION_SETTINGS` and a JVM one
that reports `isSupported = false`, because the desktop has no system notification
settings to open. The row is registered in `shared/src/jvmTest/resources/platform-seams.tsv`
and in the ports table of `docs/PLATFORM-REFERENCE.md` like every other seam.

The desktop's *reminder scheduler* gate is untouched: `TaskRemindersSlot` still
refuses before it persists, and the sheet still shows the platform's own message.

The second gate is read at the same place and for the same reason. The UI cannot be
the only check: the sheet can be dismissed, and the permission can be revoked while
the app runs, so `TaskRemindersSlot` asks the `Notifier` port whether a notification
can be shown *right now* and refuses before it persists. The port is the source of
truth — on Android it reads `POST_NOTIFICATIONS` live, on the desktop it probes
`notify-send` — and the row is registered in
`shared/src/jvmTest/resources/platform-seams.tsv` like every other seam.

Clearing is deliberately not gated, for both gates: a reminder written before the
permission was revoked is still the task's, and the user must be able to remove it.

### The four rules the follow-up round added

The first implementation of this surface shipped four rules the ADR above had not
stated. A review of the shipped behaviour found them, and they are decided here
rather than in the code.

**A relative reminder needs a due date.** The offset counts back from the due
moment, and a task without a date has no such moment. The first implementation
fell back to "now", so a task without a date got a reminder that fired at once:
the person who asked for "15 minutes before" got it immediately, and the row
showed a live reminder for a moment they never chose. `TaskRemindersSlot` now
refuses the write — before the row, before the alarm — and the reminder sheet
says that the date comes first. This is about **creating** a reminder: a reminder
that already exists keeps its stored `fireAt` when the date is removed, which is
the decision the section above took.

**An unknown reminder offset is a reminder.** `ReminderOffset.fromMinutes`
answered `null` for an offset this build does not know, and `null` was what the
UI read as "no reminder". A reminder written by an older build, by a newer one,
or by another client was therefore shown to the user as nothing at all. The
reminder is now a three-state value — no reminder, a known offset, an unknown
offset — and the third state is shown, can be removed, and is never rewritten
into the nearest offset the app happens to offer. `Int.MIN_VALUE` is handled
too: it is the one value whose magnitude does not fit in an `Int`, so the lookup
cannot take its absolute value.

**The schedule is written in one go.** The date sheet used to write the four
scheduling facts one by one — four repository writes, one fact each. If the
second write failed, the task was left with a new date and an old deadline: a
partly saved schedule the user never asked for. The sheet now sends one
`SetSchedule` intent carrying the facts that changed, the slot applies them to
one copy of the task, and the repository writes the task once. A fact the change
does not mention keeps the value it has — the save that changed the deadline
does not touch the date beside it. This is one intent and one write, not a
scheduling framework: the repository already writes a single task row, so there
is no transaction API to reach for.

**A completed task is never overdue.** The deadline row asked only whether the
deadline was before today, so a task that was already done kept saying
`Просрочен` — the app reported the task as late for work it had finished. The
rule is one function: a completed task is not overdue, a task without a deadline
is not overdue, a deadline before today is overdue, a deadline today or later is
not.

## Rationale

**The rows are the surface; the sheets are the editors.** A fact the user cannot see
is a fact they cannot clear, and the three rows make every scheduling fact visible
and every clearing action reachable in one tap. The sheets then only have to be
good at one thing each.

**The app's grid, not Material3's.** The Material3 `DatePicker` is 600 dp tall by
construction and its own action row has to be defended with a `heightIn` cap and a
scroll. At 360×800 dp the sheet that must hold a grid, a time control, a deadline row
and three buttons does not fit around it. The app's own grid is six rows of day
cells and leaves the buttons on the screen — which is what the acceptance criteria
ask for.

**Independence over convenience.** A reminder that is silently dropped when the due
date is cleared is data loss the user did not ask for, and it cannot be undone from
the screen. The same is true of the deadline. The one coupling that *is* documented
is the one that is not a loss but a meaning: the due date and its time are one fact.

**`someday` is exclusive with the date, and says so.** `someday` means *no date*.
Allowing both would put the task in two places at once — dated and deferred — and the
two filters (`NotDeferred`, `TaskFilter.Someday`) would then disagree about which
list it belongs to.

**The permission denial is shown, not swallowed.** The system can refuse the
notification permission at any time, including after a reminder was set. The row
says so in words and offers the way out, because a reminder that cannot fire is a
promise the app cannot keep.

## Consequences

- The detail screen grows by two rows. The due date row is unchanged in position and
  in behaviour.
- The `+ Ещё` parameter sheet of the wireframe stays out of this change: the rows
  above make every planning fact reachable without it, and that sheet belongs to the
  wider Task Detail rework.
- `endDate` gains a meaning at the surface — "the deadline" — on top of the one the
  domain gives it ("the end of the active window",
  `2026-10-01-startdate-vs-duedate-semantics.md`, still *deferred*). That ADR's open
  question is untouched: this change does not decide what `startDate` means against
  `dueDate`, and it does not change `TaskComputed.isActive`. A deadline is written to
  `endDate` because that is the field the domain already has for a bound on the work;
  the deferred ADR still governs the window semantics.
- `TaskEditorSheet.Time` — the sheet variant nothing opened — stays as it is; the
  due time is edited inside the date sheet, and the variant is left for the create
  flow, which has the same gap.
- The reminder's offset labels move from the enum's English `label` to
  `Res.string.*`, so the reminder sheet reads in the device's language. The
  `TestTags.reminderOffsetRadioRow` generator, which slugged the English label,
  stops being the tag of these rows: the option rows carry static constants
  (`TASK_REMINDER_OPTION_15_MIN` and friends), because a tag spelled from a
  translatable string breaks on every device set to another language and a tag
  spelled from the enum's wire name is one the selector gate cannot check.
- The three rows carry a tag per **state** — the value, the empty placeholder and
  the `Когда-нибудь` label — instead of one tag for the label. A flow has to be
  able to tell "the row reads a date" from "the row still reads the
  placeholder", and it cannot do that by matching copy: the copy is localized,
  and a device set to Russian would turn every such assertion into a green that
  asserts nothing.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/detail/TaskSchedulingSheet.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/detail/TaskReminderSheet.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/viewmodel/slot/TaskEntitySlot.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/viewmodel/slot/TaskRemindersSlot.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/notifications/NotificationSettingsPort.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/sheet/MonthCalendar.kt`
- `docs/research/task-detail-ux/implementation/target-ux.md`
- `docs/research/task-detail-ux/implementation/wireframes/task-detail.md`
- Prior: `2026-10-07-reminders-capability-gate.md`,
  `2026-10-01-startdate-vs-duedate-semantics.md`,
  `2026-09-22-task-rich-dates.md`
