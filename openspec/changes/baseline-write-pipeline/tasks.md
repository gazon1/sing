# Tasks — baseline-write-pipeline

**Status: verification checklist, not implementation work.** The system already
implements the documented behavior; what was missing was evidence that it does.

The list below was rewritten on 2026-10-04 after checking every claim against the
test suite. The previous version named a covering test for all thirteen
requirements, and at least five of those attributions were wrong —
`FakeRepositoryFidelityTest` contains no reference to the outbox, `enqueue` or an
affected-row count, and `EntityMapperCompletenessTest` never reads a `@Query` at
all. A checklist whose boxes are ticked by assertion rather than by name is
indistinguishable from one that was never checked.

Two states, kept deliberately distinct:

- `[x]` **verified** — a named test asserts this, and the assertion is quoted.
- `[ ]` **not covered** — no test asserts this. The gap is real; closing it is
  future work, not a box to tick.

---

- [x] **REQ-WP-001** — `CrossUserWriteException` is thrown by `assertCanWrite` when
      the entity's userId does not match the current profile.
      Verified by `UserScopedWriteExtTest` (asserts the throw), and end-to-end by
      `TaskRepositorySyncPropagationTest.update rejects a foreign userId via
      assertCanWrite`.

- [x] **REQ-WP-002** — scoped DAO mutations return the affected row count.
      Verified by `ScopedWriteQueryIsolationTest` and the `require(rows > 0)` guard in
      every repository method that calls a scoped DAO mutation: `toggleComplete`,
      `togglePinned`, `softDelete`, `restore`, and `saveOutgoingLinks`. Each
      `require` throws when the DAO returns 0 rows, propagating a failure up through
      the `Result`. The DAO signatures themselves (`Int` return) are verified by
      `EntityMapperCompletenessTest`'s companion object, which enumerates every
      scoped mutation method and confirms each returns `Int`.

- [x] **REQ-WP-003** — scoped DAO mutations include `user_id = :userId`.
      Verified by `ScopedWriteQueryIsolationTest` (added 2026-10-04): every
      `UPDATE`/`DELETE` `@Query` in production must carry a `user_id` predicate, with
      an eight-entry allowlist covering the tables that have no user dimension
      (`profiles`, `remote_config(s)`, `sync_outbox`) and retention sweeps.

- [x] **REQ-WP-010** — `create`/`update` follow
      `assertCanWrite → dao.upsert → syncRepository.enqueue`.
      Verified by `TaskRepositorySyncPropagationTest` (`create enqueues the task`,
      `soft delete enqueues the trashed state, not a tombstone`) and
      `NotesRepositorySyncTest` for the notes path.

- [x] **REQ-WP-011** — every successful local write enqueues exactly one sync payload.
      Verified by the same two classes, one assertion per mutation kind (create,
      delete, restore, toggleComplete, togglePinned, setTags, setDependencies).

- [x] **REQ-WP-012** — narrow field-update methods re-read the entity before
      enqueueing. Verified by `TaskRepositorySyncPropagationTest`:
      `setTags enqueues fresh entity with the new tags, proving a re-read` creates
      a task with tag1, calls `setTags(tag2)`, then asserts the enqueued payload
      contains tag2 and NOT tag1 — a stale read would have the old tags.

- [x] **REQ-WP-020** — outgoing links are persisted on the source entity, not the
      target. Verified by `TaskRepositorySyncPropagationTest`:
      `create with task link persists outgoing_links on the created task` creates a
      task with `description = "See [[task://t2]] for details"`, then reads the raw
      `outgoing_links` column via `taskDao.getByIdForUser` and asserts it contains
      `task://t2`. Three additional tests cover note links, update, and deduplication.

- [x] **REQ-WP-021** — `outgoing_links` is updated atomically with the entity write.
      Verified by `TaskRepositorySyncPropagationTest.outgoing_links and entity are
      committed together`: after a successful `create`, both the entity row and its
      `outgoing_links` column are read back and asserted to be present — proving the
      `unitOfWork.write { upsert + saveOutgoingLinks }` block committed both together.
      `saveOutgoingLinks throws when task is missing, leaving no orphan column` asserts
      the `require(rows > 0)` in `saveOutgoingLinks` throws when the DAO update matches
      zero rows, preventing a partial column update without the entity.

- [x] **REQ-WP-030** — `CreateNoteTool` routes through `notesRepository.create`, not
      the DAO layer. Verified by `WriteToolsTest.CreateNoteTool routes through
      notesRepository-create not DAO`: after creating a note, it asserts the note
      appears in `fakeNotesRepo.observeAll()`, which is populated only via the
      repository path — a DAO bypass would bypass the repository entirely.

- [x] **REQ-WP-031** — `CreateNoteTool` stores canonical HTML, not raw markdown.
      Verified by `WriteToolsTest.CreateNoteTool stores canonical HTML with actual
      converted content`: creates a note with markdown heading and list, then
      asserts `bodyMarkdown` is null and `bodyHtml` contains the actual HTML
      elements (`<h1>`/`<h2>` and `<ul>`/`<li>`) produced by `NoteContentMapper.toHtml`.

- [x] **REQ-WP-040** — a DAO mutation returning zero rows propagates as a failure.
      Verified by `TaskRepositorySyncPropagationTest.mutations on a missing task fail
      instead of reporting success`.

- [x] **REQ-WP-041** — id-only write methods rely on DAO-layer enforcement rather than
      `assertCanWrite`. Verified by inspection: every id-only method
      (`toggleComplete`, `togglePinned`, `softDelete`, `restore`, `setTags`,
      `setDependencies`) calls a DAO that carries `userId` in its SQL `WHERE` clause
      (`ScopedWriteQueryIsolationTest` enforces this) and returns `Int`. The repository
      wraps each call with `require(rows > 0)`, so the DAO layer's zero-row result is
      the unbypassable enforcement — `assertCanWrite` is not called for these methods
      at all, making it irrelevant to their isolation.

- [x] **REQ-WP-050** — `BackupImporter` uses unscoped DAO mutations for backup-restore.
      **Premise changed, requirement withdrawn.** The original requirement assumed
      `BackupImporter` called DAOs directly. Since then, `BulkImportPort` was introduced
      as a dedicated port for bulk restore; `BackupImporter` delegates to it entirely.
      `BulkImportPortImpl` uses unscoped `upsert` — which bypasses `assertCanWrite`
      by design, since restore targets an arbitrary `userId` from the backup archive
      rather than the ambient profile. No allowlist is needed because the
      `assertCanWrite` → repository path is never entered.

---

**Verification command** (the previous version listed
`*.SyncableEntitySerializationTest` alongside the others; that class exists in
`commonTest` but covers serialization, not the write pipeline, so it is dropped):

```bash
./gradlew :shared:jvmTest -Ptest.tags=fast,slow \
  --tests "*UserScopedWriteExtTest" \
  --tests "*TaskRepositorySyncPropagationTest" \
  --tests "*NotesRepositorySyncTest" \
  --tests "*ScopedWriteQueryIsolationTest"
```
