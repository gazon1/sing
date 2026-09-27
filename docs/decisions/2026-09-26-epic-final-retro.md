---
title: Epic Final Retro — docs-and-skills-hygiene
date: 2026-09-26
status: accepted
---

# Epic Final Retro — docs-and-skills-hygiene

## Context

Epic `docs-and-skills-hygiene` completed. 11 PRs across 3 phases.

## Phase Summary

### Phase 0 — Hygiene (PR-0.0 to PR-0.4) ✅

All 4 PRs completed. Key outcomes:
- 7 detekt custom rule sets wired and registered
- Baseline established, `warningsAsErrors: true`
- KDoc enforcement rules (ViewModelMustHaveKDoc, RepositoryInterfaceMustHaveKDoc)
- 4 UI-testing ADRs superseded via `ui-testing-deferred`
- Production BAN fixes (stateIn removed, runBlocking → koinBridge)
- 23 TODO comments cleaned up

### Phase 1 — Meta-skills + Docs Infrastructure (PR-1.1 to PR-1.4) ✅

- **PR-1.1**: 5 docs-infrastructure ADRs (docs-lifecycle, adr-supersede-process, domain-glossary-policy, progress-journal-policy, skill-authoring-policy)
- **PR-1.2**: `docs/CONTEXT.md` (12 domain terms), `PROGRESS.md`, `domain-glossary` + `progress-journal` skills, ADR normalization (8 older ADRs)
- **PR-1.3**: 3 process ADRs (writer-reviewer-pattern, code-review-process, four-phases-gate) + `code-review-pr-workflow` skill
- **PR-1.4**: Skills reorg — koin-di DEPRECATED, vm-migration-playbook trimmed (15.5KB→11.9KB), feature-scaffold trimmed (38KB→17KB) + TOC added

### Phase 2 — Meta-skills + Workflow Evals + ADR Backlog (PR-2.1 to PR-2.3) ✅

- **PR-2.1**: observability-production ADR, security-review ADR, `debugging-investigation` + `security-review` skills
- **PR-2.2**: performance-profiling ADR, ux-a11y-review skill, internationalization ADR
- **PR-2.3**: Workflow Evals infrastructure (5 YAML tasks, runner, baseline) + ADR backlog (R21-R22 deferred, R23 closed, R24-R30 deferred)

## Findings

### ✅ What went well

1. **Per-PR retros** — each PR had a retro ADR documenting findings. This created an audit trail and surfaced the DIGEST size issue early.
2. **Skills within limits** — all 88 skills now pass `check-skill-frontmatter`, and all are within size limits after PR-1.4 trim.
3. **Quality gates** — `jvmTest + detekt + check-skill-frontmatter` ran consistently across all PRs.
4. **Worktree isolation** — main checkout remained on `main` throughout. Epic work in `~/worktrees/singularity-docs-hygiene/`. No interference with parallel work.
5. **ADR normalization** — 8 older ADRs gained `status: accepted` via `normalize-adr-frontmatter.sh --apply`.

### 🟡 What didn't go well

1. **DIGEST oversized** — started at 1651 lines (P0), grew to 1748 lines by end of epic. Limit is 1500. Root cause: each new ADR adds Critical bullets; 30+ ADRs in total. Structural fix deferred to follow-up epic.
2. **Worktree pre-existing bugs** — PR-1.1 exposed missing `UserMessage.kt` and `TagGroupsUiState.Empty("")` in the worktree. These were lost during earlier epic branch rebases. Root cause: epic branch not verified build-clean at start of each PR.
3. **ADR frontmatter normalization not in CI** — `just docs-audit` dry-runs normalization. Should have been `--apply` in CI from the start.
4. **PR creation in wrong directory** — ADR files were initially created in main checkout instead of worktree. Caused manual cp + rm. Root cause: my CWD and worktree path confusion.
5. **Feature-scaffold was 38KB** — nearly 2x the hard cap. Required significant trimming in PR-1.4. Lesson: size limits should be enforced in CI.
6. **koin-di deprecation delayed** — flagged in PR-0.3 retro but not done until PR-1.4. Fast-follow should have been immediate.

### 🔴 Critical fixes (applied during epic)

1. **UserMessage.kt restored** (PR-1.1)
2. **TagGroupsUiState.Empty("") fixed** (PR-1.1)
3. **stateIn removed from AndroidPomodoroTaskListProvider** (PR-0.4)
4. **runBlocking → koinBridge in PlatformModule.jvm.kt** (PR-0.4)

### 🟡 ADR-worthy findings

1. **DIGEST size** — 1748/1500 lines. Options:
   - Archive old applied ADRs (target: reduce to ~1400)
   - Split DIGEST by category
   - Increase limit to 2000
   - **Recommended**: archive old ADRs in follow-up epic

2. **Skill size check not in CI** — `check-skill-frontmatter.sh` validates frontmatter but not size. Add `wc -c` size check to CI.

3. **Worktree build verification** — before each PR, verify `jvmTest` passes in worktree to catch pre-existing build errors.

4. **ADR frontmatter normalization in CI** — `just docs-audit` should run `normalize-adr-frontmatter.sh --apply` (not dry-run).

5. **PR template** — should include checkbox for "ADR needed?" and "Skills updated?" to prevent missing them.

## Lessons Learned

1. **Retro discipline** — per-PR retros caught issues early (DIGEST size, koin-di delay). Continue this practice.
2. **Skill size limits need CI enforcement** — manual review misses oversized skills. Add `wc -c` to CI.
3. **Worktree CI/CD** — Android SDK unavailable in worktree; quality gates run as `jvmTest + detekt` only. Consider `local.properties` symlink or CI task that skips Android-specific checks.
4. **ADR count management** — 30+ ADRs in this epic alone. Consider ADR templates that produce fewer Critical bullets (more concise bullets).

## Epic Stats

| Metric | Count |
|---|---|
| Total PRs | 11 (P0: 4, P1: 4, P2: 3) |
| New ADRs | ~30 |
| ADRs superseded/closed | 5 |
| ADRs deferred | ~15 |
| New skills | 8 (domain-glossary, progress-journal, code-review-pr-workflow, debugging-investigation, security-review, ux-a11y-review, singularity-todo-workflow-evals, feature-ai-registration) |
| Skills trimmed | 3 (vm-migration-playbook, feature-scaffold, koin-di stub) |
| Skills deprecated | 1 (koin-di) |
| DIGEST line growth | 1651 → 1748 (+97) |
| Pre-existing bugs fixed | 2 |

## Consequences (new DIGEST rules)

- Skill size check (wc -c) to be added to CI
- `just docs-audit` to run `normalize-adr-frontmatter.sh --apply` in CI
- Worktree: verify `jvmTest` passes before starting each PR
