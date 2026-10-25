## 1. The vocabulary

- [x] The named sets exist as one enum, in the domain, with lower-case wire names
      (`TaskSelection`)
- [x] The enum's names appear in the tool's schema, and they are exactly the names the
      tool accepts (`TaskReadSurfaceTest`)
- [x] A request may be narrowed by project, by tags and by priorities
      (`TaskSelectionRequest`)

## 2. The rule

- [x] The rule that decides membership is the app's own — `TaskDomain.matchesFilter`
      and `Selector.matches`, written out nowhere else
      (`TaskSelectionResolver`)
- [x] The named sets the domain has a filter for are answered by that filter
- [x] The two it does not — blocked and stagnant — are answered by the same `Selector`
      the agenda uses
- [x] The read starts from the live tasks, because the repository's narrower filters
      and the domain's rules do not always agree

## 3. The tools

- [x] `list_tasks` takes a selection, may be narrowed, and may ask for summaries or full
      cards
- [x] `get_task` returns the same full card the list returns for one task
- [x] The full card carries what only one task can be asked about: description, due
      date and time, priority, tags, pinned, subtasks, checklist progress, blockers
- [x] The answer says how many tasks the selection holds
- [x] The answer says how the selection is doing: overdue, blocked, stagnant
      (`TaskReadSurfaceTest`)

## 4. The cursor

- [x] The cursor lives in the shared module, and the server's copy — which was wired to
      nothing — is no longer the only one
- [x] The cursor names the selection it came from
- [x] A cursor from another selection is refused, and the refusal is said out loud
      (`TaskReadSurfaceTest`)
- [x] A string that is not a cursor is refused, and the refusal is said out loud
      (`TaskReadSurfaceTest`)

## 5. The errors

- [x] Arguments that do not fit the tool are a validation error in the server, not an
      internal failure (`ToolRegistrar`)
- [x] Arguments that do not fit the tool are a readable error in the chat, and the
      message says what to look at (`ToolCallLoopTest`)

## 6. The tests

- [x] The inbox is the app's inbox
- [x] Overdue is the app's overdue
- [x] Blocked is the app's blocked
- [x] Narrowing is an intersection
- [x] A priority the model writes in lower case still matches
- [x] A cursor continues the page it came from
- [x] A cursor from another selection is refused rather than used
- [x] A string that is not a cursor is refused rather than guessed at
- [x] The health of the selection is reported
- [x] The cursor names the selection it came from
