# Quickstart: Validating This Feature End to End

How to prove each part works, in the order that fails fastest. Every check below is a
runnable command or an observable outcome — none of them needs implementation knowledge.

The two things this feature is judged on are: **a repeating task's future is visible as
real tasks**, and **a list row says what it is** without being opened.

## Prerequisites

- A working development environment (the project's standard setup).
- For the manual run at the end: the Desktop app, and a task that repeats.

## Ground rules

- Build with `./gw`, not `./gradlew` — parallel checkouts share a Gradle home and the
  shared one serialises on a lock.
- Never run two Gradle invocations at once, in this checkout or any other.
- Run detekt for `:shared` first; it holds most of the code and fails fastest.
- Every test class carries a tag; a class without one is silently excluded from CI. New
  classes follow the tag dictionary (`Maestro/TAGS.md` for the UI tags).
- The two tests that encode the old behaviour are named below. They are **expected to
  fail until the implementation lands** — they are the specification's executable form,
  not a regression.

---

## Part B — The repeat badge

### B1. The badge is derived, not declared

The agenda builds each row's `TaskUi` by hand (`AgendaContent.kt:401`). Assert that the
row carries `isRecurring = true` for a task that has a rule, and `false` for a task that
does not.

| Task | Expected |
|---|---|
| has a `RecurrenceSpec` | `isRecurring == true` |
| has no rule | `isRecurring == false` |
| is an occurrence of a recurring series | `isRecurring == true` |
| is an occurrence of a multi-date series (no rule) | `isRecurring == false` |

Failing this is the whole point of Part B: the flag is hardcoded to `false` today, so
the icon that already exists in `TaskMetaRow.kt:65-73` has never been drawn.

### B2. The badge shows even when the row has nothing else to say

A repeating task with **no due date, no project, not blocked and not in a series** must
still show its meta row. Both gates — `hasMeta` (`TaskRowContent.kt:81`) and
`hasSomethingToShow` (`TaskMetaRow.kt:48`) — have to open for `isRecurring`.

Desktop Compose UI test: assert the repeat icon is displayed for such a row.

### B3. The badge is distinguishable and named

- The repeat icon must not be mistaken for the blocked marker or for the project badge.
- It must carry a content description naming the repeat.

---

## Part C — The project badge

### C1. The project reaches the row

| Task | Expected |
|---|---|
| belongs to a project | the badge shows the project's **name** |
| belongs to a project with a colour | the badge uses that colour |
| belongs to no project | no badge |
| belongs to a **closed** project | the badge still shows the name — the lookup must include closed projects, or the badge vanishes silently |

The last row is the one that fails by default: `observeAll()` filters
`closed_at IS NULL`.

### C2. The placeholder text is gone

No row may print the literal "Без проекта" next to its date. Today every agenda row
with a due date does, because the agenda passes `project = null`
(`AgendaContent.kt:404`) and the row substitutes the placeholder (`TaskRowContent.kt:86`).

Desktop Compose UI test: assert the string does not appear in the agenda.

### C3. The lookup is not per row

Assert that a list build resolves the projects **once**. The observable form: a list of
one thousand rows performs one projects lookup, not one thousand.

---

## Part A — The materialised series

### A1. The window is a pure function

`RecurringSeriesWindow.plan(rule, anchor)` — assert with no clock and no repository:

| Rule | Anchor | Expected window |
|---|---|---|
| weekly | a Monday | the Mondays inside 14 days **after** the anchor, plus the first Monday past the horizon. The anchor itself is not in the window — it is the occurrence that is already there. |
| daily | any day | at most 10 dates, nearest first |
| yearly | any day | exactly one date — the next year. **A rare rule must still have a next occurrence.** |
| already exhausted | any day | nothing — the window honours the rule's end date |

Assert that the same rule at the same anchor gives the same dates twice, and that the
result does not change when the calendar day changes — the window is anchored at the
occurrence, not at today.

### A2. A rule produces visible future occurrences

Create a task with a weekly rule, due today. Assert that:

- the task itself is the current occurrence, on today's date;
- the series holds the future occurrences as **open** rows, dated in the future;
- those rows carry the same title, project, tags, priority and accent colour — the same
  work;
- they appear in the Upcoming agenda, in the section of their own date.

This is the check the user's complaint is about: today the future does not exist as
anything the user can see.

### A3. Closing an occurrence does not move it, and does not shrink the future

Close the current occurrence. Assert that:

- the occurrence stays **on its own date** and stays **closed** — the date does not move;
- the count of open future occurrences does not decrease — under "from due date" the
  dates themselves do not move, and under "from completion" a late close moves them
  (see A6b);
- the occurrence can be reopened, and the series does not fall apart.

The date moving on close is the defect: the old roll-forward closed the row and then
moved its date, and `setDueDateForUser` never touches `completed_at` — so the row ended
up closed *and* dated in the future, which is why no future occurrence ever appeared as
an open task.

### A4. A refusal changes nothing

Close an occurrence that has an open prerequisite. Assert that the refusal happens, and
that the series is byte-for-byte what it was: no occurrence closed, no occurrence
created, no date moved.

### A5. The three bases agree on everything but the date

| Base | The occurrence | The next occurrence is dated from |
|---|---|---|
| `FROM_DUE` | closed, on its own date | the occurrence's due date |
| `FROM_COMPLETION` | closed, on its own date | the occurrence's completion date |
| `CATCH_UP` | closed, on its own date | the occurrence's due date, with the missed occurrences materialised as closed rows first |

The bases must not differ in *whether the occurrence is closed* any more — that
difference was an artefact of the one-row model.

### A5b. A rule that has run out closes the occurrence and stops the series

Give a task a rule that ends before its next occurrence, and close it. Assert that the
occurrence is **closed on its own date**, that the series holds no further occurrence,
and that **nothing was archived** — the task must not appear in the trash. (Today it is
soft-deleted; the new behaviour is a closed occurrence and a series that stops growing.)

### A5c. A rule set on the create screen survives the create

Create a task from the create screen with a weekly rule and today's date. Assert that
the created task **carries the rule** — today the create path drops it, because the
series planner clears `recurrence` on every row it writes, and a task created with
"repeat every week" comes out with no rule at all.

### A6. Changing the rule re-derives the future

Change a weekly rule to a monthly one. Assert that the future occurrences the weekly
rule produced are gone, the monthly ones are there, and the **closed** occurrences are
untouched — history is not rewritten.

### A6b. The base decides which date the rule is measured from

| Base | Close three days late | Expected |
|---|---|---|
| `FROM_DUE` | the next occurrence is due on the date the rule gives | the window does not move |
| `FROM_COMPLETION` | the next occurrence is due three days later than planned | the window moves by three days |

For `FROM_COMPLETION`, assert that the rows that survive the move keep their own
per-occurrence state — a reminder, a checklist. The move is a change of date, not a
rebuild.

### A7. The ceiling rotates rather than refuses

Fill a series to thirty rows and close one more. Assert that the series stays at
thirty, that the **oldest closed** occurrence has left the series, and that it is still
there as an ordinary closed task — nothing is destroyed. Assert also that it carries
**no rule**: a closed row that keeps a rule and has no series id is the shape the
launch repair adopts into a new series, so a rule that stayed would make the ceiling a
loop — the next launch would grow a fresh series around the rotated row.

### A8. The legacy repair

Start from a database that has a repeating task in the old shape: one row, a rule, no
series id, closed, dated in the future. Launch the app. Assert that:

- the row now belongs to a series and is **open** — it is the current occurrence;
- the series holds the future occurrences;
- the repair is idempotent — a second launch changes nothing;
- a repeating task closed in the **past** stays closed (FR-036: only a series with no
  open occurrence gets one reopened).

### A9. The calendar follows the same rule

A recurring series is exported as one event per **open** occurrence — the same
«one row — one event» rule a multi-date series already follows. The task's own rule is
not exported as a calendar rule; the calendar's recurrence still comes from a
reminder's pattern.

### A10. An occurrence that leaves the series stops repeating

Take one occurrence out of a recurring series. Assert that the row keeps its own date and
its own state, that it carries **no rule** any more (FR-049 — a row that keeps a rule it
will never honour would carry the repeat badge forever), and that the remaining members
still carry the rule and still repeat.

Then take one occurrence out of a recurring series of **two**. Assert that the row that
stays behind stands alone and carries **no rule** either — a row that stands alone with a
rule is the shape the launch repair adopts into a new series, so the detach would undo
itself at the next launch.

### A11. Two kinds are not joined into one series

Join a recurring series with a series of picked dates. Assert that the join is refused,
that the two series stay two, and that the user is told the kinds differ (FR-050). Then
join two recurring series carrying different rules, and assert the same. A join of two
series of the same kind still succeeds, and the dates of both series survive it.

---

## Cross-cutting gates

```bash
./gw :shared:detekt
./gw :shared:jvmTest
./gw :desktopApp:test
singularity-adr validate
openspec validate --changes
```

The ADR validator must pass: the feature ships a **superseding** ADR, and the superseded
file must be untouched.

## Definition of done per part

| Part | Done when |
|---|---|
| **B** | a repeating task's row shows the repeat badge; a one-off task's row does not; the badge shows even on a row with nothing else to say. |
| **C** | a task's row shows its project's name and colour; no row prints "Без проекта"; a closed project's badge does not vanish. |
| **A** | a rule produces real future occurrences visible in Upcoming; closing one leaves it on its date and does not shrink the future; a refusal changes nothing; the legacy data is repaired at launch without losing anything. |
