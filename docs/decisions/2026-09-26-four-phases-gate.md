---
status: accepted
date: 2026-09-26
---

# Four Phases Gate

## Context

Each PR goes through four phases. Reviewers gate progress at each phase. This prevents mixed-quality PRs (e.g., "please review — still writing tests").

## Decision

### The four phases

| Phase | Gate | Reviewer action |
|---|---|---|
| **1. Draft** | PR is `draft` | Reviewer: read only, no blocking feedback |
| **2. Ready** | All CI green + self-reviewed | Reviewer: full review, blocks if not ready |
| **3. Approved** | All comments resolved | Reviewer: approves, author merges |
| **4. Merged** | CI green on main | Done |

### Phase transitions

**Phase 1 → Phase 2 (Ready):**
- Author removes `draft` label / marks PR ready
- CI (jvmTest, detekt) is green
- Author has done a self-review pass

**Phase 2 → Phase 3 (Approved):**
- All review comments resolved
- All required checks pass
- Reviewer approves

**Phase 3 → Phase 4 (Merged):**
- Author squash-merges to main
- CI is green on main branch

### What reviewers must check at each phase

**Phase 1 (Draft):**
- High-level architecture — does the approach make sense?
- Are there red flags in the design?

**Phase 2 (Ready):**
- All required checks from `code-review-process`
- All review comments addressed or acknowledged
- No new issues introduced by changes

**Phase 3 (Approved):**
- Final sanity check
- Author has merged or will merge promptly

### Anti-patterns

- **"Please review, tests still failing"** — Phase 1 is not ready for Phase 2
- **"LGTM, merge when ready"** — approval should only happen at Phase 3
- **Reviewing a draft PR** — reviewer should not block on drafts, only read and comment
- **Approving without CI green** — never approve if CI is failing

## Consequences

- PRs with failing CI should be marked `draft` or closed
- Reviewers are not required to review Phase 1 PRs — only Phase 2+
- The `writer-reviewer-pattern` governs who is responsible for each phase transition
