# Auto-diary (Автодневник) — verification plan

## Verification objectives

Prove idempotent creation, missing-standup detection, migration safety, and concurrency serialization for project-scoped daily notes.

## Acceptance criteria coverage

| AC | Scenarios |
|---|---|
| AC-01 | V-01, V-02, V-04 |
| AC-02 | V-03, V-05 |
| AC-03 | V-06 |

## Unit tests

### V-01 — Idempotent create returns same id
- **Acceptance:** AC-01
- **Transition:** `Absent` --`create`--> `Present`
- **Preconditions:** FakeNotesRepository, no existing note
- **Sequence:** create(P,D); create(P,D)
- **Expected state:** both calls return same NoteId
- **Expected side effects:** one insert, one sync enqueue
- **Invariants checked:** INV-01, INV-03
- **Test level:** unit
- **Platform:** common
- **Deterministic reproduction:** `./gradlew :shared:jvmTest --tests 'AutoDiaryRepositoryTest'`

### V-02 — Re-entry after present
- **Acceptance:** AC-01
- **Transition:** `Present` --`create`--> `Present`
- **Preconditions:** note exists for (P,D)
- **Sequence:** create(P,D)
- **Expected state:** returns existing id, no insert
- **Invariants checked:** INV-01, INV-02
- **Test level:** unit
- **Platform:** common
- **Deterministic reproduction:** `./gradlew :shared:jvmTest --tests 'AutoDiaryRepositoryTest'`

### V-03 — Missing standup detection
- **Acceptance:** AC-02
- **Transition:** `Absent` --`check`--> `Absent`
- **Preconditions:** FakeNotesRepository empty
- **Sequence:** check(P, yesterday)
- **Expected state:** returns null/absent
- **Invariants checked:** INV-01
- **Test level:** unit
- **Platform:** common
- **Deterministic reproduction:** `./gradlew :shared:jvmTest --tests 'AutoDiaryTriggerTest'`

### V-04 — Concurrent create serialized
- **Acceptance:** AC-01
- **Transition:** two `Absent` --`create`--> race → one `Present`
- **Preconditions:** in-memory DB with unique index; two coroutines
- **Sequence:** launch create(P,D) twice concurrently; joinAll
- **Expected state:** both return same id; one row
- **Invariants checked:** INV-01, INV-03
- **Test level:** integration
- **Platform:** jvm
- **Deterministic reproduction:** `./gradlew :shared:jvmTest --tests 'AutoDiaryConcurrencyTest'`

### V-05 — Failure injection: insert throws
- **Acceptance:** AC-02
- **Transition:** `Creating` --fail--> `Failed`
- **Preconditions:** FakeNotesRepository configured to throw on insert
- **Sequence:** create(P,D)
- **Expected state:** Result.failure; no row; trigger reports missing
- **Invariants checked:** INV-01
- **Test level:** unit
- **Platform:** common
- **Deterministic reproduction:** `./gradlew :shared:jvmTest --tests 'AutoDiaryRepositoryTest'`

### V-06 — Present standup → no prompt
- **Acceptance:** AC-03
- **Transition:** `Present` --`check`--> `Present`
- **Preconditions:** note exists for previous day
- **Sequence:** check(P, yesterday)
- **Expected state:** returns note; trigger does not prompt
- **Invariants checked:** INV-01
- **Test level:** unit
- **Platform:** common
- **Deterministic reproduction:** `./gradlew :shared:jvmTest --tests 'AutoDiaryTriggerTest'`

## Integration tests

- Migration test: v49→v50 adds `project_id` nullable + partial unique index; existing rows get null. `Migration49To50Test` (slow tag).

## UI / E2E tests

Not applicable — no new UI; daily-notes list already exists.

## State-transition coverage

All transitions in model.md table covered by V-01..V-06 except S3 Failed→Creating (retry) which is covered by V-05 re-run expectation.

## Concurrency and cancellation tests

- V-04 covers parallel. Cancellation mid-flight: verify transaction rollback via `runTest` + cancellation; covered as part of V-04 variant (known limitation: DebugProbes JVM-only).

## Failure injection and recovery

- Fake throwing on insert (V-05); recovery via retry after restart (integration migration test).

## Regression tests

- Existing `NotesRepositorySyncTest` and daily-notes tests must still pass (no behavior change for `projectId = null`).

## Observability and diagnostics

Not applicable — no new diagnostic; existing sync logging covers enqueue failures.

## Commands and expected outcomes

- `./gradlew :shared:jvmTest --tests 'AutoDiary*'` → all pass.
- `./gradlew :shared:jvmTest -Ptest.tags=slow --tests 'Migration49To50Test'` → pass.

## Known limitations

- Cancellation assertion via `DebugProbes.dumpCoroutines()` is JVM-only; Android cancellation scenario not covered here.
- Agent hook (external) is out of repo and not verified by this suite.
- MCP tool wiring (`get_daily_note`, `list_projects(includeArchived)`) verified by existing MCP end-to-end tests only if extended; gap noted.