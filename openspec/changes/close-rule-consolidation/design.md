# Design — close-rule-consolidation

## Context

The blocked-completion guard (ADR `2026-10-21-blocked-completion-guard`) made the rule
visible. This change gives it one owner and moves the enforcement into the write.

## Goals

- One owner for the blocking predicate.
- No window between "the close is allowed" and "the close".
- The bulk close cannot go around the question.
- The project's cascade lands whole or not at all.
- The domain says what the dependency set holds.

## Decisions

### D1 — `Blocking` owns the rule

`Blocking.activeBlockers`, `Blocking.activeBlockersByTask`, `Blocking.isBlocked` and
`Blocking.blockedTaskIds` are the four forms of one rule. `TaskComputed` keeps the
predicates about time and shape.

**Why:** the badge and the confirmation once answered the same question differently.
Two owners is one bug waiting for a change.

### D2 — the write refuses

`TaskRepository.complete(id, force)` completes a task only while no blocking
prerequisite is open. The condition is inside the SQL statement
(`markCompleteIfNotBlockedForUser`), so the answer and the write are the same fact.
`force = true` is the confirmed path.

**Why:** a check-then-write is two statements with a window; the window is where
another device reopens the prerequisite.

### D3 — the surfaces react to the refusal

The agenda, the task card and the subtask checkbox try the write and ask when it
refuses. The recurring path keeps checking first — rolling a recurrence forward is a
different write, and the refusal is not part of it.

### D4 — the bulk close asks

`TaskMutationsUseCase.bulkComplete` resolves the blocking edges of the selection and
stops with the names of the tasks in the way, writing nothing.

### D5 — the cascade is one transaction and reads narrowly

`CloseProjectUseCase` wraps the writes in one `UnitOfWork`, and reads the project's own
tasks plus the prerequisites of those tasks (`blockedTaskIdsForUser`), not the whole
task list.

### D6 — reopening a task reopens its project

`TaskCompletionUseCase.reopen` takes the task back into work and opens its project when
that project was closed.

### D7 — the domain name says what it holds

`Task.dependsOn` → `Task.blockedBy`, with `@SerialName("dependsOn")` so the wire name
is unchanged. `TaskWireFieldNamesTest` pins both directions.

## Risks

- The refusal now lives in SQL, so it is only as correct as the statement. It is
  asserted against a real database (`BlockedCompletionWriteTest`), not against a fake.
- A caller that wants the old behaviour has to ask for `force = true` explicitly, which
  is the point.
