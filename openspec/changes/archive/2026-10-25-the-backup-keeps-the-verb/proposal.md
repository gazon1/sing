# Change: the-backup-keeps-the-verb

## Why

A backup of the dependency graph kept the two ends of every edge and dropped
what the edge *meant*. The verb was not in the payload, so a relation that only
follows up on another came back from the restore as a block — and a task that
was never waiting for anything became a task waiting for something.

That was harmless while the graph was only drawn. It stopped being harmless
when the close rule started reading the same edges: a restored `FOLLOWS_UP`
edge became a `BLOCKS` edge, so the task could not be closed, and the user was
asked about a prerequisite that did not exist.

## What changes

- **A dependency edge carries its verb in the backup.** The payload records
  what the edge means, and a restore puts back the relation that was there.
- **A backup written before the verb existed restores as a graph of blocks.**
  The default is `BLOCKS`, which is the only meaning an edge without a verb can
  have had.
- **The payload version moves to 5**, so a client that does not understand the
  verb refuses the backup instead of restoring a graph that means something
  else.

## Impact

- Affected specs: `backup/dependency-graph`
- Affected code: `core/backup`
- Schema: unchanged (no migration — the backup format is versioned separately)
- Wire format: the backup payload gains a field; the sync wire format is
  unchanged
