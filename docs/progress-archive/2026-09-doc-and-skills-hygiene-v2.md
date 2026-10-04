<!-- Archived from PROGRESS.md on 2026-10-05. This epic is complete:
     every phase is marked merged and its deliverables are on main.
     Kept for history, not read as current state. -->

## Epic: doc-and-skills-hygiene-v2

**Start date:** 2026-09-27
**Status:** in progress
**Epic branch:** `sprint/doc-skills-v2` (git worktree at `~/worktrees/sprint-doc-skills-v2/`)

### Phase A — stale-ref cascades ✅ COMMITTED (`55acab73`)

13 cascades batched by root cause (Clock removal, MVI flatten, `ConflictResolver.merge()`,
`SharedEventBus`, `isDesktop`, `ChecklistUseCase`, `NoteEditor`/`NotePreview` renames,
`BackupViewModel` path, orgmode ADR path, DI narrative, dead-file refs).

- AGENTS.md 387 → 210 lines
- 3 ADRs with body text swallowed by frontmatter — rebuilt (they broke digest parsing)
- 2 ADRs missing H1 — added
- 6 ADRs got supersedure banners; new DI-narrative ADR written

#### Phase A retro (2026-09-27)
- The pre-sprint audit was produced against a different branch (`fix/repair-broken-build`)
  than the worktree base (`main`); 2 of 13 cascades had to be re-verified against real code
  and one turned out to be a false positive (`TaskDetailViewModel.kt` still exists)
- `StatefulViewModel.kt` and `StateStrategy.kt` were never written despite the ADR
  describing them — the ADR now says so explicitly
- `ModelPricing` / `UsageExtractor` are documented in a skill but never implemented; AI
  Usage cost column is therefore always empty
- Chasing "why is this ref documented?" repeatedly turned up *design* that was never built
- Triage backlog recorded in `docs/decisions/2026-09-27-doc-and-skills-sprint-findings.md`

---
