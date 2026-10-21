# The profile reset — the inventory of the tables it does not reach

## What a profile reset is

A profile reset empties the active profile's content so that the next thing
that opens the app starts from nothing. The app reaches it through the debug
deep link `todo-debug://seed?reset=content`, and the E2E suite reaches it
between flows: a flow that types a title into a field leaves a task behind, and
the next flow — the one that asserts the list is empty — would otherwise fail on
the previous flow's row.

The reset is not "delete everything". It is "delete everything this profile
owns, and nothing else". Those are two different claims, and the second one is
the one that is easy to break.

## Why a list of exceptions is dangerous

A list of exceptions is dangerous for one reason: nobody notices when it goes
stale.

A table added to the schema is not a row in a document. It is a row in a
database. So the moment the schema grows and the reset does not, the document
that says "the reset reaches the whole profile" is still true — in the
prose — while the code has quietly stopped reaching it. The next flow finds a
leftover row, the failure looks like a flaky test rather than like a gap in a
list, and the gap gets worked around in the test instead of closed in the
reset.

A wrong entry is worse than a missing one. A table the reset does not own
being wiped destroys state the E2E run depends on — the sync cursor, the
profile itself — and the damage is invisible until much later. So the rule
below is deliberate: **a table that cannot be justified from the code is left
alone and said so, not guessed at.**

## The rule this inventory follows

A table is **profile content** when its rows belong to the active profile *and*
a user would expect a profile that has just been reset to have none of them.

Three consequences worth stating, because they decide most of the table below:

- **A table that no profile owns is not wiped by a profile-scoped reset.** The
  scope of the reset is the profile-scoped user id. A table with no owner
  column has no rows that id can match.
- **The reset deletes the profile's rows, not the live rows.** Archived,
  trashed and soft-deleted rows go too. They are the rows a previous run left,
  and a sweep built on the query that lists "the tasks" does not reach them.
- **The sync engine's own bookkeeping is not content.** It is the engine's
  record of the outside world and of its own progress, and two of those tables
  make the reset worse rather than better when they are cleared.

## How this inventory was made

From the `@Entity` annotations in `shared/src/commonMain`, read one table at a
time, and for each one asked: whose rows are these, and would a profile that
has just been reset have them?

The verdicts are not a judgement call left to the reader: they are stated
twice, once here and once in `ProfileWipePlan` (`core/wipe/ProfileWipePlan.kt`),
and `scripts/ci/check-profile-reset-inventory.py` fails when the two, the Room schema
and this document disagree. The gate is the point of the exercise — an
exception list that nothing checks is an exception list that rots.

## The inventory

Thirty tables. Twenty-one are wiped, nine are not, and none is unresolved.

### 1. Wiped — profile content the reset reaches

| Table | Verdict | Why it is profile content | How the reset reaches it |
|---|---|---|---|
| `tasks` | wiped | The task is the profile's content proper. | `TaskRepository.deleteAllForCurrentProfile()` |
| `task_tags` | wiped | The link between a task and a tag; it hangs off a task and goes with it. | `TaskRepository.deleteAllForCurrentProfile()` |
| `task_dependencies` | wiped | The link between a task and what it depends on. | `TaskRepository.deleteAllForCurrentProfile()` |
| `checklist_items` | wiped | A checklist item belongs to a task and is a row the next flow reads through the task. | `TaskRepository.deleteAllForCurrentProfile()` |
| `notes` | wiped | A note is the profile's content proper. | `NotesRepository.deleteAllForCurrentProfile()` |
| `projects` | wiped | A project is the profile's content proper. | `ProjectsRepository.deleteAllForCurrentProfile()` |
| `project_tag_groups` | wiped | The link between a project and the tag groups it inherits; it hangs off a project. | `ProjectsRepository.deleteAllForCurrentProfile()` |
| `agenda_views` | wiped | A saved agenda view is something the user made. | `SavedAgendaViewsRepository.deleteAllForCurrentProfile()` |
| `tags` | wiped | A tag is something the user made, and it is its own row — it survives a wipe that only follows tasks. | `TagsRepository.deleteAllForCurrentProfile()` |
| `tag_groups` | wiped | A tag group is something the user made, and it is its own row. | `TagGroupRepository.deleteAllForCurrentProfile()` |
| `task_reminders` | wiped | A reminder outlives the task it belongs to — the row is not removed when the task is. | `ReminderDao.deleteAllForUser(userId)` |
| `project_reminders` | wiped | A project reminder outlives the project it belongs to. | `ProjectReminderDao.deleteAllForUser(userId)` |
| `time_entries` | wiped | A time entry is a record the user made against a task, and it is its own row. | `TimeEntryDao.deleteAllForUser(userId)` |
| `saved_searches` | wiped | A saved search is something the user made, and it is a standalone row — it does not hang off a task. | `SavedSearchDao.deleteAllForUser(userId)` |
| `attachments` | wiped | An attachment belongs to a task and is shown on it. | `AttachmentDao.deleteAllForUser(userId)` |
| `attachment_annotations` | wiped | An annotation belongs to an attachment; a task-less attachment still carries them. | `AttachmentAnnotationDao.deleteAllForUser(userId)` |
| `ai_proposal` | wiped | A pending AI proposal from a previous run shows up in the next run's proposal list. | `ProposalDao.deleteAllForUser(userId)` |
| `ai_proposal_item` | wiped | The items of a proposal. They carry no owner of their own — ownership lives on the parent proposal — so they are reached through it. | `ProposalItemDao.deleteAllForUser(userId)` |
| `calendar_import_event` | wiped | An event offered for import; a leftover one is offered again to the next flow. | `CalendarImportEventDao.deleteAllForUser(userId)` |
| `calendar_sync_task_map` | wiped | The mapping from a task to the calendar event it owns. The task is gone; the mapping must go with it, or the next sync believes it still manages a task that no longer exists. | `CalendarSyncTaskMapDao.clearAll(userId)` |
| `google_event_shadow` | wiped | The record of the calendar event this profile manages, kept so the planner can tell a change from a deletion. With the task gone, a leftover shadow makes the planner believe the event is still managed and keep it alive. | `GoogleEventShadowDao.deleteAllForUser(userId)` |

### 2. Not profile content — the tables the reset leaves alone

| Table | Verdict | Why a profile reset must not touch it |
|---|---|---|
| `profiles` | not profile content | It is the profile row itself. A reset empties a profile's content; it does not delete the profile, and the row carries the identity the rest of the profile's rows are scoped by. |
| `llm_usage` | not profile content | The usage log is an accounting record of what the AI cost — an append-only ledger of calls, tokens and micros. It is not something the user made and does not want deleted; it is the thing that makes the cost of the feature visible. |
| `remote_configs` | not profile content | The account's server configuration (the Supabase URL and the anonymous key). It is configuration, not content, and it is not scoped to a profile. |
| `remote_config_cache` | not profile content | A cache of the configuration above. A cache is rebuilt on demand; deleting it costs a fetch and buys nothing. |
| `sync_state` | not profile content | The sync engine's own bookkeeping: the last log sequence number it applied, and whether the first seed is done. Clearing it makes the next pull re-download everything the reset just removed — the reset would undo itself. |
| `sync_shadow` | not profile content | The sync engine's record of what the server is known to hold, keyed by (owner, profile). It is the base the planner diffs against, not content. |
| `sync_outbox` | not profile content | The queue of patches waiting to go to the server. The reset's own repository deletes enqueue there — that is how the server learns the tasks are gone — so clearing the queue after the wipe would discard the patches that make the wipe stick. |
| `sync_dead_letter` | not profile content | The shelf of patches that could not be delivered. Its own documentation says a change the user made must not be destroyed because the system could not deliver it. |
| `calendar_sync_state` | not profile content | The calendar provider's incremental cursor — the sync token, and when the provider was last read. It is the engine's bookkeeping about the outside world; clearing it costs a full re-listing of the provider's calendar and buys no isolation. |

### 3. Unresolved

None. Every table in the schema has a verdict, and every verdict is justified
from the entity definitions and the code that reads and writes it.

A verdict of "unresolved" would be honest where the code does not say what a
table is for. It would also mean the table stays in the database across a reset,
which is a leak — so it is a verdict of last resort, and the gate treats it as
"not wiped" rather than as an excuse.

## Which tables the reset reached before this change

Before it grew, the reset reached five things through four repository calls:
tasks, notes, projects and saved agenda views (and the drafts, which are not in
the database at all). Those four calls also took the rows hanging off them —
the task's tag references, its dependencies and its checklist items, the
project's tag-group links — so eight of the thirty tables were reached, and
twenty-two were not.

Of those twenty-two, thirteen were profile content a flow can leave behind
without creating a task, and the reset now reaches them: `tags`, `tag_groups`,
`task_reminders`, `project_reminders`, `time_entries`, `saved_searches`,
`attachments`, `attachment_annotations`, `ai_proposal`, `ai_proposal_item`,
`calendar_import_event`, `calendar_sync_task_map` and `google_event_shadow`.

The other nine are the tables a profile reset must not touch, and they are the
second section of this inventory: `profiles`, `llm_usage`, `remote_configs`,
`remote_config_cache`, `sync_state`, `sync_shadow`, `sync_outbox`,
`sync_dead_letter` and `calendar_sync_state`. They were not reached before this
change either, and they are not reached now — for the reasons given there.

## What the gate checks, and what it does not

`scripts/ci/check-profile-reset-inventory.py` reads three things — the `@Entity`
annotations, the wipe's own list (`ProfileWipePlan`) and this document — and
fails when:

- a table exists in the schema that this document does not name, or names with
  no verdict;
- this document names a table the schema no longer has;
- the code wipes a table this document calls "not profile content", or this
  document calls "wiped" a table the code does not reach;
- a table the code claims to wipe has no profile-scoped `DELETE FROM` in the
  sources, or the delete the plan names is not a call the reset actually makes.

It does **not** check that the deletes do what they claim — that is the JVM
test's job (`ProfileResetReachesTheWholeProfileTest`), which seeds one row in
every table and says what is left afterwards. A gate that read the code and a
test that ran the code are two different kinds of evidence, and neither
substitutes for the other.

## Where the reset lives

| Piece | Where |
|---|---|
| The port | `core/wipe/ContentResetter.kt` |
| The implementation | `core/wipe/ProfileContentResetter.kt` |
| The list of tables | `core/wipe/ProfileWipePlan.kt` |
| The entry point | `androidApp/src/debug/kotlin/com/singularity/todo/debug/DebugSeedActivity.kt` — `reset=content` |
