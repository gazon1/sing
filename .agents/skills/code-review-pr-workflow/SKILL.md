---
name: code-review-pr-workflow
description: Author and review a pull request in this project. Use when opening a PR, deciding draft vs ready, naming a branch, writing a PR body, self-reviewing a local diff before pushing, or reviewing someone else's PR.
---

# Code Review PR Workflow

Phases 0–3: Phase 0 is the author's side (before the PR exists), Phases 1–3 are the
reviewer's side. Both are here because a reviewer's questions ("why no test?", "what
changed?") are usually the author's unanswered checklist.

## When to use

- Opening a PR, or deciding draft vs. ready
- Self-reviewing a local diff before pushing
- Reviewing someone else's PR
- Checking whether a PR is ready to merge

## Prerequisites

- PR is open and assigned to you as reviewer (Phases 1–3)
- You have write access and a clean working tree (Phase 0)

---

## Phase 0 — Author pre-flight (before the PR exists)

### Branch naming

```
<type>/<scope>-<slug>        e.g. refactor/shared-ui-components-split
```

Types in use here: `feat`, `fix`, `refactor`, `docs`, `chore`, `build`, `test`.
Scope is the feature or subsystem (`tasks`, `sync`, `di`, `skills`, `detekt`).

For anything wider than one file or one subsystem, work in a dedicated worktree
(`singularity-todo-worktree-isolation`) so the main checkout stays usable.

### The gate — run all of it, in this order

```bash
./check.sh            # jvmTest → desktopApp:test → assembleDebug → detekt (enforcing)
just tcheck-evals     # agent workflow evals
just docs-audit       # frontmatter + doc sizes + dead refs + DIGEST
```

All three must be green. `just docs-audit` is the one most often skipped and the one that
catches the errors that actually reach a reviewer: a dead file reference, an ADR whose
frontmatter drifted, a skill that outgrew its size budget.

### PR body

```markdown
## What
One or two sentences. The problem, not the diff.

## Why
Why this approach over the obvious alternative. Link the ADR if there is one.

## How
The shape of the change, not a file listing. Note anything a reviewer cannot infer
from the diff (a refactor that touches X for consistency, a generated file, a
deliberately deferred item).

## Verification
- [ ] ./check.sh
- [ ] just tcheck-evals
- [ ] just docs-audit
- [ ] manual/UI verification where the change is visual

## Risk
What could break, and how you would notice. "None — docs only" is a valid answer.
```

### The review verdict

After the reviewer agent has answered, its verdict is written down twice, and
the two are different things.

The **review itself** is a comment on the pull request — see "The review is a
comment on the pull request" below. The comment is what a person reads and
what the `Agent review` job reads.

The **verdict of a change** is a file — not a memory of the session that asked
for it. A change that is not in
`config/docs/review-verdicts-baseline.txt` carries one at
`.specify/reviews/<slug>.md` (the slug without the date prefix):

```markdown
---
change: <the slug>
verdict: pass | fail
date: YYYY-MM-DD
pr: <the pull request url>
reviewed: <the commit the review was made against>
documents: sha256:<the digest of the change's contract documents>
---

## What was reviewed
...
```

The digest is over the change's proposal and its spec deltas — the contract,
not the checklist. The gate computes it:

```bash
python3 scripts/check-review-verdicts.py --digest <slug>
```

Write the documents **before** the review, and when a contract moves afterwards,
**re-issue** the verdict rather than re-reading it: the change that edits the
contract re-issues the verdict with the new digest, in the same diff, where a
reviewer sees the move and the re-issue together. The gate reports the drift —
a verdict that carries the digest of a contract that is not there any more is
a finding — and whether the re-issue was earned is the reviewer's question,
because a re-issue is a claim in a diff like any other.

The two verdicts are allowed to disagree, and neither one is checked against
the other: the file's `fail` says the review found something, and the
comment's `fail` keeps the pull request red until a later review says `pass`.
One of them is a record, the other is a gate.

### Draft vs. ready

Push as **draft** when any of these hold; they are the same list a reviewer will check:

- the gate has not been run end to end
- a test for the new behaviour does not exist yet
- you know of a follow-up that belongs in the same PR
- you are not able to say what could break

Drop the draft prefix when the gate is green and you can answer "what could break".

### Self-review before requesting review

Read your own diff as if you had not written it:

```bash
git diff <base>...HEAD --stat
git diff <base>...HEAD
```

Check specifically for: a commented-out block, a debug `println`/`Log.d`, a TODO you
introduced, a KDoc `@see` pointing at a file that moved, and any test asserting a
literal instead of the behaviour.

An agent reviewing its own diff inherits the assumptions the diff was built on. For a
change over a few hundred lines, dispatch a fresh sub-agent with only the diff and the
ADR — it sees what you cannot unsee. Give it the diff, not the session history.

### The review is a comment on the pull request

The review is written down **on the pull request**, as a comment that **opens** with the
marker `<!-- agent-review -->` and states a verdict:

```
<!-- agent-review -->
## Agent review

**Verdict:** pass
```

`pass` says the review found nothing that blocks the change; `fail` says it found
something. A `fail` keeps the pull request red: the findings have to be dealt with, and
a later review has to say `pass` again. A review that finds something and a pull request
that merges anyway are two different things, and only one of them is right.

The comment has to *open* with the marker, and the verdict has to be the whole value of
its line: `**Verdict:** pass or fail` is a sentence about verdicts, and the check
reads no verdict in it. The marker and the format are written down in three places, so
a comment that quotes them is not a review — and the check is built to say so.

This is not a formality. The job `Agent review` (`scripts/ci/check-agent-review.py`)
asks the pull request for that comment, and the `CI gate` waits for that job: a pull
request without the review does not go green. A review that happened in a session and
left nothing behind is a review the next reader cannot see.

**The gate is counted again when the review lands.** The check runs on the pull
request, so the run that opened it finds no review and is red. After the review is
posted, re-run the workflow: apply the `agent-reviewed` label (a pull-request-scoped
trigger), or push. A run triggered by a *comment* would not help — GitHub counts the
checks of such a run on the default branch, so the green would land on main and not on
the pull request.

The reviewer and the author run under the same account, so "separate" is a fact about
the session — a fresh agent that only saw the diff — and not about the login.

---

## Phase 1–3 — Reviewer side

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
