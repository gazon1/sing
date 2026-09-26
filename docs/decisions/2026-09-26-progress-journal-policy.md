---
status: accepted
date: 2026-09-26
---

# Progress Journal Policy

## Context

Epics and large features span multiple PRs over days or weeks. Without a shared progress tracker, it is hard to see where the work stands, what was already done, and what remains.

## Decision

### PROGRESS.md

A `PROGRESS.md` file at the repo root tracks active epics. It is a **living document** — updated after each PR merge, never reset to "not started".

### Format

```markdown
# Progress Journal

## Epic: <epic name>

**Start date:** YYYY-MM-DD
**Status:** in-progress | completed | paused
**Epic branch:** `docs-and-skills-hygiene` (git worktree at `~/worktrees/<epic-name>/`)

### Phase 1 — <phase name> (PRs 1-3)

| PR | Description | Status | Merged |
|---|---|---|---|
| PR-1 | Feature X | ✅ merged | 2026-09-20 |
| PR-2 | Feature Y | 🔄 in review | — |
| PR-3 | Feature Z | ⏳ not started | — |

### Retro findings

#### PR-1 retro (2026-09-19)
- ...

#### PR-2 retro (2026-09-21)
- ...

### Blockers / Deferred to ADR

- R14: `feature-x-cleanup` deferred to follow-up epic _(filed: `docs/decisions/2026-09-21-epic-X-retro.md`)_
```

### Update frequency

| Event | Who | What |
|---|---|---|
| PR opened | Author | Add PR row with "🔄 in review" |
| PR merged | Author or reviewer | Update status to "✅ merged" + date |
| Post-PR retro | Author | Add `#### PR-N retro` section |
| Epic completed | Epic owner | Change status to `completed` |

### Retro section content

Each retro entry answers:
1. **What was done** — brief summary
2. **What went well** — bullets
3. **What didn't go well** — bullets
4. **Critical fixes** — any bugs/blockers fixed immediately after the PR
5. **ADR-worthy findings** — items logged to ADR instead of fixed

### PROGRESS.md is not a TODO list

- Items are never deleted from PROGRESS.md — only marked completed or deferred
- A PR's retro section is append-only after the PR is merged
- "⏳ not started" items can be reordered without changing history

## Consequences

- `just docs-audit` validates PROGRESS.md exists if `docs/decisions/` contains retro ADRs from the last 30 days
- Retro ADRs reference their PROGRESS.md entry (bidirectional link)
- Completed epics keep their PROGRESS.md entry as an audit trail
