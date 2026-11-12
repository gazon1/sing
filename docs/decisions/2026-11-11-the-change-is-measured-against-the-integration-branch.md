---
title: The change is measured against the integration branch
date: 2026-11-11
status: accepted
deciders: [maintainer]
---

# The change is measured against the integration branch

## Context

The repository is about to grow a protected integration branch, `dev`, beside
its trunk (`docs/decisions` are not the only record — the issue is
[#2832](https://github.com/gazon1/sing/issues/2832)). Every tool that answers
"what does this change touch?" needs a base: the ref the change is compared
with. In this repository that answer was written down **fourteen times**, and
the fourteen copies drifted:

- `origin/main` in some of them, `main` in others;
- `refs/remotes/origin/main` in a third;
- a candidate list in a fourth, written out by hand.

A change measured against `origin/main` in one tool and against `main` in
another is measured against two different things — the remote's copy of the
trunk and the local one are different commits the moment the trunk moves. The
same change, judged by two gates, could select different work.

The moment a second branch exists, the drift becomes a bug rather than an
inconsistency: a tool that compares against `origin/main` measures a `dev`
change against the wrong trunk, and the wrong answer is a green run.

One tool must answer "which ref is the base?", and every tool must ask it
the same way.

The exclusive rule below has a consequence nobody planned for: a name that
refers to a ref in *this* repository means nothing in another one. The first
CI run of this change found it — the workflow names the base once, for the
whole workflow, and the name reached a control's fixture repository. The
ratchet measured the fixture's branch against a ref the fixture does not have,
found no growth, and passed a deliberately sabotaged input. The control that
was there to prove the gate can fail proved instead that it cannot, and only
in CI: on a workstation the same control passed, because the name was not
there. A base that is not there is not a base, and it is not a missing base
either — it is a tree that has been told it has none.

## Idea

One module owns the answer. Every tool asks it. The answer is a chain, and
the chain names the integration branch before the trunk.

## Decision

`scripts/src/sgate/gatekit/refs.py` owns the answer.

**Two chains, and the difference matters.** `REMOTE_BASE_CANDIDATES` holds
the **remote-tracking** copies only (`origin/dev`, then `origin/main`).
`BASE_CANDIDATES` holds the remote copies and the local branches in **role
order** — the integration branch first (`origin/dev`, then `dev`), the trunk
after it (`origin/main`, then `main`) — the short names, because git resolves
`origin/dev` to the remote-tracking ref and `dev` to the local branch, so the
fully qualified spellings would never be reached.

A tool that asks *"what is this checkout carrying, compared with the trunk it
is based on?"* uses the remote chain: a local branch is not an upstream, and
a checkout measured against its own branch answers nothing. That is
`sgate checkout-state` and `scripts/pre-push-verdict.py`.

A tool that asks *"what does this change touch?"* uses the general chain: a
clone with no remote at all still has a branch, and a change measured against
nothing is measured against everything. That is `scripts/git_changed.py`,
`scripts/lint-changed.py`, `scripts/compile-changed.py`,
`scripts/changed-modules.py`, `scripts/test-impact-map.py`,
`scripts/check-baseline-ratchet.py` and `sgate.control_scope`.

**The role comes first and the freshness second.** `dev` comes before `main`
whatever else is true, because the trunk a change is based on is the
integration branch while it exists and the release branch after it. Within one
role the remote's copy comes before the local branch: the remote's copy is the
branch as the world sees it, and the local branch is the branch as this
checkout last had it.

The two rules can disagree — a checkout with `origin/main` and a local `dev`
has both, and only one of them can be the answer — and the **role wins**. The
remote's copy of `main` is not a better integration branch than the local
`dev`; it is a better *copy of the trunk*. A change based on the integration
branch is measured against the integration branch.

**A change is never measured against itself.** A push leaves the
remote-tracking copy of the pushed branch on the commit that was just pushed.
A push to `dev` therefore leaves `origin/dev` on `HEAD`, and a chain that
answers "the integration branch" measures that push against itself: the diff
is empty, every gate that selects work by the changed files finds nothing to
do, and the run is green because it measured nothing.

So there are two resolvers, and they answer two questions. `resolve_base_ref()`
answers *"which ref is this checkout's trunk?"* and takes the chain as it
stands. `resolve_change_base_ref()` answers *"which ref is this change
measured against?"* and steps over a candidate that points at `HEAD`: the
change resolver is what the change-measuring tools use. When every candidate
points at `HEAD` there is nothing else to measure against, and the only answer
that can say so honestly is the trunk — see *"A base that is the change
itself"* below.

**A named base is exclusive, and is taken as given.** `SGATE_BASE_REF` names the
base for callers that know better. The caller named it, and the caller knows
whether it is the change itself: a name is an answer, not a guess. When it
names a ref that is **not here**, there is **no base** — the chain is not
consulted. Answering with a different ref would be answering a question nobody
asked, and a tool that measures against a base the caller did not name
measures against the wrong thing without saying so. The tools therefore carry
**no second answer**: no tool falls back to `origin/main` when the owner has
none, because that fallback is either dead (the owner already tried
`origin/main`) or wrong (it answers a question the caller did not ask). A tool
with no base says so.

**A named base travels with the tree it names.** `SGATE_BASE_REF` names a ref
in the tree the caller is in, so it is true here and says nothing about
another repository. A process that runs in another tree — a fixture's own
repository, a control's copy of this one — does not carry the name: the name
is not there, and because a named base is exclusive, the tree that has no base
of its own would be told it has none, and the chain that could have answered
would never be consulted. `scripts/src/sgate/gatekit/gitenv.py` owns the
answer to "which names must not cross into another tree" — git's hook
variables, this one, and the object-store names that point at another tree's
objects as plainly as a base ref points at another tree's commits — and the
control harness, the sandbox and the test fixtures all take their environment
from that owner instead of spelling the names out a second time.

**The ratchet fails when it cannot measure.** `scripts/check-baseline-ratchet.py`
answers "is the baseline still true?" — and the answer is about a *comparison*,
not about a file. It asks the same owner for its base as every other
change-measuring tool: a named base is exclusive, and otherwise the chain
answers. But there is one thing a ratchet may not do, and it is what a warning
amounts to: report success for a measurement it did not make.

So there are three outcomes, and the third one is a failure:

1. a base resolved — the comparison is made;
2. no base resolved and the caller permits an unbased check — the check runs
   against everything, and says so;
3. a named base that is not here, a named base that is git's null commit (the
   push created the branch), no base at all, or no merge-base between the
   base and `HEAD` — the ratchet cannot measure, and it **exits non-zero**.

`HEAD` is not one of the answers. Measuring the baseline against `HEAD` is
measuring the file against itself: the diff is empty, the baseline cannot
grow unnoticed, and the tool reports the success of a comparison that never
happened. A base whose merge-base *is* `HEAD` is a different fact and is not
a failure: `HEAD` carries no commits of its own, so the change is empty and
there is nothing to measure — that is an honest zero, not an impossible
measurement.

**No base is `None`, and `None` is never `HEAD`.** `sgate checkout-state`
keeps its three-valued answer: `git diff --quiet` exits 1 when the files
differ, 0 when they are equal, and 128 when the ref is not there. Those are
three facts, and the third one is not the second one. A checkout whose base
is missing reports `upstreamKnown: false` and `instructions.differs: null`,
and the report says that the comparison is *unknown* rather than *equal*.

**The resolver looks the way git looks.** A ref is resolved with
`git rev-parse --verify --quiet <ref>^{commit}` — not `for-each-ref`, not
`show-ref`. Those two do not see a ref written by
`git update-ref origin/main HEAD`, which lands in `.git/origin/main`; the
test fixtures build their upstream that way. A resolver that used the wrong
command would answer "no base" about a repository that has one.

**Hook-side code reaches the answer by path.** `scripts/refs_loader.py`
loads the module the way `scripts/gitenv_loader.py` already loads
`gitenv.py`: the `sgate` package imports `click` and `loguru`, and the
pre-push hook runs where neither is guaranteed. The same file carries a
command line, so a recipe outside the package can ask the owner for a base
instead of naming one of its own.

**CI names the base once for the whole workflow, and it names it per event.**
The workflow sets `SGATE_BASE_REF` in one place, and every job that reads the
base reads the same name. What the name *is* depends on what happened:

| Event | Named base | Why |
|---|---|---|
| Pull request against `main` | `origin/main` | the branch the pull request targets |
| Pull request against `dev` | `origin/dev` | the branch the pull request targets |
| Push to `main` | the commit the push replaced (`github.event.before`) | after the push, `origin/main` **is** the pushed commit — naming it would measure the change against itself |
| Push to `main` that **created** the branch | git's null commit (`0000…0`) | there is no state the push replaced, so there is nothing to measure against |
| Push to any other branch (e.g. `dev`) | `origin/main` | the change is measured against the trunk it was not pushed to |
| Schedule | `origin/main` | there is no event to name a base |
| Manual run | `origin/main` | there is no event to name a base |

The event is named explicitly rather than left to the chain's fallback order:
the chain cannot know whether `origin/dev` is the branch the change was just
pushed to or the branch it was based on, and the difference is the difference
between a real comparison and no comparison at all.

**The mapping is policy, and it is written down here.** The expression in the
workflow is a policy engine: it says what each event measures against, and a
policy that exists only as an expression is a policy nobody audits. The table
above is the mapping, and it is pinned by
`scripts/tests/test_ci_pr_path.py` — every row of it is a case there, and
every case there checks **both** halves: the name the expression produces, and
what that name measures against when the checkout does not have it. A name
that is not here is no base, and the gate fails rather than measuring against
something else.

**A push that created the branch names the null commit, and that fails closed.**
The all-zeros `before` of a branch's first push is not a base and is not
"the trunk is the answer" either: after such a push `origin/main` **is** the
pushed commit, so naming the trunk would measure the commit against itself and
report a vacuous green. The name is therefore the null commit — the payload's
own answer to "the ref did not exist before this push" — and the null commit
resolves to no commit at all, so the comparison cannot be made and the ratchet
exits non-zero. That is the third outcome stated as itself: *the measurement
could not be performed*, never the second one dressed up as itself. The rule
lives in the resolver (`is_null_commit()`), not only in the ratchet: git accepts
the null id as a *name* and only fails when it is asked to peel it, so the
intent must not depend on how git was invoked.

Every job still reads the same name: the name is set once, and the event
decides what it says.

## Rationale

The drift was not a style problem. It was fourteen answers to one question,
and the tools that answered it differently selected different work — which is
the kind of defect that shows up as "the gate passed and the build broke",
never as "the gate disagreed with the other gate".

The two chains look like two answers to one question, and the temptation is to
keep one chain and delete the other. They cannot be merged. The remote chain
answers *what upstream is this checkout based on*, and the local branch of a
checkout that has never fetched is that checkout's own branch — measuring a
checkout against itself reports that it carries nothing, which is exactly the
false green `sgate checkout-state` exists to prevent. The general chain
answers *what does this change touch*, and a clone with no remote has a
branch; measuring it against nothing selects everything, which is safe and
useless. One chain for both would have to pick, and either choice breaks one
of the two questions.

The exclusive rule is the part that looks like an inconvenience. A fallback
would be friendlier: `SGATE_BASE_REF=origin/dev` in a repository that has no
`dev` yet would measure against `origin/main` and keep working. But it would
also answer silently about a different ref than the one the caller named, and
the caller is the only one who knows which comparison was wanted. In CI the
named base is the branch the pull request targets. The jobs that compare
against it — the gate suite and the changed-modules map — check the full
history out, so the name is there; a job with a shallow checkout does not have
it, and the tools there say so — they measure everything — instead of
answering about a ref that is not in the tree. In a clone without the branch
it costs the same clear "no base".

`None` is not `HEAD` for the same reason. `git diff --quiet` against a
missing ref exits 128, and the repository already had a rule about exit
codes: 1 is a difference, 0 is equality, and anything else is a fact about
the measurement rather than about the tree. Collapsing 128 into 0 would
report "the instructions do not differ from the base" about a base that does
not exist — a green answer to a question nobody asked.

## Consequences

- `sgate checkout-state` resolves its base **once** and threads it through
  all seven of its call sites. A base that changed between two questions
  would be two bases, and the report would answer about a checkout that
  never existed.
- The pre-push hook carries the same chain in shell
  (`refs/remotes/<remote>/dev`, `dev`, then `refs/remotes/<remote>/main`,
  `main`), because the hook cannot import the package. The shell chain is in
  the same **role order** as the owner: the integration branch first, the
  trunk after it, and the remote's copy before the local branch within one
  role.
- `scripts/git_changed.py`'s default changed from the literal `"origin/main"`
  to `None`, resolved at call time: a default evaluated at import time would
  run git at import.
- The tools that ask "what does this change touch?" no longer carry a base of
  their own. `LINT_BASE` and `COMPILE_CHANGED_BASE` are gone, and so is the
  `origin/main` tail: `SGATE_BASE_REF` and `--base` are the two ways to name a
  base, and the owner is the third answer that costs no configuration.
- The `rumdl-changed` recipes ask the owner for their default through
  `scripts/refs_loader.py`, and keep `RUMDL_BASE` for the case where the
  caller knows better. There is no second default left to drift: the recipe
  names a base only when a person named it.
- A tool that asks "what does this change touch?" and finds no base measures
  against **everything** rather than against `HEAD` — the safe direction. The
  ratchet is the exception, and it is the exception on purpose: it is the one
  tool whose answer *is* the comparison, so it cannot have one without a base
  and fails instead of answering about a comparison it did not make.
- `config/hooks/pre-push.json` records the hook's base chain, and
  `scripts/check-hook-gates.py` verifies code and JSON in both directions, so
  the record and the hook cannot drift.
- A gate that runs in another tree is given an environment without the base
  name, and so is a fixture that builds its own repository. The name is not
  banned — the tree it belongs to still gets it — but it does not travel.
- A push that **created** the branch has no previous state to measure against,
  and its run therefore fails the ratchet rather than passing vacuously. The
  first push to a new branch is not measurable, and the gate says so instead of
  naming the pushed commit as its own baseline.
- A base that is the change itself is a base **only** when it is the trunk: the
  work is already in the trunk, and the measurement is empty by truth. When the
  only candidate is the branch the push went to, there is no base, and the
  tools that must measure against something say so instead of reporting an
  empty change for work that was never measured.
- The branch topology itself — `dev` existing, protected, and receiving the
  population split — is #2832's later phases. This decision makes the tools
  able to name it; it does not create it.

## Links

- `scripts/src/sgate/gatekit/refs.py` — `BASE_CANDIDATES`,
  `REMOTE_BASE_CANDIDATES`, `TRUNK_CANDIDATES`, `resolve_base_ref()`,
  `resolve_change_base_ref()`, `NULL_COMMIT`, `is_null_commit()`.
- `scripts/src/sgate/gatekit/gitenv.py` — the names that must not cross into
  another tree, and `foreign_env()`.
- `scripts/refs_loader.py` — the by-path loader for hook-side scripts, and the
  command line that answers "which base?" for recipes outside the package.
- `scripts/check-baseline-ratchet.py` — the three outcomes, and the failure
  that is not a warning.
- `scripts/src/sgate/commands/checkout_state.py` — `_upstream_ref()`, the
  three-valued `differs`.
- `scripts/tests/test_gatekit_refs.py` — the chain, the exclusive named base,
  the resolver's command, the role before the freshness, the change that is
  never measured against itself, and the one exception that is the trunk.
- `scripts/tests/test_ci_pr_path.py` — the base is named once for the whole
  workflow, named per event, and never as the branch that was pushed; every
  event of the mapping is pinned, and so is what happens when the name it
  produces is not in the checkout.
- `scripts/tests/test_check_baseline_ratchet.py` — the ratchet fails when it
  cannot measure — a base that is not here, a base with no merge-base, the
  null commit, the pushed branch that is the only candidate — and passes when
  there is nothing to measure.
- `scripts/tests/test_checkout_state.py` — the integration branch as the
  base, the local branch that is not a base, the missing base that stays
  unknown.
- `scripts/tests/test_git_sandbox.py` — the base name that does not travel
  into another tree.
- `scripts/tests/test_check_gate_wiring.py` — the control that still proves the
  gate can fail when a base is named.
- `scripts/tests/test_pre_push_verdict.py`, `scripts/tests/test_pre_push_hook.py`
  — the fixtures that build a repository of their own, and the base they
  resolve against it.
- `.githooks/pre-push` — the shell chain.
- `docs/decisions/2026-10-25-the-suite-measures-this-checkout.md`.
- `docs/decisions/2026-10-24-the-ratchet-measures-the-branch-growth.md` — why the ratchet
  compares against the merge-base; its `HEAD` fallback is superseded here.
- `docs/decisions/2026-10-23-the-local-verdict-judges-what-the-change-reaches.md`.
