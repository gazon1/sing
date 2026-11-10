---
date: 2026-11-09
status: accepted
deciders: [maintainer]
---

# The pre-push hook judges the push, not the repository's inherited state

## Context

The pre-push hook runs gates that read the **whole tree**: the UI wait
budgets, the review verdicts, the formatting, the gate suite's own tests, the
detekt verdict. That is deliberate — the fast lane runs the gates that read
the whole tree (`2026-10-28-the-fast-lane-runs-the-gates-that-read-the-whole-tree.md`),
because a gate that reads less than it claims is a lie (`2026-10-20-a-gate-that-reads-less-than-it-claims.md`).

The consequence was measured on 2026-11-09, while preparing PR #2177:

| Gate | Result on the branch's tree | Result on a clean `origin/main` (`aaee487e6`) |
|---|---|---|
| `check-ui-wait-budgets.py` | 4 mismatches | the same 4 |
| `check-review-verdicts.py` | 3 moved digests + 4 stale baseline entries | the same |
| `ktlint-lint.py` | 247 findings / 74 files | the same |
| gate suite | 52 failures / 3280 tests | the same 52, the same test ids |
| `:shared:detekt` | 6 findings | the same 6 |

Every one of them fails identically on the trunk. The trunk's own CI is red
for the same reasons. So the hook was not failing on anything the branch
brought: it was failing on the repository's debt, on every push, forever — and
the only ways through it were `--no-verify` (which is not a fix) or fixing the
trunk's debt inside an unrelated branch (which is not this branch's business,
and would not have made the trunk green either).

The table is the state before this branch's own repairs. The branch carries
one of the files the table counts: `SyncDaoGuardContractTest.kt`, which the
trunk's own `fix(tests): restore the imports the jvmTest sources need` left
with `kotlin.test.AfterTest` imported twice — an ambiguous import, and a file
that no longer compiles. A file the push carries is a file the push owns, so
the branch fixes that file: the 31 formatting findings, the swallowed
cancellation, and the import. The branch's tree therefore carries 31 fewer
findings than the trunk's, and the trunk's copy of the file is the trunk's
debt.

Two smaller defects sat inside the same failure:

- **A merge push carried no files.** The set of files a push touches was
  computed with `git log --name-only`, and git prints no files for a merge
  commit. Every "if the push touched this" gate was therefore skipped on a
  push that merges the trunk — the pushes where a wrong file is most likely.
- **Two tools were resolved by the machine, not by the tree.** `easement` and
  the `heavy` wrapper are installed through mise, and mise resolves a shim
  from the tree's own `mise.toml`. A tree without one — the hook's own test
  fixture, for one — has the shim on `PATH` and cannot run it, so the same
  tree passed on one machine and failed on another. Sixteen of the hook's own
  tests failed for that reason alone.

## Idea

A gate that reads the whole tree should still say something about **the
push**. The two claims are not the same claim, and the hook was making the
second one while its output said the first.

The repository's findings are the repository's CI run to report — that is
where a trunk-wide finding belongs, in the run that judges the trunk. The
hook's business is the push: *does this push make a gate redder than the base
it starts from?*

The precedent is in the repository already: `scripts/lint-changed.py` and
`scripts/check-baseline-ratchet.py` both measure a change against a base
rather than against an absolute, and a baseline is a **ratchet** — it may
shrink, never grow (`2026-10-01-ci-quality-ratchet.md`,
`2026-10-20-the-instrumented-floor-is-a-ratchet.md`).

## Decision

**A push must not make a gate redder than the base it starts from.**

- **The base is the commit the branch forked from** — the merge-base of the
  trunk and the pushed commit. `SGATE_BASE_REF` names it when the caller
  knows better (a release line, a fork); `refs/remotes/<remote>/main`,
  `origin/main` and `main` are the fallbacks. A push with no base at all is
  judged as if everything in the tree were new — the safe direction.
- **The files a push carries** are the files that differ between the remote's
  tip and the pushed commit (`git diff --name-only`), not the files a commit
  touched (`git log --name-only`). The diff is what the push *delivers*; the
  log is empty for a merge. The diff is read with `core.quotePath=false`:
  the default escapes a path with non-ASCII letters (`f/Caf\303\251.kt`), and
  the escaped form matches no pattern a gate uses — the file is in the push and
  no gate sees it. When there is no tree to compare with, the push is judged as
  carrying the whole history of the pushed commit: a gate that judges too much
  only costs time, while a gate that judges too little lets a defect through.
- **A gate that fails is run again in the base tree**, in a throwaway
  worktree, and the two answers are compared. What the base does not have is
  the push's own and stops the push; what the base has as well is the
  trunk's, and the hook says `PRE-EXISTING` and lets the push go.
- **Each tree is judged by its own copy of the gate.** The base tree runs the
  gate it carries, the pushed tree runs the gate it carries: each tree is
  judged by the rules it was written under, and the claim stays "this push
  adds no findings".
- **The gate suite is compared by its failing test ids**, taken from the
  JUnit report (never from the console: the console of a suite run carries
  the output of the runs the suite itself makes). Each failing id is re-run in
  the base tree; an id that fails there as well is pre-existing.
- **The detekt verdict is compared by ownership.** A finding in a file the
  push carries is the push's own; a finding in a file it does not touch is the
  repository's. A change in the rules, the custom rules, the build or the
  editorconfig makes every finding count, because it can change what every
  file looks like to detekt. The verdict's base is the trunk merge-base, not
  the branch's upstream: a merge push used to be measured against the whole
  trunk it merged, and every finding in the repository was the merge's own.
- **A tree's own declaration decides whether it has a tool.** `easement` runs
  only when the pushed tree's `mise.toml` declares it; the `heavy` wrapper is
  used only when it actually runs. A tree that does not have the tool is not
  blocked by it, and the outcome does not depend on the machine.
- **No base, no ratchet.** When the comparison cannot be made, the finding
  counts as the push's own. "Cannot be made" covers both cases: there is no
  base at all, and there is a base the diff cannot be computed against —
  unrelated histories, a shallow clone. In both cases there is no way to tell
  the push's findings from the repository's, and a finding that cannot be
  attributed cannot be called somebody else's. The verdict counts every
  finding as the push's own, which is the direction that stops the push. The
  opposite direction — every finding counted as inherited, so the push is
  green — is the failure mode this clause exists to prevent.
  The ratchet is a way of not blocking on somebody
  else's debt, not a way of skipping a gate.
- **A run that failed without reporting anything said nothing.** The exit code
  of each run is part of what is compared, not only the text it printed. A
  gate that exits nonzero and prints nothing broke — it did not find
  something — and a broken run cannot say what the other tree has. Two broken
  runs used to compare as equal (both outputs empty), so the push was accepted
  on the strength of a gate that validated neither tree. Now such a comparison
  cannot be made, and it stops the push.

The rule is recorded in `config/hooks/pre-push.json` (section `ratchet`), the
comparison is done by `scripts/pre-push-ratchet.py` — a tool, not a gate: it
judges nothing about the tree, it only compares two answers — and
`scripts/check-hook-gates.py` fails if the hook stops naming it.

## Rationale

- **It is the claim the hook was already making.** The hook's own output
  said "the gates that read the whole tree". A gate that reads the whole tree
  and stops the push for what the trunk already carried was not that; it was
  a gate that blocks every push forever.
- **It does not weaken the gate.** The gate still reads the whole tree, on
  both sides. The ratchet decides whether a finding *stops the push*; it
  never decides whether the finding is there. CI still judges the whole tree,
  and the trunk's findings are the trunk's CI run to report.
- **It is the repository's own precedent.** `lint-changed.py` and
  `check-baseline-ratchet.py` already measure against a base. The hook was
  the last place that judged a change against an absolute.
- **The comparison is coarse about position and exact about content.** A
  finding's position moves when the file above it grows; the file, the rule
  and the message are what identify it. Normalising the position away is what
  makes "the same finding in two trees" one finding instead of two.
- **Machine-dependent answers are answers about the machine.** `easement` and
  `heavy` were resolved by "is it on `PATH`", which is not the same question
  as "can this tree run it", and the two came apart on the hook's own
  fixtures.

## Consequences

- **A push of a branch whose trunk is red can go through**, and the trunk's
  findings are still reported — by the trunk's CI run, which is where they
  belong.
- **A push that brings a new finding is still stopped**, and the hook says
  what the push adds: the difference between the two answers, not the whole
  output of the gate.
- **The hook costs one base worktree and one extra run of each failing
  gate.** The base worktree is a throwaway checkout of the base commit; the
  extra run is seconds for the structural gates and the failing test ids for
  the suite.
- **The trunk's debt is still debt.** Nothing here fixes it, and nothing here
  hides it: `scripts/check-baseline-ratchet.py` still holds the baselines
  honest, and CI still judges the whole tree.
- **The detekt verdict is now partial in a second way**: it no longer reports
  the repository's findings as the push's own. The hook says how many
  findings are in files the change does not touch, so the partiality is
  visible rather than implied.
- **A file the push touches is a file the push owns.** A finding in it is the
  push's, and the push has to fix it — even when the finding was there
  before. That is the price of touching a file, and it is the same price CI
  charges.
- **A gate that breaks blocks the push.** A run that failed without reporting
  anything is neither a finding nor a pass, and the ratchet does not read it
  as either: the comparison cannot be made, so the push stops and the hook
  says that the comparison could not be made.

## Links

- `scripts/pre-push-ratchet.py` — the comparison (outputs, and test ids)
- `.githooks/pre-push` — the hook: the base, the base tree, the ratchet
- `scripts/pre-push-verdict.py` — the detekt verdict and the ownership rule
- `config/hooks/pre-push.json` — the record of the contract
- `scripts/tests/test_pre_push_ratchet.py` — the tool's own tests
- `scripts/tests/test_pre_push_hook.py` — the hook's own tests, through a push
- `docs/decisions/2026-10-28-the-fast-lane-runs-the-gates-that-read-the-whole-tree.md`
- `docs/decisions/2026-10-20-a-gate-that-reads-less-than-it-claims.md`
- `docs/decisions/2026-10-06-a-gate-that-lies-is-worse-than-no-gate.md`
- `docs/decisions/2026-10-23-the-local-verdict-judges-what-the-change-reaches.md`
- `docs/decisions/2026-10-25-the-hook-does-not-name-a-gate.md`
