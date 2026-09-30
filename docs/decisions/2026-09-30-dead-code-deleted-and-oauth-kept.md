---
title: "MR-4 dead-code sweep: what was deleted, and three things that look deletable but are not"
date: 2026-09-30
status: accepted
tags: [logging, koin, kermit, debugging]
---

## Context

MR-1 through MR-3 fixed and wired the logging subsystem. MR-4 is the
follow-up sweep: delete code that is fully implemented, compiles, has
tests, and is reachable from nowhere.

The project's own tooling names this defect class — AGENTS.md calls it
"фича готова, но ничего не делает" and `scripts/find-unwired-surfaces.py`
exists to find it. That script detected four shapes. MR-4 adds a fifth
(`log-writer`) and then acts on the results.

## Decision

### Deleted

| Symbol | Why it was dead |
|---|---|
| `TaskEditorBody` + 4 private row composables + 3 label helpers (`TaskEditorBody.kt`, 380 lines) | No call site. The whole file is one public composable plus `private` helpers only it uses. |
| `TaskEditorMenuHost` | No call site. The overflow `DropdownMenu` it hosted is built inline in the live editor. |
| `TaskMoreOptionsRow` | No call site. |
| `TaskPriorityCard` | No call site. `TaskAttributeCard` is used directly. |
| `RoomNotesRepository` (`feature/notes/NotesRepository.kt:110`, 352 lines) | Duplicate of `feature/notes/data/NotesRepositoryImpl.kt`, which is what `NotesDiModule` actually binds. The file now holds only the `NotesRepository` interface. |
| `TokenError`, `RedirectState` (`core/auth/oauth/OAuth.kt`) | No call site. |
| `FakeNotificationPort` | No call site, not even in tests. Tests construct their own doubles inline. |
| `UpdateTagGroupUseCase`, `SetProjectInheritedGroupsUseCase` + their Koin bindings | No injection site. |
| `LogExporter`, `LoggerHolder` | Deleted in MR-2. |

Net: **~800 lines of code removed**, no behaviour change.

### Kept — three things that look dead and are not

**`TaskMutationsUseCase` is kept.** It has no injection site, which is the
signature of an unwired surface, and it was on the deletion list. But it is
not leftover: `2026-09-05-refactoring-summary.md` records that it was
*created* by collapsing five pass-through use cases, and
`2026-09-07-dogfooding-followups.md` records that `delete`/`toggle`/
`togglePin` were stripped from it while `bulkComplete` and `bulkDelete` were
deliberately **kept** because they enforce fail-fast atomicity that the
repositories do not. Six tests cover that atomicity. Deleting it would revert
a decision made deliberately and documented three times. The real finding is
upstream of it: **bulk operations have no UI**, so the atomicity guarantee is
untested in production. That is a product gap, not dead code.

**`UpdateTagUseCase` is kept** — MR-5 injects it.

**`OAuth.kt`'s remaining symbols are kept.** See below.

### The fifth script form: `log-writer`

`find-unwired-surfaces.py` now reports a `LogWriter` subclass that never
reaches `Logger.setLogWriters(...)`. `FileLogWriter` sat unwired from
2026-09-23 until MR-2 wired it on 2026-09-30 — a correct implementation that
had never received a single line. The check exists so the next one is caught
in seconds rather than a year.

Two things the first implementation of the check got wrong, both worth
recording because they are the kind of bug that makes a linter worse than
nothing:

- A naive `setLogWriters\s*\(([^)]*)\)` regex reads only the first
  argument.** The arguments are themselves constructor calls, so `[^)]*`
  stops at the `)` of `RedactingLogWriter(ColorizedWriter()` and every
  argument after it is invisible. The check then reported `FileLogWriter` —
  which *is* wired, via the second argument — as unwired. Fixed by matching
  the balanced parenthesis run in `set_log_writers_args()`.
- A writer can be registered through a local alias. Both `LogBootstrap`
  files do `val fileWriter = FileLogWriter(dir)` and then
  `setLogWriters(..., fileWriter)`, because the JVM one needs the reference
  for its shutdown hook. Matching only inline constructor calls misses this.
  `WRITER_ALIAS` maps the alias to its writer type.

The form was verified against a synthetic `OrphanWriter` (correctly reported)
and against the real tree (clean), before any code was deleted. Test doubles
in `src/*Test/` are excluded — they are passed to the class under test, not
to Kermit, and flagging them would be noise.

## Rationale

The plan this MR follows listed `TaskMutationsUseCase` for deletion because
a reference count of one (its own DI binding) looks identical to
`UpdateTagGroupUseCase`'s reference count of two. Reference counting does not
distinguish *never wired* from *deliberately staged*. Checking the decision
log before deleting is what separates them, and it changed the outcome for
both this use case and, below, an entire file.

## Consequences

- `TaskEditorBody.kt` was 380 lines of a fourth parallel row implementation.
  The live editor builds its rows another way; the dead copy was a standing
  invitation to edit the wrong one.
- `RoomNotesRepository` and `NotesRepositoryImpl` were two full Room
  implementations of the same interface in the same feature. Only one was
  bound. This is the most dangerous shape in the list — a reader would
  reasonably assume the class next to the interface is the one in use.
- `core/auth/oauth/OAuth.kt` is an entirely unwired file, not just the two
  symbols the plan named.** `OAuthConfig`, `OAuthResult`, `OAuthTokenData`,
  and `toOAuthTokenData` have zero references outside the file — no VM, no
  repository, no test, no Koin binding. Only `TokenError` and
  `RedirectState` were removed, because deleting the whole file is a
  statement about the product ("we are not doing OAuth") that a dead-code
  sweep should not make on its own. The file stays until someone decides
  whether Supabase OAuth is still planned.
- `FakeNotificationPort` was a ready-made double that no test used. If
  notification-related tests appear, an inline double will be written again;
  that is a better outcome than a shared fake with no owner drifting out of
  date.
- The `log-writer` form is a heuristic, not a proof. A writer stored in a
  mutable collection and registered later would be reported falsely. It errs
  toward reporting, which is the right bias for this script, but it means a
  future finding may need a human to confirm it.

## Links

- `scripts/find-unwired-surfaces.py` — the `log-writer` form and `set_log_writers_args`
- `2026-09-05-refactoring-summary.md` — why `TaskMutationsUseCase` exists
- `2026-09-07-dogfooding-followups.md` — why `bulkComplete`/`bulkDelete` were kept
- `2026-09-30-file-logging-wired.md` — the wiring that made the fifth form necessary
