# Test data — what the profile holds and what the import lost

Source artifact (read-only, never modified):

```
/home/max/Downloads/singularity_backup_2026-11-08.json   28 628 219 bytes
{"version": 25, "created": "2026-11-08T19:38:32.057Z", "data": { … 33 collections … }}
```

Valid JSON, `version: 25`, 33 collections under `data`. Counts below are `len()` of each collection
after dropping `_removed`.

---

## Why the import was verified, not re-run

The brief asked to import this file with `import-cli`. The profile on the device **already contained
that conversion**: every count that `convert` reports (`tasks=12026 projects=132 tags=277 notes=4268
checklists=2588 timeEntries=662`) matches the database, and the 21 "completed candidates" match the
current mapper's rule exactly. Re-running the import would have replaced a working profile with a
byte-identical one, while risking the only copy of the data both apps were about to be compared on.

So the import was **audited** instead: the converter's contract was read in
`import-cli/.../ImporterCli.kt` and `Mapper.kt`, its output was reconciled row-by-row against the
backup JSON (table below), and the two known importer limitations (completion rule, dropped
reminders/attachments) were reproduced as facts about the screen rather than assumed.

What *was* executed, and is written down in `environment.md`: pulling the live database with its WAL,
counting every entity, and reading the field-level mapping. If a future run needs the actual convert
step, the invocation is unchanged from `docs/research/2727-imported-completion-state.md`:

```sh
./gw :import-cli:run --args "convert \
  --input /home/max/Downloads/singularity_backup_2026-11-08.json \
  --output /tmp/native.zip"
# then restore the ZIP through the app's backup screen
```

---

## The import was already on the device

The device's database matches a completed conversion of this exact file; the numbers reconcile with
`import-cli`'s own report (`convert` in `import-cli/src/main/kotlin/.../ImporterCli.kt`):

| Entity | Backup | Device DB (`todo.db`) | Match |
|---|---:|---:|---|
| tasks | 12 026 | **12 028** | +2: `BackupRoundTripTask` (created by an earlier session, 2026-11-09) and one probe task from this session (now in trash) |
| projects | 132 | **132** | ✅ |
| tags | 277 | **277** | ✅ |
| notes | 4 513 | **4 268** | ❌ 245 lost (see below) |
| checklist items | 2 588 | **2 588** | ✅ |
| time entries | 664 | **662** | −2 (`Mapper` skips entries it cannot attribute) |
| task↔tag links | — | 1 535 links on 1 168 tasks | legacy `tags` = 1 168 tasks ✅ |
| task reminders | 1 756 tasks carry `notifies` | **0** | ❌ not imported |
| attachments/files | 153 | **0** | ❌ not imported |
| task dependencies | 0 in backup | 0 | ✅ |
| habits | 8 | not a table in the app | n/a |

The conversion was run **without `--mark-completed`** (21 completed tasks in the DB, which is exactly
the count of `checked==1 && state==0` records, i.e. the current, known-wrong completion rule). So
**9 637 tasks that the user considers done are imported as active** — this is why Today shows
`Overdue · 7040`. Full analysis: `docs/research/2727-imported-completion-state.md`.

### Field-level fidelity in `tasks`

| Legacy field | Device column | Imported | Notes |
|---|---|---:|---|
| `start` (ISO) | `due_date` + `due_time` | 7 933 dates, 2 561 with time | ✅ |
| `deadline` | `end_date` | 150 | ✅ stored, **but the detail renders `end_date` as nothing** — the UI has no deadline row |
| `priority` 0/1/2 | `priority` Low/Medium/High | 717 / 11 301 / 8 | ✅ mapping verified |
| `checked` == 1 | `completed_at` | 21 | ❌ 9 637 lost — see above |
| `recurrence` | `recurrence_rule` | 80 | ✅ stored; ✗ rendered as `toString()` (UXF-01) |
| `parent` | `parent_task_id` | 133 | ✅ |
| `projectId` | `project_id` | 6 080 | ✅ |
| `tags[]` | `task_tags` | 1 168 tasks | ✅ |
| `note` | `notes.task_id` | 4 227 notes | ✅ (but 4 513 → 4 268 overall) |
| `timeLength` | `estimate_minutes` | 1 652 | ✅ |
| `deferred` | `someday` | 310 | ✅ stored; **no UI surface anywhere** |
| `notifies[]` | `task_reminders` | 0 | ❌ |
| `files` | `attachments` | 0 | ❌ |
| `deleteDate` | `archived_at` | 571 (now 594 after probes) | ✅ |

### Why notes lose 245 rows

`4513 − 4268 = 245`. The legacy export contains 132 *project* notes (`N-P-…`) and 4 381 task notes;
the DB holds 41 project notes and 4 227 task notes. The mapper writes notes it can attribute to a
task or a project and drops the rest (orbits whose container was itself removed). This is a
**data-fidelity issue of the importer, not a Task Detail issue**, but it changes what the detail's
"Logbook" and "Related notes" sections can show, so it is recorded here.

---

## What this means for reading the UX

Every "empty" section you see on a screenshot in this folder is empty **in the database**, not hidden
by a filter:

| Section | Rendered on device | Actually has data |
|---|---|---|
| Reminders | never rendered (no UI) | 0 rows, so a UI could not have shown anything |
| Attachments | empty card with "+ Add file" | 0 rows (153 files existed in the backup) |
| Deadline | no row in the UI at all | 150 tasks have `end_date` |
| Someday | no row in the UI at all | 310 tasks have `someday = 1` |
| Checklist | empty card with hint | 0 on the tasks used for the empty-state screenshots; 26 on `Backlog sing features` |
| Subtasks | empty card with hint | 0 on most tasks; 7 on `Недавно услышал здравую мысль…` |

So the empty-state screenshots are a *worse-than-real* case for reminders and attachments, and a
*faithful* case for checklist/subtasks (many real tasks genuinely have none).

---

## Fixtures used in the screenshots

| Fixture | Why it was chosen | Where |
|---|---|---|
| `Купить хлеб` (instance due 2026-11-12) | a normal daily task: project, high priority, recurrence, no checklist/subtasks/attachments — the "typical" case | `02`–`14` |
| `Backlog sing features` | 26 checklist items, 1 note, project, no dates — the "long body" case | `15`–`17` |
| `Отмамамыларамливаю книгу head first` | completed task with a long title | `18` |
| a task created in-session (`ux-prob-editede-task`) | newly created task → shows the `Quick actions` card and the `Saved` pill | `22`, `24` |
| SingularityApp `Снять 50к с альфы банкомана` | a dated task with project + priority + recurrence, no checklist — the same shape as `Купить хлеб` | `singularity-app/*` |

Both apps were opened on the **same account's data**, so the two screenshots of "a task with a date
and a project" describe the same task, not two different ones.

---

## Side effects of the session (user data)

Two writes reached the real profile. Both are recoverable and both are recorded so they can be
verified:

1. **`ux-prob-editede-task`** (`0fb7b0fa-9edc-489f-93f0-9e699956d75d`) — a probe task created through
   the Create screen (title typed, saved), later title-edited inline, then deleted through the
   detail's overflow. It is **soft-deleted**, so it sits in `tasks` with
   `archived_at = 1794499760618` and is restorable/purgeable from **Menu → Archive**.
2. **one instance of `Умыться энзимной пудрой и салмциновой кислотой…`** — archived
   (`archived_at = 1794499139346`) when a tap intended for the overflow menu landed on
   `Архивировать`. This is one of ~22 recurrence instances of a repeating task; restore it from
   **Menu → Archive → open the row → restore**.

Everything else was restored in place: the priority of `Купить хлеб` was raised to High and set back
to Medium (verified in `tasks.priority`), and the probe checklist item `ux-probe-item` was deleted
(`checklist_items` back to 2 588). No backup file and no `origin/main` state was touched.

One database read trap worth repeating: a `todo.db` pulled **without** `-wal` shows stale rows —
checklist count 2 588 vs 2 589, priority `Medium` after it was already `High`. Pull all three files.

---

## Two more data-shape facts that change the UX reading

- **Recurrence instances are separate rows with repeated titles.** `Купить хлеб` exists ~160 times
  (one per occurrence), each with its own `due_date` and its own copy of the recurrence rule. Any
  screen that shows "this task repeats" is describing a *series*, and the series card on the detail
  (`TaskSeriesCard.kt`) is the only place that fact surfaces.
- **`project_id` is on 6 080 of 12 027 tasks (50.6%)**; the other half has none. A "No project" row
  is not an edge case in this profile — it is the common case.
