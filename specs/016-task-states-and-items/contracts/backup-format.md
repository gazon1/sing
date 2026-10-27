# Contract: The Backup File's Task Record

**Feature**: `016-task-states-and-items` · **Date**: 2026-10-26

A backup is a zip the user owns and can restore into any build. Its task record is a
per-field DTO, not a dump of the database row, so a field that is added to the row has to
be added to the DTO deliberately — and a field added to the DTO changes the shape of the
file, which the format tracks with a version.

---

## What changes

| | |
|---|---|
| **Field** | `workflowState` |
| **Type** | string, one of `"Open"`, `"NextUp"`, `"InProgress"` |
| **Default** | `"Open"` — a backup written before the field existed restores as `Open` |
| **Schema version** | **5 → 6** |

```kotlin
@Serializable
data class TaskDto(
    // … unchanged …
    /** The state the user claimed for this task. Independent of completion. */
    val workflowState: String = "Open",
    // … unchanged …
)
```

An unknown value read back is treated as `Open` rather than failing the restore — the
same rule the database column uses.

---

## Why the schema version moves

`BackupFormat.SCHEMA_VERSION` is 5 today. The version exists for exactly this situation,
and the repository's own history says what happens when it is ignored:

> v5: a dependency edge carries its verb. A v4 backup had only the two ends of the edge,
> so every relation came back as a block — and a task that followed up on another came
> back waiting for it. The shape changed, so the version changed too: an older client
> refuses a v5 backup outright rather than restoring a graph whose edges mean something
> else.
> — `BackupFormat.kt:11-16`

The same argument applies here, with the opposite direction of harm: a v6 backup restored
by a v5 client would lose the workflow state, and the client would restore tasks that
all claim to be `Open` — a set of tasks in the wrong state, silently.

**The rule**: a change to the shape of a record bumps `SCHEMA_VERSION`. An older client
refuses a newer backup outright rather than restoring a record whose fields mean something
else.

```kotlin
object BackupFormat {
    const val FORMAT_VERSION = 1   // the zip layout — unchanged by this feature
    const val SCHEMA_VERSION = 6   // the record shape — bumped
    const val MIN_SUPPORTED_SCHEMA_VERSION = 1
}
```

`FORMAT_VERSION` is the **zip layout** and does not move: no file is added, removed or
renamed.

---

## The dependency verb — this format is already ahead of the wire

Worth stating, because it is easy to assume the backup and the sync contract agree:

| | carries the relation verb? |
|---|---|
| **Backup** | **yes** — `TaskDependencyDto.verb`, defaulting to `"BLOCKS"` (`BackupTaskDtos.kt`) |
| **Sync wire** | **no** — only the blocking projection travels; see [sync-wire.md](sync-wire.md) |

So a `FOLLOWS_UP` relation **survives a backup** but does **not** survive a sync. The
follow-up marking is therefore durable across a restore and local across devices. Both
halves of that sentence are limitations the user should be told about; neither is fixed
by this feature.

---

## Known gap this feature does not close

`TaskDto` has **no `parentTaskId` field**. A subtask is a task whose `parentTaskId` points
at another task, and that link does not survive a restore: a restored subtask comes back
as a top-level task.

This is pre-existing and out of scope here — but it is adjacent to this feature's
requirement that a subtask stays openable as its own task, so it is recorded rather than
left to be discovered. Closing it means adding `parentTaskId` to `TaskDto`, which is
another schema-version bump and another migration on the restore path.

---

## Files

| File | What changes |
|---|---|
| `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/BackupTaskDtos.kt` | **+ `workflowState`** on `TaskDto`, and the mapping in both directions |
| `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/BackupFormat.kt` | **SCHEMA_VERSION 5 → 6** |
| the backup round-trip test | asserts that a task's state survives a write and a restore |
