<!-- Archived from PROGRESS.md on 2026-10-05. This epic is complete:
     every phase is marked merged and its deliverables are on main.
     Kept for history, not read as current state. -->

## Epic: openspec-adoption

**Start date:** 2026-10-03
**Status:** in progress
**Worktree:** `~/work/singularity-openspec` (`refactor/openspec-adoption`)

### Phase 0 — Decision record ✅ DONE
`docs/decisions/2026-10-03-openspec-adoption.md` — ownership matrix, `skip_specs` table,
decision tree, ADR/spec relationship rules, consequences.

### Phase 1 — Gate sweep (PR-2) 🔄 IN PROGRESS
CI gates + unwired-surface detectors + quick fixes. All done in isolated worktree;
main checkout stays clean.

- Phase 1.1: `check-doc-sizes.py` + `check-doc-dead-refs.py` wired in CI as blocking ✅
- Phase 1.2: detector 7 `dead-symbol` + baseline ✅
- Phase 1.3: detector 8 `skill-dangling-symbol` + baseline ✅
- Phase 1.4: `PlatformModule.android.kt` / `.jvm.kt` parity (18 shared ports, 2 intentional
  platform-only: `CoroutineScope` and `PomodoroScheduler`) ✅
- Phase 1.5: two detekt bugs fixed (`NoDirectDispatchersRule` dead branch,
  `NoDirectClockSystemRule` KDoc) ✅
- Phase 1.6: PROGRESS + deferred-backlog + DIGEST budget reconciliation 🔄 in progress
- Phase 1.7: skill clusters (~13 skills with broken symbol refs → backlog)

**Retro step (Phase 1):** blocker count = 0; non-blocking items → `deferred-backlog.md`.

### Phase 2 — OpenSpec CLI surface (PR-3)
Install `openspec`, run `init`, verify CLI structure. Stop if assumptions don't match.
Baseline PR-3 only if CLI validates.

### Phase 3 — `openspec/config.yaml`
Write config from actual CLI surface (not assumptions). KMP context, artifact rules,
operation guidance.

### Phase 4 — `singularity-todo-openspec-workflow` skill (PR-3)
Decision tree, `skip_specs`, mandatory read order, `verify` gate. Wire into `AGENTS.md`,
`wayfinder`, `docs/doc-maintenance.md`.

### Phase 5 — Baseline change: `baseline-write-pipeline` (PR-4)
Write pipeline spec from existing code + tests. No behavior change — proof OpenSpec
can describe current state. No design document (baseline specs omit it).

### Phase 6 — Real feature: log export (PR-5 + PR-6)
`FileSharePort` + `LogBundleExporter`. Known gaps documented in the Phase 6 design
document (RedactingLogWriter redacts only message body, not `cause` chain or `tag`;
`initLogging` runs before Koin; `beginShutdown` never called on Android).

### Phase 7 — CI integration (advisory, PR-7)
`openspec validate || true`, stale-check script, skill integration. No blocking gate.
