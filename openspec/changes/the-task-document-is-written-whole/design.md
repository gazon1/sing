# Design — the task document is written whole

## The shape of a task

A task lives in three places and one derived column:

| Part | Where | Written by |
|---|---|---|
| the row | `tasks` | `Task.toEntity()` — the row mapper |
| the links | `tasks.outgoing_links` | `saveOutgoingLinks` — derived from the description |
| the tags | `task_tags` | `writeTags` — the cross-ref table |
| the dependencies | `task_dependencies` | `writeBlockingDependencies` — the cross-ref table |

`@Upsert` replaces the whole row. The row mapper is therefore the only place
where a column can be lost by omission: a column it does not pass takes its
Kotlin default, and the default is written over whatever was there.

The domain task carries no field for the links — they are a function of the
description, and a second home for that fact would be a second thing to drift.
So the row mapper takes them as a parameter and the writer that has the
description supplies them.

## One writer

The pull path applies a whole document, and it does it in one unit of work:

```kotlin
unitOfWork.write {
    taskDao.upsert(task.toEntity())   // the row that arrived
    writeTaskParts(task)               // the parts that are not the row
    realignSeries(task)
}
```

`writeTaskParts` is the parts of the document that live outside the row:

```kotlin
private suspend fun writeTaskParts(task: Task) {
    val uid = currentUser.scopedUserId.value.value
    writeTags(task, uid)
    saveOutgoingLinks(task.id, task.description)
    writeBlockingDependencies(task, uid)
}
```

The paths that apply *part* of a document — an edit, a batch write of a series
— are not document writers, and they say so:

- `update()` writes the row and the links, and not the tags: the task it was
  given was read through `get()`, which does not load them.
- `updateAll()` writes the rows and the tags, because the series write is a
  statement about the shared fields — and the tags are one of them.

## The dependencies

The wire carries `dependsOn`, which is the set of *blocking* edges. The other
verbs — `FOLLOWS_UP`, `DUPLICATES`, `FIXES` — are local to the device, so
applying a document touches the blocking edges only. Removing an edge of
another kind would delete what the document never spoke about.

An edge that would close a cycle is refused on its own, and the rest of the
document still applies: the graph the local code walks stays acyclic, and the
row still lands.

The cycle check is the same one the local write path uses
(`DependencyValidator.assertNoCycles`), applied per new edge so that a
document with several edges is not refused whole because of one of them.

## The row of another profile

The pull path stores rows of other profiles — the lost-race path writes a
row's own shadow. Those rows are stored as they are: this device does not
rewrite the rows of a profile it is not on. The links such a row carried are
kept, because the writer that owns them (the profile's own device) is not
here, and an `@Upsert` would reset them to nothing.

## The gate

`TaskRowWriterTest` checks that every column of `TaskEntity` — and every
column of the embedded `SyncColumns` — has a writer: the row mapper passes it,
or the test declares it with the name of the writer that writes it instead.

The read-side gate (`EntityMapperCompletenessTest`) cannot see this: it checks
that *someone* touches every column, and the reader touches all of them. The
columns that hide inside an `@Embedded` are out of its reach for the same
reason.

A declaration is not a behaviour: the gate says "this column has a writer, and
here it is". The behaviour is checked by `TaskDocumentWriteTest`, which
asserts that the links of a row that arrives are in the database.

## What this does not do

- The four vestigial `sync` columns of the task row stay. They are declared
  as vestigial; removing them is a schema migration and a separate decision.
- The wire still does not carry the non-blocking dependency verbs.
- The toast that outlives its screen, and the undo of a split or a merge, are
  separate changes.
