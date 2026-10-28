# Auto-diary (Автодневник) — state model

## Purpose and scope

Models the lifecycle of a daily standup note for a (project, date) key and the trigger that detects a missing one. Covers in-app creation/idempotency and the abstract trigger state. Does NOT model the agent hook (out of repo), UI rendering, or sync.

## Model boundary

`NotesRepository.getOrCreateDailyNote` and the project-scoped successor, plus the trigger's "missing?" query. Implementation mapping: `NotesRepositoryImpl.kt` (daily methods ~285-375), `RoomNotesRepository` (per ADR), and a (future) `AutoDiaryTrigger`.

## States

- `S0 Absent` — no daily note row for (project, date).
- `S1 Present` — daily note row exists for (project, date).
- `S2 Creating` — creation in flight (transient).
- `S3 Failed` — creation attempted and failed; no row.

## Events and triggers

- `E1 create(project, date)` — request to create/get daily note.
- `E2 check(project, date)` — trigger query: is a standup present for previous day?
- `E3 retry(project, date)` — re-attempt after failure.

## Transition table

| Source | Event / trigger | Guard | Target | State mutation | Invariants checked | Side effects | Failure behaviour | Cancellation behaviour | Verification ref |
|---|---|---|---|---|---|---|---|---|---|
| S0 | E1 create | key not present | S2 Creating | begin insert | INV-01, INV-03 | DB insert | on fail → S3 | insert aborted, row absent | AC-01 |
| S2 | E1 create (completes) | insert ok | S1 Present | row persisted | INV-01, INV-02 | sync enqueue | n/a | n/a | AC-01 |
| S2 | E1 create (completes) | unique violation (race) | S1 Present | return existing id | INV-01 | none | n/a | n/a | AC-01 |
| S2 | E1 create (fails) | IO error | S3 Failed | no row | INV-01 | log failure | reported, no retry loop | partial state none | AC-02 |
| S1 | E1 create | key present | S1 Present | return existing id | INV-01, INV-02 | none | n/a | n/a | AC-01 |
| S0 | E2 check | n/a | S0 Absent | none | INV-01 | none | n/a | n/a | AC-02 |
| S1 | E2 check | n/a | S1 Present | none | INV-01 | none | n/a | n/a | AC-03 |
| S3 | E3 retry | n/a | S2 Creating | begin insert | INV-01, INV-03 | DB insert | on fail → S3 | aborted | AC-02 |

## Side effects

- DB insert of `Note` (kind=Daily, title=date key, projectId=project).
- Sync outbox enqueue (existing note-sync path).
- Trigger: prompt emission (agent-side, out of repo).

## Failure transitions

- Insert failure (IO/DB) → S3 Failed; logged; no automatic retry loop; caller may issue E3.
- Project missing → no-op, no row (guard: project exists).

## Cancellation and shutdown

- Cancellation between begin-insert and commit: transaction rolls back; state remains S0. No partial row. Sync enqueue only after commit.

## Concurrency and serialization

- DB unique index (partial, `kind='Daily'`) on `(title, project_id)` serializes creation across processes. Repository-level lock is insufficient for MCP cross-process access. Concurrent E1 calls: one wins insert, others get unique violation → return existing id (S1).

## Recovery and retry

- Process restart after S2 without commit: row absent → state S0; E1 re-runnable (idempotent).
- Process restart after S1: row present → S1.

## Invariants

- INV-01 (at most one daily note per project+date): checked after every transition; enforced by unique index.
- INV-02 (project association immutable): no transition mutates `projectId` of an existing row.
- INV-03 (idempotency under concurrency): checked by unique-violation path returning existing id.

## Counterexamples

1. **Re-entry**: E1 twice for same key → second returns existing id, no duplicate. Expected: S1 after both.
2. **Parallel calls**: two callers E1 concurrently → unique index ensures one insert, other gets existing. Expected: both observe same id.
3. **Cancellation mid-flight**: cancel during insert → transaction rollback, state S0, no row. Expected: retry creates fresh.
4. **Restart**: process restart after partial → S0; E1 re-run succeeds. Expected: no duplicate.

## Mapping to implementation

- `NotesRepository.getOrCreateDailyNote(dateKey, fromTemplateId)` → extended signature with `projectId`.
- `NotesRepositoryImpl` / `RoomNotesRepository` daily methods (~285-375 per ADR).
- DB migration v22→v23 + unique index.
- MCP: `get_daily_note`, `list_projects(includeArchived)`.

## Verification coverage

- AC-01 → idempotency transitions (S0→S2→S1, S1→S1).
- AC-02 → S0 check + failure path.
- AC-03 → S1 check.