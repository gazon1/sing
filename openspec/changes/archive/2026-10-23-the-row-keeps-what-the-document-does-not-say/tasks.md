# Tasks — the-row-keeps-what-the-document-does-not-say

## 1. The row keeps what the document does not say

- [x] 1.1 Add `mergeWithLocalRow(document, local)`: the document wins wherever
      it speaks, and where it says nothing the local row keeps saying what it
      says.
- [x] 1.2 Read the local row on the path that applies a task document — the
      pull handler and the revert of a lost race both go through
      `SyncDocumentWriter` — and apply the merge there.
- [x] 1.3 Leave the keys of the parts that live outside the row — the tags and
      the blocking edges — to the document alone, so that the claim of a part
      stays the claim of that part.

## 2. The series of the row stays the series the row had

- [x] 2.1 The merge keeps the local series, and the repair of the series runs
      on it: the rule of the series — its members say one thing about the work
      — does not depend on whether the sending device knew about the series.
- [x] 2.2 The repair is not made conditional on what the document said about
      the series: a document that changed a task of the series still brings the
      series to one thing about the work, and a member never holds a newer
      title that the repair would destroy (a local edit of a member repairs
      the series too).

## 3. The wire names of the fields live with the model

- [x] 3.1 Put `TAGS_WIRE_NAME` and `BLOCKED_BY_WIRE_NAME` in the model next to
      the annotations that write them, so a rename of a wire field cannot
      leave the merge behind, looking for the old name.

## 4. The tests hold it

- [x] 4.1 `DocumentMergeTest` (named `TaskDocumentMergeTest` when this change
      was made, and renamed when the merge was generalised to every type): a
      document that carries no series id, no
      recurrence and no estimate leaves the row's values alone; a document
      that says the task stands alone takes it out of the series; the document
      wins where it speaks; a document about an unknown task is the task; the
      merge does not speak about the parts that live outside the row.
- [x] 4.2 `TaskDocumentApplyTest`: the path that applies a document goes
      through the merge — a pull of a document without a `seriesId` leaves the
      task in its series, a document that says the task stands alone takes it
      out, and the row of another profile is written as the document says. The
      documents in the test are the documents a device sends: a whole task,
      minus the keys an older device did not know about.
- [x] 4.3 `TaskDocumentWriteTest`: a document that carries no `seriesId` still
      leaves the series saying one thing about the work — against the real
      repository, where the repair of the series lives.

## 5. The decision is written down

- [x] 5.1 ADR `2026-10-23-the-row-keeps-what-the-document-does-not-say`.
- [x] 5.2 The spec delta in `specs/offline-sync/spec.md` (REQ-OS-029).
- [x] 5.3 The change is registered in `.specify/issues/`.
