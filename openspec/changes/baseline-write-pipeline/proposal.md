# baseline-write-pipeline

## What

Document the current write pipeline for user-scoped entities: profile isolation, cross-user write rejection, outbox delivery, and the AI-tool write path parity.

This is a **baseline spec** — the system already behaves this way. No behavior is being changed.

## Why

The write pipeline is the most security-sensitive path in the codebase. It has been hardened across 8 merge rounds (MR-1 through MR-8), fixing 18 distinct defect classes. The decisions are recorded in ADRs; the observable behavior is not captured in a spec.

## Scope

### In scope

- Profile-scoped write isolation: a write for one profile cannot create data in another
- `assertCanWrite` guard: entity-carrying writes throw before any DAO operation when the entity's userId does not match the current profile
- DAO-level ownership enforcement: all scoped DAO mutations take `userId` and return affected row count; a `0` is a rejected write
- Outbox delivery: every successful local write enqueues exactly one sync payload
- Backlinks maintenance: creating or updating a task/note with `[[task://id]]` or `[[note://id]]` links persists the reverse reference atomically
- AI-tool write path: `CreateNoteTool` and `CreateTaskTool` stamp the current user and route through the repository, not the DAO layer

### Out of scope

- Sync protocol details (conflict resolution, server-side behavior)
- Backup/restore import path (uses unscoped DAO variants explicitly allowlisted for that layer)
- `beginShutdown` behavior on Android (log tail may be lost)
- `RedactingLogWriter` redaction completeness (documented gaps deferred to a separate ADR)

## References

- `docs/decisions/2026-09-21-generic-user-scoped-repository.md`
- `docs/decisions/2026-09-24-dao-userid-guards.md`
- `docs/decisions/2026-09-25-no-store-library-local-first-pattern.md`
- `docs/decisions/2026-09-27-write-layer-soundness.md`
- `docs/decisions/2026-09-28-mr3-repository-read-isolation.md`

<!-- 2026-10-05: three links here pointed at ADRs that were never written —
     2026-10-03-assert-canwrite-adr.md, 2026-10-03-write-integrity-phase0a.md and
     2026-10-03-write-integrity-phase0b.md. Removed rather than repointed: no
     ADR with equivalent content exists, and a spec for this capability is
     tracked in openspec/specs/MODULE-INDEX.md. If the write-integrity work
     lands as a decision, it needs its own ADR then. -->

## Status

**Active.** This is a baseline spec — no implementation required.
