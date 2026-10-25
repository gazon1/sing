# Design — the read surface speaks the app's language

The rationale for this change lives in
`docs/decisions/2026-10-22-the-read-surface.md`. This file records the shape of the
implementation and what would have to be undone.

## The shape

Three pieces, in three places:

- `feature/tasks/domain/logic/TaskSelection.kt` — the vocabulary (`TaskSelection`), the
  narrowed request (`TaskSelectionRequest`) and the rule that decides membership
  (`TaskSelectionResolver`).
- `feature/ai/tools/` — the tools themselves, and the DTOs they hand back
  (`TaskToolDtos.kt`, `TaskToolSupport.kt`).
- `core/ai/TaskPageCursor.kt` — the cursor, which the server module used to keep for
  itself and never used.

The selection lives in the domain because it is a domain question: "which of the user's
tasks are overdue" is the same question the agenda and the statistics screen ask. The
tools are in the AI layer because they are one reader of that question, and the next
reader — a screen, a command, the server's tools — gets the same answer for free.

## The rule is the app's rule

`TaskSelectionResolver.matches` is built out of `TaskDomain.matchesFilter` and
`Selector.matches`. Nothing about "overdue" is written twice.

The read starts from `TaskFilter.All` — the live tasks — and filters in memory, because
the repository's narrower filters and the domain's rules do not always agree (the
repository answers `ByStatuses` with nothing at all, and answers `Inbox` more widely
than the domain does). Pushing a filter down would buy a narrower query at the price of
a narrower *answer*.

## The cursor carries a fingerprint

`TaskPageCursor` holds an offset and a fingerprint of the selection and its narrowing.
The fingerprint is what makes a cursor safe to hand back: a cursor from another
selection is refused, and the refusal is said out loud in the answer
(`cursorWarning`), because a reader that was silently given the wrong page would have
no way of knowing.

## Rollback risk

**Low.** The change adds a domain file, a cursor file, a DTO file and a support file;
rewrites two tools; and changes one `catch` in the server's registrar. Undoing it means
restoring the two tools and the registrar, and deleting the new files. No stored data
changes, and no other caller depends on the new shapes.

The one thing that is not trivially undone is the tool constructors: the read tools now
take a `Clock` and a `TimeZoneProvider`, so the DI bindings and the tests that build
them by hand change with them. Both are in the same commit as the tools, so they are
undone together.
