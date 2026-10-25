# the-document-says-only-what-it-carries

## What

A document says what it carries, and what it does not say, it does not take.

The change that made the pull path apply the whole document also made it apply
parts that were never in the document. A task document is decoded as a `Task`,
and the fields the model cannot do without have defaults: `tags` defaults to
an empty list, `blockedBy` to an empty set. A document that carries no `tags`
key therefore decodes into a task that says "no tags" — and the pull applies
what the document says, so the tags of the task are taken off.

That was harmless while the pull wrote the row and nothing else. Now that the
pull applies the tags, the reading of "no key" as "no tags" is a deletion: the
tags live in a cross-reference table, so an empty list is a removal, not a
statement.

This change makes the absence a claim of its own:

1. **A document carries claims, and a claim can be absent.** The tags and the
   dependencies of a document are nullable: `null` means the document says
   nothing about that part, an empty collection means it says there are none.
2. **The pull applies what the document says.** The row is written whole — a
   document is a row — and the parts the document speaks about are written
   with it, in one unit of work. A part the document does not speak about is
   not written at all.
3. **The absence is read before the decode.** The wire key is visible only in
   the raw document; after the decode the two cases are the same task.
4. **A pair may carry several relations.** The verbs are different claims
   about the same pair, and the document speaks about the blocking one only.
5. **A refused edge is said out loud.** The refusal of a cycle-closing edge is
   logged: a device that refuses an edge silently disagrees with the device
   that sent it, and the disagreement repeats on every pull.
6. **A batch write repairs the series, like an ordinary write.** The single
   write repairs the series around it; the batch write did not.
7. **The repair follows the claim of the writer.** A document that says nothing
   about the tags does not take the tags off the other members of the series
   either — the repair copies the shared fields, and the tags are one of them.

## Why

The wire is the reason the reading matters. A document carries the fields the
sending device knew about: a device that predates the tags sends no `tags`
key, and a device that predates the dependencies sends no `dependsOn` key.
Nothing in the contract says a device sends the whole document; the allow-list
says what a device sends, and it grew.

So the defect was: an old device could delete the tags of the tasks it writes,
invisibly. The row lands, the pull reports success, and the tags are gone.

The other three are smaller and were left unsaid by the change that introduced
them: only the spec said that a pair may carry two relations, nothing said
that a refused edge was refused, and the batch write repaired the series less
often than the ordinary write did.

## How

- `TaskDocument` — the domain type of a document: a task, and the parts the
  document speaks about. `TaskDocument.of(task)` is the document that says
  everything the task carries.
- `TaskRepository.upsert` takes a document instead of a task.
- `SyncDocumentWriter` reads the wire keys before the decode and says nothing
  about the parts the document did not carry. The key names live in the model
  next to the annotations that write them, so a rename of the wire field
  cannot leave the check behind.
- The pull path writes the tags and the dependencies only when the document
  speaks about them.
- The repair of the series follows the same rule: a writer with no claim about
  the tags repairs the other fields only.
- A refused edge is logged with Kermit, the logger the other features use.
- The batch write calls the same repair the ordinary write calls.
- The double keeps the parts the document does not carry — a fake that turns an
  absent claim into a deletion would make the tests lie.

## Non-goals

- The wire does not change: a device still sends what it knows, and the
  receiving side learns to hear the difference between "no tags" and "no
  claim about the tags".
- The four vestigial `sync` columns of the task row stay as they are
  (#999).
- A toast that outlives its screen, and the undo of a split or a merge, are
  separate changes (#1000).
- The document does not get a writer of its own yet (#1001): the rules are
  still in the repository, and this change is about what the pull applies, not
  about where the rules live.
- The row's own absences are not covered: a document that carries no series id
  still writes the row without one (#1026). This change is about the parts that
  live outside the row.
