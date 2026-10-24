# A push carries what the database holds — tasks

## 1. The hole, named

- [x] The push of an ordinary edit said "the work has no tags" and "the work
      waits for nothing" — the emptiness of the read, as a claim. Proven by
      `TaskPushClaimTest`, which failed before the fix.

## 2. The fix

- [x] `TaskDao.freshTask` — the task as the database holds it: the row, the
      tags, the blocking edges. One place, next to the mapper it ends in.
- [x] `TaskDao.freshTasks` — the same answer for many tasks, read in one pass.
- [x] `update()` enqueues the fresh task.
- [x] `updateAll()` enqueues the fresh task.
- [x] `create()` and `createSeries()` enqueue the fresh task.
- [x] `TaskDaoArchiveRepositoryImpl.archiveCompletedTasks()` enqueues the fresh
      tasks.
- [x] The tag edit on the detail screen goes through `setTags` — the path that
      writes the tags. The ordinary write does not write them, so the edit used
      to reach the server through the push and come back on the next pull, and
      the local row never held them.

## 3. The checks

- [x] `TaskPushClaimTest` — an edit does not tell the server that the work has
      no tags; an edit does not tell the server that the work waits for
      nothing; a batch write does not tell the server that the work waits for
      nothing; the claim is read inside the transaction, and it sees the write.
- [x] `TaskDaoArchiveRepositoryImplTest` — the bulk archive does not tell the
      server that the work has no tags.
- [x] The existing suites of the write path still pass: `TaskDocumentWriteTest`,
      `TaskSeriesWritePathTest`, `TaskRepositoryContractTest`.

## 4. The documents

- [x] ADR `docs/decisions/2026-10-23-a-push-carries-what-the-database-holds.md`.
- [x] The rule in `CODING_STANDARDS.md`.
- [x] The projects have the same hole — #1034 — and the ADR says so.
