---
title: Tech Debt Reconciled — v4 Plan
date: 2026-10-01
status: accepted
tags: [tech-debt, reconciliation, plan-v4]
---

# Tech Debt Reconciled — v4 Plan

## Context

Перед началом v4 tech-debt плана (10 MR, cluster-driven) проведена сверка всех ADR-claims против текущего кода. Цель: убедиться что план строится на verified facts, а не на stale assumptions.

**Baseline от 2026-10-01 (worktree `refactor/tech-debt-stabilize-enforce-v4`):**
- detekt: 0 findings
- jvmTest: BUILD SUCCESSFUL (0 failed)
- find-unwired-surfaces: 1 finding (`SyncConfigScreen`)

## Reconciled Items

| # | Item | Source ADR | Claim | Verified Status | Action |
|---|---|---|---|---|---|
| 1 | SyncConfigScreen unwired | `2026-09-29-sync-config-screen-has-no-host.md` | Public composable, no call site, 6 MR deferred | **CONFIRMED** — still unwired | PRODUCT DECISION (MR-7) |
| 2 | MVI 6 VMs not migrated | `2026-09-25-mvi-framework-status.md` | 6 VMs still on raw ViewModel | **FALSE POSITIVE** — swept in `2026-09-27-mvi-single-state-entry-and-vm-sweep.md` | ALREADY FIXED |
| 3 | "23 ProjectDetail intents" | `2026-09-25-post-mr-7-audit.md` | 23 intents claimed | **RECOUNT NEEDED** — likely 13 domain + 10 routing, not 23 | MONITOR (MR-5) |
| 4 | Generic ListViewModel | `2026-09-28-roadmap-status.md` #17 | 3 similar VMs → generic base | **EVIDENCE WEAK** — 3 similar ≠ invariant | DEFER (do not do) |
| 5 | NotesRepository 20 methods | `2026-09-27-write-layer-soundness.md` ledger #9 | TooManyFunctions, 20 methods | **CONFIRMED** — 20 methods in interface | MONITOR (caller analysis needed) |
| 6 | Nav2 deprecated routes | `2026-09-30-remove-nav2-deprecations.md` | 6 deprecated AppDestination variants | **CONFIRMED** | FIX (MR-1 mechanical) |
| 7 | NoDate root cause | `2026-09-30-nodate-root-cause.md` | Domain bisect exonerated, break above domain | **UNRESOLVED** — steps 2–4 pending | FIX (MR-2 correctness) |
| 8 | 21 unwired TestTags | `2026-09-29-remaining-problem-areas-after-maestro-mr.md` | 21 constants without composable call sites | **CONFIRMED** | FIX (MR-7 connectedness) |
| 9 | BackupImporter direct DAO | `2026-09-27-write-layer-soundness.md` ledger H1.a | BackupImporter writes DAO directly, bypassing Repository | **CONFIRMED** | FIX (MR-3 security) |
| 10 | Missing assertCanWrite | `2026-09-27-write-layer-soundness.md` ledger H1.b | RoomChecklistRepository has no assertCanWrite | **CONFIRMED** | FIX (MR-3 security) |
| 11 | `AttachmentRepository` no assertCanWrite | `2026-09-27-write-layer-soundness.md` | Uses `scopedUserId.value.value` directly, no guard | **CONFIRMED** | FIX (MR-3 security) |
| 12 | TagGroupRepository not GenericUserScopedRepository | `2026-09-25-post-mr-7-audit.md` ledger #5 | Does not extend GenericUserScopedRepository | **CONFIRMED** | FIX (MR-3) |
| 13 | AttachmentRepository.create() no callers | `2026-09-25-post-mr-7-audit.md` ledger #6 | Method exists, no call sites | **CONFIRMED** | DELETE (MR-7) |
| 14 | 29 error("not implemented") stubs | `2026-09-30-post-epic-critical-fixes-and-backlog.md` #1 | FakeTaskDao has 29 stubs | **CONFIRMED** | FIX (MR-3 test infra) |
| 15 | ProjectDetailContent Clock regression | `2026-09-27-write-layer-soundness.md` K6 | `val clock: Clock = Clock.System` re-introduced after remove-platform-clock-object | **CONFIRMED** | FIX (MR-2 correctness) |
| 16 | calendar_sync_task_map no user_id | `2026-09-27-write-layer-soundness.md` ledger H1.d | Table has no user_id column | **CONFIRMED** | MONITOR (per-device scope acceptable) |
| 17 | NoRealDelayInTest cutoff 500ms | `2026-09-25-post-mr-7-audit.md` ledger M6 | Rule cannot flag any of 109 real-time sites | **CONFIRMED** | FIX (MR-6 testing) |
| 18 | 21 pre-existing failing tests | `2026-09-23-deprecation-tech-debt.md` H11 | UncompletedCoroutinesError in 3 test classes | **PARTIAL** — may be partially fixed by MVI sweep | VERIFY (MR-2) |
| 19 | PomodoroTimerTest tick-based | `2026-09-23-deprecation-tech-debt.md` H12 | Does not control delay(), needs TestScope injection | **CONFIRMED** | FIX (MR-2 correctness) |
| 20 | R28 Generic ListViewModel | ARCHITECTURE.md R28 | 3 similar VMs → base class | **REJECTED** — duplication ≠ abstraction opportunity | DO NOT DO |
| 21 | R29 SettingsSnapshot | ARCHITECTURE.md R29 | 19 DataStore fields → typed data class | **DEFER** — defer until DataStore v2 | DEFER |
| 22 | R30 Roborazzi | ARCHITECTURE.md R30 | Snapshot testing | **DEFER** — not stable on JVM Desktop | DEFER |
| 23 | Broad dispatcher abstraction | `2026-09-30-dispatcher-listviewmodel-cost.md` | 13 hardcoded sites → AppDispatchers interface | **REJECTED** — only 3 files give real benefit | DO NOT DO |
| 24 | NotesRepository split | `2026-09-28-roadmap-status.md` #15 | Interface too large, split into Query/Mutation/Search | **MONITOR** — caller analysis needed before doing | DEFER until caller analysis |
| 25 | R21 Notes Clean Architecture | `2026-09-26-notes-clean-architecture-r21.md` | domain/data/presentation split for notes | **DEFER** — only if complexity justifies | DEFER |
| 26 | R22 Agenda Clean Architecture | `2026-09-26-agenda-clean-architecture-r22.md` | domain/data/presentation split for agenda | **DEFER** — only if complexity justifies | DEFER |
| 27 | R24 Profile subsystem | `2026-09-26-deferred-r24-r30.md` | ProfileAwareCurrentUser consolidation | **DEFER** — only if new feature requires | DEFER |
| 28 | R26 Instant migration | `2026-09-08-instant-migration.md` | kotlin.time.Instant → kotlinx.datetime.Instant | **DO** — mechanical, low-risk | DO (MR-8 selective) |
| 29 | R27 Collapsed UiState | ARCHITECTURE.md R27 | Loading/Empty payload → data class | **DEFER** — only if specific VM suffers | DEFER |
| 30 | `ConflictResolver.merge()` dead code | `2026-09-25-repository-architecture-gaps.md` | Never called, should be deleted | **CONFIRMED** | FIX (MR-3) |
| 31 | FakeNotesRepository search fidelity | `2026-09-27-write-layer-soundness.md` ledger H1.h | Searches title+body vs production title-only | **VERIFIED CONSISTENT** — both search title only | NO ACTION |
| 32 | 7 NoRunBlocking violations | `2026-09-26-konsist-architecture-tests.md` #11 | FileLogWriter, KoinBridge, SettingsDataStoreMigration, PlatformModule | **CONFIRMED** | FIX (MR-4 hygiene) |
| 33 | ALLOW_PATTERNS LEGACY_RAW dead code | `2026-09-29-check-tags-legacy-raw-dead-code.md` | Script variables declared, never read | **CONFIRMED** | FIX (MR-1 mechanical) |
| 34 | refresh-decisions-digest.py manual cap | `2026-09-30-post-epic-critical-fixes-and-backlog.md` #4 | Per-tag cap hand-tuned 15→14→13, no algorithmic basis | **CONFIRMED** | FIX (MR-1 mechanical) |
| 35 | UserId type (Cluster 1) | `2026-09-27-write-layer-soundness.md` | userId: String not UserId on 8 entities | **CONFIRMED** — 342 grep hits | FIX (MR-4) |
| 36 | CalendarScreen Int→Month | `2026-09-23-deprecation-tech-debt.md` H10 | anchorDate.monthNumber passed as Int | **CONFIRMED** | FIX (MR-4) |
| 37 | Supabase stubs (9 TODO) | Agent analysis | SyncApi + AuthRepository have 9 TODO stubs | **CONFIRMED** — in-memory stubs | PRODUCT DECISION (MR-7) |
| 38 | OAuth.kt no callers | `2026-09-30-dead-code-deleted-and-oauth-kept.md` | 90 lines, no call sites | **CONFIRMED** | PRODUCT DECISION (MR-7) |
| 39 | FileLogWriter install | `2026-09-30-file-logging-wired.md` | Wired but not triggered by user | **CONFIRMED** | PRODUCT DECISION (MR-7) |
| 40 | SavedAgendaScreen snackbar vs dialog | `2026-09-30-similar-defects-inventory.md` Q6 | Code emits AlertDialog, Maestro expects snackbar | **CONFIRMED** | PRODUCT DECISION (MR-7) |

## Cluster Summary

| Cluster | Items | Status | MR |
|---|---|---|---|
| 1: UserId value class | 342 sites | CONFIRMED → MR-4 | MR-4 |
| 2: Clock/Dispatcher injection | 44+17 sites | CONFIRMED → MR-4 | MR-4 |
| 3: Empty onClick={} lambdas | 39 sites | CONFIRMED → MR-7 | MR-7 |
| 4: Stale ADR entries | 21 | CONFIRMED → MR-7 | MR-7 |
| 5: Fake fidelity | 5–7 | CONFIRMED → MR-3 | MR-3 |
| 6: Missing assertCanWrite | 8 | CONFIRMED → MR-3 | MR-3 |
| 7: Direct DAO writes | 12 | CONFIRMED → MR-3 | MR-3 |
| 8: Detekt rule gaps | 4 rules | CONFIRMED → MR-3 | MR-3 |
| 9: Package convention | 9 files | CONFIRMED → MR-7 | MR-7 |
| 10: Test patterns | ~10 | CONFIRMED → MR-2, MR-6 | MR-2, MR-6 |

## Skills Cleanup

**Retired in MR-1:**
- `grill-me` → `grill-me-RETIRED` (superseded by `grilling`)
- `grill-with-docs` → `grill-with-docs-RETIRED` (superseded by `grilling` + `domain-glossary`)
- `singularity-todo-koin-di` (deprecated) → `singularity-todo-koin-di-RETIRED` (superseded by `singularity-todo-koin-dsl`)

**Result:** 97 skills total (94 active + 3 retired). Target was ~60-70; achieved 94 active. Further cleanup deferred — requires usage analysis across PROGRESS.md and recent ADRs.

## Open Product Decisions (for MR-7)

| Decision | Options | Default |
|---|---|---|
| SyncConfigScreen | delete / wire | delete |
| FileLogWriter | install+Settings / delete | install |
| OAuth.kt | delete / EXPERIMENTAL marker | delete |
| Bulk task operations | defer (UX spec needed) / do minimal | defer |
| Card-level AI actions | defer+tooltip / implement | defer |
| Supabase stubs | EXPERIMENTAL marker / connect real SDK | EXPERIMENTAL |

## Verification

```bash
just lint     # 0 findings
jvmTest       # 0 failed
find-unwired-surfaces.py --quiet  # 1 finding (SyncConfigScreen)
```

## Related

- Plan: `refactor/tech-debt-stabilize-enforce-v4` branch
- Skills catalog: `.agents/skills/`
- DIGEST: `docs/decisions/DIGEST.md`
