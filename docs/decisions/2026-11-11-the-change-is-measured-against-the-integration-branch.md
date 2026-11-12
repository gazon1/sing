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
`BASE_CANDIDATES` holds those plus the local branches (`dev`, then `main`) —
the short names, because git resolves `origin/dev` to the remote-tracking ref
and `dev` to the local branch, so the fully qualified spellings would never be
reached.

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

**`dev` comes before `main` everywhere.** The trunk a change is based on is
the integration branch while it exists. `origin/main` stays in the chain so
that a repository which has no `dev` branch yet still measures its changes
against something — Phase 1 of #2832 lands before the branch does.

**A named base is exclusive.** `SGATE_BASE_REF` names the base for callers
that know better. When it names a ref that is **not here**, there is **no
base** — the chain is not consulted. Answering with a different ref would be
answering a question nobody asked, and a tool that measures against a base
the caller did not name measures against the wrong thing without saying so.
The tools therefore carry **no second answer**: no tool falls back to
`origin/main` when the owner has none, because that fallback is either dead
(the owner already tried `origin/main`) or wrong (it answers a question the
caller did not ask). A tool with no base says so.

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

**The ratchet keeps its announced fallback.** `scripts/check-baseline-ratchet.py`
has a base of its own to compare with, and a named base that is not here leaves
it comparing against `HEAD` — announced, in the label it prints
(`HEAD (SGATE_BASE_REF='…' does not exist)`). It does not consult the chain
either: the fallback is `HEAD` and it is said out loud, not another ref
answered for.

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
pre-push hook runs where neither is guaranteed.

**CI names the base once for the whole workflow.** The workflow sets
`SGATE_BASE_REF: ${{ github.base_ref && format('origin/{0}', github.base_ref) }}`
— the branch the pull request targets, and on a push **nothing**: the branch
that was pushed is the change itself, and a change measured against itself is
measured against nothing. The name is therefore empty on a push, and the chain
answers there. Every job that reads the base reads the same name, and a named
base never falls back to another branch.

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
named base always exists, so the rule costs nothing there; in a clone without
the branch it costs a clear "no base" instead of a wrong comparison.

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
  (`refs/remotes/<remote>/dev`, `refs/remotes/<remote>/main`, then the
  short names), because the hook cannot import the package.
- `scripts/git_changed.py`'s default changed from the literal `"origin/main"`
  to `None`, resolved at call time: a default evaluated at import time would
  run git at import.
- The tools that ask "what does this change touch?" no longer carry a base of
  their own. `LINT_BASE` and `COMPILE_CHANGED_BASE` are gone, and so is the
  `origin/main` tail: `SGATE_BASE_REF` and `--base` are the two ways to name a
  base, and the owner is the third answer that costs no configuration. The
  `rumdl-changed` recipe keeps a knob of its own, under the name that says
  what it is for: `RUMDL_BASE`.
- A tool that asks "what does this change touch?" and finds no base measures
  against **everything** rather than against `HEAD` — the safe direction.
- `config/hooks/pre-push.json` records the hook's base chain, and
  `scripts/check-hook-gates.py` verifies code and JSON in both directions, so
  the record and the hook cannot drift.
- A gate that runs in another tree is given an environment without the base
  name, and so is a fixture that builds its own repository. The name is not
  banned — the tree it belongs to still gets it — but it does not travel.
- The branch topology itself — `dev` existing, protected, and receiving the
  population split — is #2832's later phases. This decision makes the tools
  able to name it; it does not create it.

## Links

- `scripts/src/sgate/gatekit/refs.py` — `BASE_CANDIDATES`,
  `REMOTE_BASE_CANDIDATES`, `resolve_base_ref()`, `resolves()`.
- `scripts/src/sgate/gatekit/gitenv.py` — the names that must not cross into
  another tree, and `foreign_env()`.
- `scripts/refs_loader.py` — the by-path loader for hook-side scripts.
- `scripts/src/sgate/commands/checkout_state.py` — `_upstream_ref()`, the
  three-valued `differs`.
- `scripts/tests/test_gatekit_refs.py` — the chain, the exclusive named base,
  the resolver's command.
- `scripts/tests/test_ci_pr_path.py` — the base is named once for the whole
  workflow, and never as the branch that was pushed.
- `scripts/tests/test_checkout_state.py` — the integration branch as the
  base, the local branch that is not a base, the missing base that stays
  unknown.
- `scripts/tests/test_git_sandbox.py` — the base name that does not travel
  into another tree.
- `scripts/tests/test_check_gate_wiring.py` — the control that still proves the
  gate can fail when a base is named.
- `.githooks/pre-push` — the shell chain.
- `docs/decisions/2026-10-25-the-suite-measures-this-checkout.md`.
- `docs/decisions/2026-10-23-the-local-verdict-judges-what-the-change-reaches.md`.
