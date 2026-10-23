# A push carries what the database holds

## Why

A task that travels to the server is a claim: "this is what the work is". The
parts of a task that live outside the row — the tags, the blocking edges —
travel with it, and the receiver applies the claim.

The write path was sending claims it did not mean. A task read through
`get()` carries no tags and no edges — they live in cross-ref tables — and the
ordinary edit pushed that same task. On the wire an empty tag list says "the
work has no tags", and the server applies it: the tags were gone from the
server, and every other device lost them on the next pull. The edit itself
was applied; the work it belonged to was stripped, on every other device, by a
device that had never been asked whether the work had tags.

The local write already knew this — `update()` does not write the tags, and
the comment there says why. The push was the half that did not know.

## What changes

- Every path that writes a task and enqueues it enqueues the task as the
  database holds it: the row, the tags and the blocking edges, read back after
  the write.
- The assembly lives in one place — `TaskDao.freshTask` — next to the mapper
  it ends in.
- The three paths that sent the claim: `update()`, `updateAll()` and the bulk
  archive.

## Non-goals

- The receiving side: a document that arrives without a field still resets
  that field of the row (#1026).
- The shadow does not remember what the device knew (#1032), and the
  allow-list on the wire is still a second list (#1033).
- A claim is still a claim: a device that means "this work has no tags" says
  so, and the other devices believe it. That is the protocol working.
