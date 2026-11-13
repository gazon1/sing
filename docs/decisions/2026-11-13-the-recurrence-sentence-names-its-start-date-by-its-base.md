---
title: "The recurrence sentence names its start date by the rule's base"
date: 2026-11-13
status: accepted
tags: [tasks, recurrence, i18n, ui]
---

## Context

The task detail row reads a recurring rule as a sentence: "Every 3 days, from the
due date, starting 12 Nov 2026". Three things about that sentence were decided
by accident rather than by a rule.

**The start date was the due date, always.** `TaskDetailContent` passed
`ui.task.dueDate` into the sentence for every rule, and the sentence named it
"starting". For `FROM_COMPLETION` that is a false statement: the series is
measured from each completion, so the anchor moves with every occurrence and
the series has no date it starts on. The task's `completedAt` is the *last*
completion, not the first, and it does not exist at all while the rule is being
chosen — so it cannot be the series' start either.

**The sentence was assembled in Kotlin.** The parts were joined with
`listOfNotNull(core, base, starting, until).joinToString(", ")`. That fixes one
word order and one punctuation for every language, and Russian needs a
different one: it says "начиная с" where English says "starting", and "до"
where English says "until".

**An unknown unit read as days.** The formatter matched the unit by hand and
fell through to `else -> day`, so a rule stored as "every 3 months" read as
"every 3 days" — a wrong rule, not a rough one.

## Idea

- One place decides which date the sentence names, and it decides it from the
  rule's base, which is what the date means.
- The formatter receives a date that is already correct; it does not guess.
- The order of the parts of the sentence belongs to the language, so it lives in
  the resource bundle.
- The unit is read from the unit's *type*, so the compiler enforces that every
  unit the domain can hold is handled.

## Decision

**The start date comes from the base, and only from the base.**

| Base | The date the sentence names |
| --- | --- |
| `FROM_DUE` | the task's due date |
| `CATCH_UP` | the task's due date |
| `FROM_COMPLETION` | none — the sentence names no date |

`CATCH_UP` measures its occurrences from the due date, not from the
completion: catch-up fills the gaps between *scheduled* occurrences, and a
scheduled occurrence is scheduled from the due date. It hangs on the due date
the way `FROM_DUE` does, and the sentence says so with its own base words.

A rule with no date to show shows no date. The sentence says what repeats and
what it is measured from, and nothing it cannot say.

**One function decides it.** `recurrenceStartDate(base, dueDate)` in
`RecurrenceFormatters.kt` is the only place the mapping lives. The public
`RecurrenceSentence(spec, dueDate)` pairs a rule with a task's context and is
what both the task detail row and the picker's preview call, so the two cannot
drift apart. The picker's `anchorDate` — which falls back to today for the
next-occurrence preview — stays separate from the `dueDate` the sentence reads:
today is not a date the task has.

**The sentence is a resource template.** Four templates cover the combinations
that exist — rule, rule with a start, rule with an end, rule with both — and
each language writes its own order and punctuation in its own bundle. There is
no i18n framework and no template DSL: four strings and one `when`.

**The unit is read by type.** `DateTimeUnit.DateBased` has exactly two
subclasses, `DayBased` and `MonthBased`, so the `when` over them is
compiler-exhaustive and there is no fallback branch to be wrong about. Seven
days is a week and twelve months is a year; three days is three days and three
months is three months.

## Rationale

The sentence is a claim about the task. A rule measured from completion that
says "starting 12 Nov 2026" tells the person something false about their own
task, and the fix is not a better wording but a truer claim: the rule has no
start date, so the sentence does not name one.

Putting the order of the words in the bundle is what makes a third language a
bundle rather than a branch in Kotlin. The four templates are the whole
mechanism, because four is how many combinations the domain actually has.

Reading the unit by type removes a class of bug instead of papering over it:
there is no longer a path where an unknown unit silently becomes days.

## Consequences

- A `FROM_COMPLETION` task with a due date shows no date in its recurrence row.
  That is the intended change; the review comment on #2889 asked for it.
- The picker's preview and the task detail row read the same sentence from the
  same function, and a test compares them.
- A rule with an unusual unit reads honestly ("Every 3 months") rather than
  wrongly ("Every 3 days").
- `RecurrenceLocale` is gone: the sentence is no longer assembled from
  pre-built parts, so the struct that held them had no reason to exist.
- The unit's mapping is a property of the rule, so a rule read in another
  language reads the same rule.

## Links

- PR https://github.com/gazon1/sing/pull/2889
- `2026-09-26-internationalization.md` — the app's user-facing text lives in
  Compose resources
