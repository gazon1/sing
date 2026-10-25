# the-task-document-is-written-whole

## What

A task is not one row. It is the row in `tasks`, the tag cross-refs in
`task_tags`, the blocking edges in `task_dependencies`, and the `outgoing_links`
column of the row — which is derived from the description rather than carried by
the task itself.

`@Upsert` replaces the whole row, so a writer that applies only the row does
not leave the rest of the document alone: it resets it.

This change makes the path that applies a task document apply all of it:

1. **The document lands whole.** A row that arrives from another device brings
   its tags, the links its description implies, and the dependencies it
   carries — and all of them land, in one unit of work.
2. **The links survive a pull.** The links of a task are derived from its
   description; a pull that wrote the row without them took them away.
3. **The dependencies follow the document.** A dependency the remote device
   dropped is dropped here too; a dependency it added is added here too. The
   edges the document never spoke about — the ones with another verb — stay.
4. **An edit does not take the tags with it.** The tags of a task are changed
   by the path that knows what they should be, and a path that applies part of
   a document says which part it is.

## Why

Every one of these was found by a review that read the code, not by a test
that failed:

- the pull path wrote the row and nothing else, so every pull reset the links
  of the task it applied, and the tags and the dependencies of the document
  never arrived at all;
- the batch write of a series wrote the rows and never the tag cross-refs, so
  merging two series left the joined rows with the old tags in the database;
- the repair of the series took the tags of the task it was given, and the
  edit path hands it a task read through `get()` — which carries no tags,
  because they live in the cross-ref table. Every other member of the series
  then looked divergent on the tags, was rewritten, and had its tag rows
  deleted, and the push that followed carried the empty set to the other
  devices.

The three are the same defect: a path that applied a part of a document and
called it the whole. A document that arrives half-applied is worse than one
that did not arrive — the row says the work has no links while the rest of the
database still says it has them, and nothing in the system knows which of the
two is true.

The read-side completeness gate could not see any of it: it checks that
*someone* touches every column of the task row, and the reader touches all of
them. The writer — the private mapper inside the repository — was out of reach.

## How

- The pull path applies the document whole: the row that arrived, and then the
  parts of the document that are not the row — the tags, the links, the
  dependencies — in one unit of work.
- The pull path calls it, and calls nothing else.
- The row's mapper takes the links as a parameter: it cannot derive them, and
  a mapper that left the column out would let `@Upsert` reset it.
- The dependencies that come from the document are the blocking ones — the ones
  the wire carries — and an edge that would close a cycle is refused on its own.
- A gate checks that every column of the task row has a writer, and that a
  column without one is declared with the name of the writer that writes it.

## Non-goals

- The four vestigial `sync` columns of the task row stay as they are: they are
  declared as vestigial, not removed. Removing them is a schema migration and a
  separate decision.
- The other verbs of a dependency are still not synced. The wire does not carry
  them, and this change does not extend the wire.
- A toast that outlives its screen, and the undo of a split or a merge, are
  separate changes.
