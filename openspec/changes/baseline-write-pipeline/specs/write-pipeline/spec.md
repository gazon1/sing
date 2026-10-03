# Write Pipeline — Observable Behavior

**capability:** `write-pipeline` | **status:** baseline

---

## ADDED Requirements

### Requirement: REQ-WP-001

When a write targets an entity whose `userId` is neither the current profile's `userId` nor `UserId.anonymous`, the system **SHALL** reject the write before any local storage operation and return a failure result.

**Rationale:** `UserId.anonymous` entities may be stamped with the current profile's `userId` by the caller before the write. This guard catches the case where the caller's intent and the entity's embedded `userId` disagree.

**Test coverage:** `FakeRepositoryFidelityTest` exercises cross-profile rejection via `assertCanWrite`.

#### Scenario: Cross-profile write rejected
- Current profile is `profile-A`
- Caller attempts to write an entity with `userId = profile-B`
- Write is rejected before DAO is called

---

### Requirement: REQ-WP-002

Scoped DAO mutations **MUST** take `userId` as a parameter and return the number of affected rows. A return value of `0` indicates the write was rejected — either the row does not exist or it belongs to another profile.

**Test coverage:** `EntityMapperCompletenessTest` (Konsist) verifies every DAO mutation in the corpus carries `userId` in its signature.

#### Scenario: DAO signature inspection
- All DAO mutation methods are enumerated
- Each accepts `userId` as a parameter
- Each returns `Int` (affected row count)

---

### Requirement: REQ-WP-003

Scoped DAO mutations **MUST** include `user_id = :userId` in the WHERE clause of every `UPDATE`, `DELETE`, and `INSERT ... SELECT` statement.

**Test coverage:** `EntityMapperCompletenessTest` verifies SQL text of every annotated DAO method.

#### Scenario: SQL WHERE clause inspection
- All scoped DAO mutation SQL is extracted
- Each contains `user_id = :userId` in its WHERE clause

---

### Requirement: REQ-WP-010

Every entity-carrying write (`create`, `update`) **MUST** follow this sequence: (1) Call `assertCanWrite(entityId, entity.userId)` — throws `CrossUserWriteException` on violation, (2) write to DAO (upsert or targeted UPDATE), (3) enqueue sync payload via `syncRepository.enqueue(entity)`.

**Test coverage:** `WriteToolsTest` exercises the AI write path; `TaskOutgoingLinksTest` exercises backlinks; `FakeRepositoryFidelityTest` verifies the full sequence on fakes.

#### Scenario: Create task via AI tool
- User requests AI to create a task
- `assertCanWrite` is called
- DAO upsert is executed
- `syncRepository.enqueue` is called once

---

### Requirement: REQ-WP-011

For every successful local write, the sync outbox **MUST** receive exactly one payload representing the entity's post-write state.

#### Scenario: create(task) enqueues one payload
- `create(task)` is called
- Exactly one entity payload is enqueued

#### Scenario: update(task) enqueues one payload
- `update(task)` is called with modified fields
- Exactly one entity payload with updated fields is enqueued

**Test coverage:** `FakeRepositoryFidelityTest` asserts every repository method that should enqueue does; `SyncableEntitySerializationTest` verifies `toJson()` round-trips for all entity types.

---

### Requirement: REQ-WP-012

Narrow field-update methods (`toggleComplete`, `togglePinned`, `setTags`, `setDependencies`, `softDelete`, `restore`) **MUST** re-read the entity from the database and enqueue the fresh state, not the caller's stale copy.

**Rationale:** These methods use targeted `UPDATE` statements rather than a full-row upsert. The entity version the caller holds may lag behind the actual database state.

**Test coverage:** `TaskRepositoryImpl` calls `enqueueFresh(id)` for all narrow methods; `FakeRepositoryFidelityTest` verifies enqueue is called.

#### Scenario: toggleComplete re-reads entity before enqueueing
- `toggleComplete(taskId)` is called
- Entity is re-read from the database
- Fresh state is enqueued to sync

---

### Requirement: REQ-WP-020

When a task or note is created or updated with a body/description containing `[[task://<id>]]` or `[[note://<id>]]` links, the system **MUST** persist the reverse reference in the `outgoing_links` column as a JSON array on the source entity, and the forward reference **MUST NOT** be persisted on the target entity.

#### Scenario: Create task linking to existing task
- User creates Task B with description `See [[task://<A>]]`
- `outgoing_links` on Task B is updated with `["task://<A>"]`
- Task A is not modified

#### Scenario: Update note linking to another note
- User updates Note X with `Related to [[note://<Y>]]`
- `outgoing_links` on Note X is updated with `["note://<Y>"]`
- Note Y is not modified

**Test coverage:** `TaskOutgoingLinksTest` covers task→task links; `WriteToolsTest` covers AI-created notes with links.

---

### Requirement: REQ-WP-021

`outgoing_links` **MUST** be updated atomically with the entity write — within the same write transaction, so a crash between entity upsert and link update does not leave orphaned forward/backward state.

**Test coverage:** Both `TaskRepositoryImpl` and `NotesRepositoryImpl` call `setOutgoingLinksForUser` in the same suspend function as the entity upsert, before `syncRepository.enqueue`.

#### Scenario: Link update in same transaction as entity write
- Entity upsert and link update execute in one database transaction
- If link update fails, entire transaction rolls back

---

### Requirement: REQ-WP-030

`CreateNoteTool` **MUST** obtain the current user from `ProfileAwareCurrentUser.scopedUserId`, stamp the generated entity with that `userId`, and delegate to `notesRepository.create(note)`. `CreateNoteTool` **MUST NOT** call the DAO directly.

**Test coverage:** `WriteToolsTest` exercises `CreateNoteTool` and verifies the note is persisted via the repository.

#### Scenario: CreateNoteTool uses repository, not DAO
- `CreateNoteTool.execute()` is called
- `notesRepository.create()` is invoked
- No DAO is called directly

---

### Requirement: REQ-WP-031

`CreateNoteTool` **MUST** convert incoming markdown to canonical HTML via `NoteContentMapper.toHtml` before storing the entity. The `bodyMarkdown` field on the stored entity **MUST** be `null`.

**Rationale:** Notes are stored with `bodyHtml` as the canonical representation. AI-created notes that skip the HTML conversion leave `bodyHtml=null`, causing the editor to fall back to `toHtml()` on open and permanently storing markdown in the legacy field.

**Test coverage:** `WriteToolsTest` verifies `bodyHtml` is non-null and `bodyMarkdown` is null after `CreateNoteTool.execute`.

#### Scenario: Markdown converted to HTML before storage
- AI generates note with markdown body
- `NoteContentMapper.toHtml` is called
- Stored entity has `bodyHtml` set, `bodyMarkdown` is null

---

### Requirement: REQ-WP-040

When a DAO mutation returns `0` rows for a scoped operation, the repository **MUST** propagate a failure to the caller rather than silently succeeding.

#### Scenario: `toggleComplete(taskId)` where `taskId` belongs to another profile → `rows == 0` → `IllegalArgumentException` thrown and propagated as `Result.failure`.

---

### Requirement: REQ-WP-041

`assertCanWrite` **MUST NOT** be the sole ownership check for ID-only write methods (`toggleComplete`, `softDelete`, `setTags`, `setDependencies`). The DAO layer's `userId` filter is the unbypassable enforcement for those methods.

#### Scenario: toggleComplete rejected at DAO layer, not assertCanWrite
- `toggleComplete(taskId)` is called
- DAO executes UPDATE with `WHERE id = :id AND user_id = :userId`
- If userId mismatch, rows == 0, `IllegalArgumentException` is thrown

---

### Requirement: REQ-WP-050

`BackupImporter` **MUST** use unscoped DAO mutations for backup-restore operations. These methods are explicitly documented as backup-restore only and are allowlisted in `EntityMapperCompletenessTest`.

**Rationale:** Import targets an arbitrary `userId` while repositories read the ambient scoped user. The layer boundary cannot be crossed without unscoped writes.

#### Scenario: BackupImporter writes to arbitrary userId
- `BackupImporter.restore()` is called
- DAO receives `userId` from the backup archive
- Entity is written without profile isolation check
