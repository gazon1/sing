# Change: a-closed-project-is-reachable

## Why

Closing a project was added with the blocked-completion guard: a project whose
work is finished leaves the project list, and its tasks are closed with it. But
a closed project had nowhere to go — the only way back was through one of its
tasks, and a project with no tasks has none. Closing the wrong project was
permanent, and the user was not even told that the project still existed.

## What changes

- **A closed project has a list of its own.** The projects screen shows the
  projects the user closed, most recently closed first, below the open ones.
- **The way back is a button.** Each closed project carries an action that opens
  it again, and the project returns to the project list.
- **The list is shown even when there are no open projects**, because "the list
  is empty" and "the account is empty" are different screens.

## Impact

- Affected specs: `projects/project-closing`
- Affected code: `feature/projects`, `core/database`
- Schema: unchanged (no migration)
- Wire format: unchanged
