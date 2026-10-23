# the-row-keeps-what-the-document-does-not-say

**Status:** proposed · **Issues:** [#1026](https://github.com/gazon1/sing/issues/1026) · **ADRs:** `2026-10-23-the-row-keeps-what-the-document-does-not-say`

## What

A task document that arrives from another device is applied to the row that is
already on this device — and the fields the document does not carry are left
alone.

The row is written whole, and the fields the model cannot do without have
defaults: `seriesId` defaults to "no series", `recurrence` to "no recurrence",
`estimateMinutes` to "no estimate". A document that carries no `seriesId` key
therefore decoded into a task that says "this task stands alone", and the row
was written with it: the task silently left its series on this device. The
same for a recurring task that lost its recurrence, and for a task that lost
its estimate.

## Why

The wire is the reason the reading matters. A document carries the fields the
sending device knew about: a device that predates the series sends no
`seriesId` key, and a device that predates the recurrence sends no
`recurrence` key. The allow-list says what a device sends, and it grew.

Read as "no series", that missing key is a deletion — and an invisible one:
the row is written, the pull reports success, and the task is simply not in
its series any more. The rule the repository already states for the parts
that live *outside* the row — the tags and the blocking edges — is the same
rule one level in: a document says only what it carries, and what it does not
say is not applied.

That change names this as its known limitation and points here (#1026), so
the boundary is stated where the rule lives rather than left unsaid.

## How

- The path that applies a task document reads the document against the row
  that is already on the device: the document wins wherever it speaks, and
  where it says nothing, the row keeps saying what it says.
- The read happens on the wire document, before the decode: after the decode
  the two cases are the same task, because the difference between "no series"
  and "no claim about the series" is gone.
- A claim is still a claim: a document that carries `seriesId: null` says the
  task stands alone, and it takes the task out of its series. Only the
  *absence* of the key is an absence of a claim.
- The series of the row is the series the row already had. The rule of the
  series — its members say one thing about the work — does not depend on
  whether the sending device knew about the series, so a document that never
  spoke about the series still leaves the series saying one thing.
- The parts that live outside the row — the tags and the blocking edges — are
  not the row's to claim, so the merge leaves those keys to the document
  alone.

## What this does not do

- It does not change what a device sends. The document is still written whole
  by the sender; this is about how the receiver reads what arrived.
- It does not change the protocol. The server already applies `unset` as a
  null value, so a device that takes a task out of its series still says so
  with a key the receiver can read.
- It does not touch the parts that live outside the row. Their claims are
  their own change (`the-document-says-only-what-it-carries`, which is now in
  the tree), and the merge leaves their keys alone so that both readings can
  hold: a part the document does not carry is not written, and a field of the
  row the document does not carry is not reset.
- It does not change the rule of the series: a document that changes a task of
  a series still brings the series to one thing about the work, as it always
  did.

## Links

- ADR `2026-10-23-the-row-keeps-what-the-document-does-not-say`
- Issue [#1026](https://github.com/gazon1/sing/issues/1026) — the row's own
  absences, and the limitation this change closes
- ADR `2026-10-23-a-task-document-is-written-whole` — why the row is written
  whole
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/TaskDocumentMerge.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncDocumentWriter.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/model/TaskDocument.kt`
  — the task and the claims it makes about its own row
- Issue [#1026](https://github.com/gazon1/sing/issues/1026)
