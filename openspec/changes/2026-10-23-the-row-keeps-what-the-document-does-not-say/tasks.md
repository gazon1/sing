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

## 2. The wire names of the parts live with the model

- [x] 2.1 Put `TAGS_WIRE_NAME` and `BLOCKED_BY_WIRE_NAME` in the model next to
      the annotations that write them, so a rename of a wire field cannot
      leave the merge behind, looking for the old name.

## 3. The tests hold it

- [x] 3.1 `TaskDocumentMergeTest`: a document that carries no series id, no
      recurrence and no estimate leaves the row's values alone; a document
      that says the task stands alone takes it out of the series; the document
      wins where it speaks; a document about an unknown task is the task.
- [x] 3.2 `TaskDocumentApplyTest`: the path that applies a document goes
      through the merge — a pull of a document without a `seriesId` leaves the
      task in its series, and a document that says the task stands alone takes
      it out.

## 4. The decision is written down

- [x] 4.1 ADR `2026-10-23-the-row-keeps-what-the-document-does-not-say`.
- [x] 4.2 The spec delta in `specs/offline-sync/spec.md` (REQ-OS-028).
- [x] 4.3 The change is registered in `.specify/issues/`.
