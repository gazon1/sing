---
name: code-review-pr-workflow
description: Conduct a code review following the four-phases gate process. Use when reviewing or authoring a PR.
---

# Code Review PR Workflow

## When to use

- When assigned as a reviewer on a PR
- When opening a PR and self-reviewing before requesting review
- When checking if a PR is ready to merge

## Prerequisites

- PR is open and assigned to you as reviewer
- You have read access to the repo
- CI is running or has run

## Step-by-step

### Step 1 — Identify the phase

Check the PR title/labels for phase:

```
Draft PR  → Phase 1 (Draft)
CI green + "Ready for review" → Phase 2 (Ready)
All comments resolved → Phase 3 (Approved)
```

### Step 2 — Phase 1: Read and advise (do not block)

If the PR is in Draft phase:
1. Read the PR description and diff
2. Provide high-level feedback only
3. Do NOT block on style, naming, or minor issues
4. Flag architectural concerns

**Example comment:** "This approach makes sense for the short term, but have you considered X for the future? No blocking action needed now."

### Step 3 — Phase 2: Full review

If the PR is marked Ready:
1. Run `just tcheck` locally on the branch (or trust CI)
2. Read every changed file
3. Check:
   - [ ] Correctness: logic is sound
   - [ ] Tests: new logic covered
   - [ ] Architecture: layer boundaries respected
   - [ ] ADR: if architectural change, ADR is present
   - [ ] No secrets: no keys/tokens in diff
4. Block if any check fails — be specific

**Example blocking comment:** "This will cause a regression in ProfileRepository — the old code returns `Result.Left` but this returns `null`. Please add a test case for the error path."

### Step 4 — Phase 3: Approve or confirm

Once all comments are resolved:
1. Re-read the diff — verify the resolution is correct
2. If satisfied: approve
3. If not satisfied: leave a comment explaining why
4. Never merge someone else's PR — only the author merges

### Step 5 — Post-merge

If you merged the PR (author role):
1. Verify CI is green on main
2. Update PROGRESS.md if this is part of an epic
3. File retro ADR if significant findings during review

## Decision tree: what to block on

```
REVIEWER SEES PR
    │
    ├─── Is PR draft?
    │         YES → Read only, provide high-level feedback, DO NOT BLOCK
    │         NO  → Continue
    │
    ├─── Is CI failing?
    │         YES → Block: "CI must be green before review"
    │         NO  → Continue
    │
    ├─── Is there new logic without tests?
    │         YES → Block: "Tests required for new logic"
    │         NO  → Continue
    │
    ├─── Is there an architectural change without ADR?
    │         YES → Block: "ADR required for architectural changes"
    │         NO  → Continue
    │
    ├─── Are layer boundaries violated?
    │         YES → Block: "Architecture violation: X in Y layer"
    │         NO  → Continue
    │
    └─── All checks pass → APPROVE
```

## Common pitfalls

1. **Blocking on drafts** — drafts are for exploration, not final review. Only read and advise.
2. **Approving with failing CI** — never approve if CI is red, even if the failures seem unrelated.
3. **Missing ADR review** — an ADR reviewer focuses on the decision quality, not just the code. If you review an ADR, use the `writer-reviewer-pattern` ADR.
4. **Style-only comments** — minor naming/style issues are nice-to-have. Mark as "nit:" so author knows it's non-blocking.
5. **Rubber-stamping** — if you approve, you take equal responsibility for the code. Read the diff.
