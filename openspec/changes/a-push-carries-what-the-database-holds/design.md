# A push carries what the database holds — design

## The wire speaks in claims

A patch is a set of field operations. A field that is set says "this is the
value"; a field that is absent from the patch says nothing at all. The server
merges the fields it receives into the document it holds, field by field, and
the receiver applies the document.

So the empty collection is a claim. A document whose `tags` is `[]` says "the
work has no tags", and the receiver removes them. A document whose `dependsOn`
is `[]` says "the work waits for nothing", and the receiver removes the edges.
There is no wire state for "I did not look" — only for "here is the value" and
"here is the emptiness".

That is not a defect of the protocol. The protocol is fine when the device
knows what it sends. The defect was in the device.

## The device did not know

`get()` loads the row and not the cross-references. The tags live in
`task_tags`, the blocking edges in `task_dependencies`, and the read that the
editor does is not interested in either. The task that comes back therefore
carries two empty collections that are not the truth about the work — they
are the truth about the read.

The local write respected that: `update()` does not write the tags, and the
comment there says why in three lines. The push did not: it took the same
task, put it in the outbox, and the outbox took it for the whole truth. The
server applied "the work has no tags", and every other device lost the tags
on the next pull.

The shape of the bug is worth stating, because it is easy to reintroduce: the
write path had two halves, one of which knew about the read's emptiness and
one of which did not. Nothing connected them. The half that did not know was
the half that left the device.

## The decision

Every path that writes a task and enqueues it enqueues the task as the
database holds it.

The assembly is one function — `TaskDao.freshTask` — because it is one
question asked in more than one place: what is this task, for the wire? It
reads the row, the tags and the blocking edges, and it lives next to the
mapper it ends in (`TaskExtras.kt`, where the other reader of a task with its
parts already lives). A second copy would drift, and the drift would be
silent: the copy that forgot the tags would send the emptiness.

The write paths that used to enqueue the caller's task now enqueue the fresh
one:

- `update()` — the ordinary edit;
- `updateAll()` — the batch write, which writes the tags and not the edges;
- `create()` and `createSeries()` — the tags are written before the push, and
  the edges the caller carried are not written at all, so the claim is the
  database's;
- `TaskDaoArchiveRepositoryImpl.archiveCompletedTasks()` — the bulk archive,
  which pushed a row it had read without its cross-refs.

The bulk delete of a profile is the one path that does not read the parts
back: it reads the cross-refs *before* it deletes them, because that is the
only way to say what the work had, and it pushes the tasks it assembled from
them. The reason is written down where it happens.

The cost is a read per write: the row is read back after the write, so the
push describes what the database holds rather than what the caller held. That
read is inside the same transaction as the write, so the two halves of the
change still commit together.

## Why not "the tags are not synced"

The obvious counter-argument is that the tags should not travel at all, and
the problem would not exist. The answer is that the tags are shared work
metadata: a tag added on one device has to be visible on the others, and the
cross-reference tables are not something the server can read. The server
stores the document, and the document has to carry them. Making them travel
correctly is the job; making them not travel would be a different product.

## What stays the same

- A device that means "this work has no tags" still says so, and the other
  devices believe it. The claim is honest there.
- The receiver's rule is unchanged: a part the document does not carry is not
  written. This change makes the sender's documents say what they mean.
- The notes, the tags, the tag groups and the time entries are not touched:
  their parts live inside their rows, so a caller's copy of one is the whole
  truth. The rule bites where a part lives outside the row.
- The projects are the exception and are not fixed here: a project's inherited
  tag groups are a join table, and the project path has the same hole
  (#1034). Fixing it means deciding what a project write does with the
  inherited groups at all, which is a question of its own.
