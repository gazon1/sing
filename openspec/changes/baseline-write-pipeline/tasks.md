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

- [ ] **REQ-WP-002** — scoped DAO mutations return the affected row count.
      **Not covered.** No test asserts a mutation's return value, and the previous
      attribution to `EntityMapperCompletenessTest` was wrong — that test compares
      mapper field access against a hand-maintained table.

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

- [ ] **REQ-WP-012** — narrow field-update methods re-read the entity before
      enqueueing. **Not covered.** The sync-propagation tests assert that a payload
      was enqueued, not that it was built from a fresh read.

- [ ] **REQ-WP-020** — outgoing links are persisted on the source entity, not the
      target. **Not covered.** `TaskOutgoingLinksTest` covers the JSON codec
      (`toLinksJson`, `parseLinksJson`, `extractOutgoingLinks`) and nothing about
      persistence.

- [ ] **REQ-WP-021** — `outgoing_links` is updated atomically with the entity write.
      **Not covered**, and not currently asserted anywhere.

- [ ] **REQ-WP-030** — `CreateNoteTool` routes through `notesRepository.create`, not
      the DAO layer. **Partially covered.** `WriteToolsTest.CreateNoteTool uses
      scoped userId from profile` asserts the resulting note carries the current
      profile's id, which a DAO bypass using the same id would also satisfy.

- [ ] **REQ-WP-031** — `CreateNoteTool` stores canonical HTML, not raw markdown.
      **Not covered.** No assertion on the stored HTML in `WriteToolsTest`.

- [x] **REQ-WP-040** — a DAO mutation returning zero rows propagates as a failure.
      Verified by `TaskRepositorySyncPropagationTest.mutations on a missing task fail
      instead of reporting success`.

- [ ] **REQ-WP-041** — id-only write methods rely on DAO-layer enforcement rather than
      `assertCanWrite`. **Not covered**, and the previous attribution to
      `EntityMapperCompletenessTest` was wrong.

- [ ] **REQ-WP-050** — unscoped mutations in `BackupImporter` are allowlisted.
      **Not covered, and the premise no longer holds**: the allowlist
      (`FIELD_ALLOWLIST` in `EntityMapperCompletenessTest`) is empty, and
      `BackupImporter` appears in neither `ENTITY_PARAMS` nor the mapper table. The
      requirement should be re-derived or withdrawn rather than verified.

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
