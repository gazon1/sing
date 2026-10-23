# Design

## The shape of the change

One type, one port, one reading.

- `TaskDocument` — the domain type of a document: a task, and the parts the
  document speaks about. The parts are nullable, and `null` means "the
  document says nothing about this part".
- `TaskRepository.upsert` takes the document instead of the task.
- `SyncDocumentWriter.taskDocument` reads the wire keys before the decode and
  builds the document.

Everything else is the pull path applying what it was given.

## Why the parts are nullable and not a sealed type

The two cases — "the document says there are no tags" and "the document says
nothing about the tags" — have to be distinguishable, and they have to be
distinguishable in the type, because the difference is invisible at runtime
otherwise. A nullable collection says it in the type: `null` is not an empty
list.

A sealed type (`Absent` / `Says`) would say it more loudly, and would cost
more: every reader of the document would have to unwrap two cases where a
nullable collection costs one. The repository has exactly one reader of the
document, and it reads each part once.

## Why the reading happens in the sync layer

The wire key is visible only in the raw document. After the decode, the two
cases are the same task: `tags` defaults to an empty list, `blockedBy` to an
empty set, and the difference is gone. So the reading has to happen before the
decode, and the sync layer is the only place where both the raw document and
the decoded task are in reach.

The key names come from the model, next to the annotations that write them:
`TAGS_WIRE_NAME` and `BLOCKED_BY_WIRE_NAME`. The domain calls the field
`blockedBy` and the wire calls it `dependsOn`; the constant says both at once,
so a rename of the wire field cannot leave the check behind. The descriptor
cannot say it: it holds the wire names, not the property names, so a lookup by
the property name finds nothing — the reader has to be told the name, and the
model is the place that knows it.

## Why the row is still written whole

The row is the document. A document that carries a title carries a title; a
document that carries no tags says nothing about them. The parts where the
absence carries information are the parts that live outside the row — the
tags and the dependencies — because those are the parts a device can add to
the model later without changing the row.

So the rule is not "apply what the document carries, part by part". It is:
the row is the document and is written whole; the parts are claims, and a
claim that is not made is not applied.

## The relation of this change to the previous one

`2026-10-23-a-task-document-is-written-whole.md` made the pull apply the whole
document. That change was right about the row and about the parts it speaks
about, and it was silent about the parts it does not speak about — and the
silence became a deletion when the pull started applying the tags.

The two ADRs are read together: the first says what a document is, the second
says what a document that does not mention a part means.

## The three smaller decisions

**A pair may carry several relations.** The cross-reference key carries the
verb, so the relations of a pair are separate facts. The pull adds the
blocking edge beside the relation the pair already had. The spec of the
previous change said so; the model said nothing. Now the model says it, and a
test pins it.

**A refused edge is logged.** The refusal repeats on every pull — the device
that sent the edge will send it again — and a device that refuses silently
disagrees with the device that sent it forever. Kermit is the logger the
other features use, and the repository now has a place to put it: one
constructor parameter with a default, the way `PullEventHandler` takes its
logger.

**A batch write repairs the series.** `update` calls `realignSeries` after the
write; `updateAll` did not. Both current callers — `UpdateTaskSeries` and
`MergeTaskSeries` — write the whole series, so the repair had nothing to do
and the asymmetry was invisible. A partial batch would have left the series
saying two things about the work. The repair is called once, with the first
aligned member as the source, because the batch was aligned to it.

**The repair follows the claim of the writer.** The repair copies the shared
fields of the source onto the other members of the series, and the tags are one
of those fields. A document that carries no `tags` key has no claim about the
tags, so the repair leaves them alone — otherwise the arrival of a row that is
new on this device (and therefore holds no tags) would take the tags off every
other member of its series. The repair takes the claim as a parameter, and the
domain decides which fields it compares, so the rule is in one place.

## The double has to keep the same rule

A fake that stores whole `Task` objects turns an absent claim into a deletion:
the task carries the decoded defaults, and the empty list wipes the tags. The
double now keeps the parts the document does not carry. A double that lies
about the contract makes every test written against it a test of the double.

## What is deliberately not here

- The document does not get a writer of its own (#1001). The rules are still
  in the repository; this change is about what the pull applies.
- The wire does not change. A device still sends what it knows, and the
  receiving side learns to hear the difference.
- The vestigial `sync` columns stay (#999).
- The row's own absences are not covered: a document that carries no series id
  still writes the row without one (#1026). This change is about the parts that
  live outside the row.
