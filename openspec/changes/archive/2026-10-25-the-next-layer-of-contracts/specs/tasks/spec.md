# tasks — Observable Behavior

**capability:** `tasks` | **status:** accepted

---

## ADDED Requirements

### Requirement: REQ-TASKS-REWRITE-1

The task detail SHALL tell a replacement from a keystroke.

The task detail holds a **bridged** text field — a Compose `TextFieldState`
fed by a draft in the ViewModel — and the draft observes a task the repository
owns. That task can be rewritten from outside: a sync from another device, a
change made on another screen. A replacement and a keystroke arrive the same
way, as a new value, so the draft SHALL carry a revision token alongside the
value, and the token SHALL move only when the content is replaced.

The draft SHALL tell four cases apart:

- **the first seed** initialises the draft;
- **the echo of the draft's own save** — the draft already holds exactly that
  text — SHALL change nothing and SHALL NOT move the revision;
- **a replacement from outside** SHALL be taken, and SHALL move the revision;
- **the user's unsaved edits** SHALL win: a dirty draft SHALL NOT be re-seeded,
  and the replacement SHALL wait for the draft to become clean.

The repository's own echo SHALL mark the draft clean: when the repository holds
exactly what the draft holds, the write landed, and the baseline moves with it,
so that a later replacement is not refused as "the user is typing". A write
that landed while the user kept typing does not match the draft, so the newer
text wins and the draft stays dirty.

The task's title and description fields SHALL pass the draft's revision to the
bridge, so that the bridge's echo guard is lifted for a replacement that lands
on a value the field produced earlier.

#### Scenario: A task rewritten from outside

- **Given** the task detail holds a task and the draft is clean
- **And** the task's title is rewritten by something outside the screen
- **When** the screen observes the new title
- **Then** the title field shows the new title
- **And** the draft's revision has moved

#### Scenario: A rewrite that arrives while the user is typing

- **Given** the task detail holds a task with unsaved edits
- **And** the task's title is rewritten by something outside the screen
- **When** the screen observes the new title
- **Then** the title field still holds the user's edits
- **And** the draft's revision has not moved

#### Scenario: The screen's own save

- **Given** the task detail holds a task and the draft is clean
- **When** the user edits the title and the write is accepted
- **And** the repository echoes the saved task back
- **Then** the draft's revision has not moved
- **And** the title field is unchanged

#### Scenario: A replacement that lands on a value the field produced

- **Given** the user typed a title earlier and the field pushed it
- **And** the draft was saved, so it is clean
- **When** a sync rewrites the task's title to that same value
- **Then** the revision moves, and the field accepts the value
