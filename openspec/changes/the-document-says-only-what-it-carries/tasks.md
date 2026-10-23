# Tasks

## 1. The domain type

- [x] `TaskDocument` — a task, and the parts the document speaks about; the
      parts are nullable, and `null` means the document says nothing about
      them.
- [x] `TaskDocument.of(task)` — the document that says everything the task
      carries, for the callers that hold a whole task.

## 2. The port and the implementation

- [x] `TaskRepository.upsert` takes a `TaskDocument`.
- [x] The pull path writes the tags only when the document speaks about them.
- [x] The pull path writes the blocking dependencies only when the document
      speaks about them.
- [x] The row is still written whole — a document is a row.

## 3. The reading of the wire

- [x] `SyncDocumentWriter.taskDocument` reads the wire keys before the decode.
- [x] The key of the dependencies comes from the model
      (`BLOCKED_BY_WIRE_NAME`, next to the annotation that writes it), so a
      rename of the wire field cannot leave the check behind.

## 4. The three smaller decisions

- [x] A pair may carry several relations — said in the model and in the test.
- [x] A refused edge is logged, with the logger the other features use.
- [x] A batch write repairs the series, the way an ordinary write does.
- [x] The repair follows the claim of the writer: a document with no claim
      about the tags does not take the tags off the other members.
- [x] The double keeps the parts the document does not carry — a fake that
      turns an absent claim into a deletion would make the tests lie.

## 5. The checks

- [x] `TaskDocumentPartsTest` — the wire level: a document that carries a
      part says it, a document that does not carry it says nothing about it,
      and the double keeps the parts the document does not carry.
- [x] `TaskDocumentWriteTest` — the behaviour: a document that says nothing
      about the tags leaves them, a document that says nothing about the
      dependencies leaves them, a document that says "no tags" takes them off,
      a document with no claim leaves the tags of the series alone, a pair may
      carry two relations, a refused edge still lands the row.
- [x] `TaskSeriesWritePathTest` — a batch write repairs the members it did not
      write.

## 6. The documents

- [x] ADR `2026-10-23-a-document-says-only-what-it-carries.md`.
- [x] The spec of the previous change says what a document that does not
      mention a part means — the rule "the spec and the code say the same
      thing" is the rule that failed here.
- [x] The issue-registry record.
