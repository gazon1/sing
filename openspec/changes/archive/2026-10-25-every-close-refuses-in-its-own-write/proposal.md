# Change: every-close-refuses-in-its-own-write

## Why

The close rule was consolidated into the statement: completing a task is refused
while a blocking prerequisite is open, and the write itself decides. Two paths
still closed tasks without going through that statement:

- the **recurring close** rolled the recurrence forward with a full-row update —
  a write that stamps `completed_at` whether or not anything is in the way. The
  slot worked around it with a check that ran *before* the write, which is the
  check-then-write the consolidation was about: the window between the two was
  where another device reopened the prerequisite.
- the **bulk close** reached the user as the raw message of an exception in a
  snackbar. A single close asked a question with a dialog; a bulk close
  reported a fact.

So the rule was the rule in one place and a convention in the two a user would
notice.

## What changes

- **The recurring close refuses in its own write.** The completion of an
  occurrence is the guarded statement, and the roll-forward is a narrow write
  beside it. The whole roll-forward is one transaction, so a refusal leaves
  nothing behind — no completion, no copies of the missed occurrences, no new
  date.
- **The check before the recurring write is gone.** The user is asked when the
  write refuses, the same way an ordinary close is.
- **The bulk close asks the same question.** The tasks in the way are named, the
  selection is held, and the user's answer decides.

## Impact

- Affected specs: `tasks`
- Affected code: `feature/tasks`, `feature/agenda`, `core/database`
- Schema: unchanged (no migration)
- Wire format: unchanged
