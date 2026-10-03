# Tasks — baseline-write-pipeline

**Status: no tasks** — this is a baseline spec. The system already implements the documented behavior.

The tasks below are a verification checklist, not implementation work. Each requirement maps to an existing test that confirms it holds.

---

- [ ] **Verify REQ-WP-001** — `CrossUserWriteException` is thrown by `assertCanWrite` when entity userId mismatches current profile. Covered by `FakeRepositoryFidelityTest`.

- [ ] **Verify REQ-WP-002** — All scoped DAO mutations carry `userId` and return affected row count. Covered by `EntityMapperCompletenessTest` (Konsist architecture test).

- [ ] **Verify REQ-WP-003** — Scoped DAO mutations include `user_id = :userId` in WHERE clause. Covered by `EntityMapperCompletenessTest`.

- [ ] **Verify REQ-WP-010** — `create`/`update` follow `assertCanWrite → dao.upsert → syncRepository.enqueue`. Covered by `FakeRepositoryFidelityTest`.

- [ ] **Verify REQ-WP-011** — Every successful local write enqueues exactly one sync payload. Covered by `FakeRepositoryFidelityTest`.

- [ ] **Verify REQ-WP-012** — Narrow field-update methods re-read entity before enqueueing. Covered by `FakeRepositoryFidelityTest`.

- [ ] **Verify REQ-WP-020** — Outgoing links are persisted on the source entity, not the target. Covered by `TaskOutgoingLinksTest` and `WriteToolsTest`.

- [ ] **Verify REQ-WP-021** — `outgoing_links` is updated atomically with the entity write. Covered by `TaskOutgoingLinksTest`.

- [ ] **Verify REQ-WP-030** — `CreateNoteTool` routes through `notesRepository.create`, not the DAO layer. Covered by `WriteToolsTest`.

- [ ] **Verify REQ-WP-031** — `CreateNoteTool` stores canonical HTML, not raw markdown. Covered by `WriteToolsTest`.

- [ ] **Verify REQ-WP-040** — DAO mutations returning 0 rows propagate as failures. Covered by `FakeRepositoryFidelityTest`.

- [ ] **Verify REQ-WP-041** — ID-only write methods rely on DAO layer enforcement, not `assertCanWrite`. Covered by `EntityMapperCompletenessTest`.

- [ ] **Verify REQ-WP-050** — BackupImporter unscoped mutations are allowlisted in `EntityMapperCompletenessTest`. Covered by the allowlist itself.

---

**Verification command:**

```bash
./gradlew :shared:jvmTest --tests "*.FakeRepositoryFidelityTest" \
                            --tests "*.EntityMapperCompletenessTest" \
                            --tests "*.WriteToolsTest" \
                            --tests "*.TaskOutgoingLinksTest" \
                            --tests "*.SyncableEntitySerializationTest"
```
