---
date: 2026-10-23
status: accepted
deciders: [maintainer]
---

# The checkout has a doctor and a gate

## Context

The cause of the checkout's repeated corruption is already written down:
[2026-10-23-a-test-fixture-must-not-reach-the-developers-repository.md](2026-10-23-a-test-fixture-must-not-reach-the-developers-repository.md).
A fixture that ran inside the pre-push hook inherited the `GIT_DIR` that git
exports, and its `git config` calls landed in the repository the environment
named — one common `.git` and about twenty worktrees. The result was a
checkout that was bare (every command refused), a checkout whose hooks path
was lost (git runs *no* hooks, silently), and a checkout whose `core.sshCommand`
pointed at a port and an identity file that do not exist here (every fetch and
every push failed, with 141).

The cause is fixed: the fixtures run their git commands through
`sgate.gatekit.gitenv` and `scripts/tests/git_sandbox.py`, and the answer is
written down once.

What was missing is the other half: **nothing checked the checkout.** Each
corruption was diagnosed from scratch, in three separate sessions, because the
symptom names the failure and never the cause — "push sends nothing",
"commit fails", "push dies with 141" are three different stories about one
broken file.

## Decision

- **The checkout has a doctor.** `scripts/doctor.sh` answers "is this checkout
  sound?" in one call: not bare, the hooks path is there and holds an
  executable pre-push, the ssh command carries the keepalive, the remote is
  configured, the Gradle home is private per worktree, and the gate tooling
  serves this checkout. `--fix` repairs what is
  safe to repair (bare, hooks path) and touches nothing else: a configured
  ssh command may carry a different binary, a port or an identity, and
  overwriting it would break the push in a way nobody would read as this. The
  gate tooling is not repaired either, for the same kind of reason: taking it
  over from another checkout is a decision about somebody else's environment,
  and the installer refuses it too.
- **The checkout has a gate.** `scripts/check-checkout-invariants.py` holds the
  checkout to a written promise — `config/checkout-invariants.json` — and the
  doctor reads the same file. A gate and a doctor that disagree are two
  policies.
- **The rule is mechanical.** `scripts/check-git-environment.py` finds the
  scripts that read a tree that is not their own checkout's root and fails
  when they do not control the environment those commands inherit. The
  sanctioned way to control it is the shared helper
  (`sgate.gatekit.gitenv.tree_env` / `sandbox_env`), and the gate accepts a
  call to it. The gate found a real violation the day it was written — in the
  script that measures the verdict's price.
- **The cheat sheet has a size.** `scripts/check-agents-size.py` fails when
  `AGENTS.md` grows past the limit. The file is read by every session, in one
  go, and every line of it is paid for whether the session needs it or not; the
  rules that need more room live in `docs/architecture/`, and the file points
  at them.

## Rationale

- A tool that answers the question in one call is worth more than a rule that
  says "remember to check". Three sessions of diagnosis for one cause is the
  price of not having the tool.
- The doctor is a tool and the gate is a gate: a machine's state is not the
  repository, and a gate that failed in CI for a developer's broken checkout
  would be a gate everybody learned to ignore. The checkout invariants are
  checked outside CI; the repository's promises are checked everywhere.
- The declaration is a file so that the doctor and the gate read one promise.

## Consequences

- A corrupted checkout is diagnosed in one call, and the pre-push gate refuses
  to certify a tree it cannot judge.
- The gate that holds the checkout to the declaration has a control: the
  declaration's hooks path is changed, and the gate has to fail.
- `AGENTS.md` cannot grow back without a deliberate change to the gate.

## A control that proves nothing

The first versions of the two controls declared here passed against their own
sabotage, and the harness said so:

- `agents-size` appended 40 lines to `AGENTS.md`. The file sits at 218 lines
  and the limit is 300, so the sabotaged file was 258 lines and the gate
  correctly passed. A control that lands under the ceiling it is meant to
  cross certifies nothing.
- `git-environment` appended its probe to the gate's own source. That file
  *mentions* the shared helper in its prose, and the gate reads a mention of
  the helper as a script that controls its environment — so the appended
  violation was excused by the very text it sat next to.

Both fixes are the same rule: a probe has to be measured, not assumed. It has
to change the thing the gate reads by enough to cross the gate's own
threshold, and it has to land in a file the gate does not already excuse. The
probe for `git-environment` therefore lands in `scripts/changed-modules.py`,
which reads a tree it was pointed at and does not claim to control the
environment.

## The doctor must not write what it repairs

The first version of the doctor repaired a broken checkout with a plain
`git config` — which lands in the SHARED config, and therefore in every
worktree of the clone. It was the very damage the doctor exists to diagnose,
written by the doctor itself: an absolute `core.hooksPath` in the shared
config made every worktree run one checkout's hooks, and the repository's own
blocking gate went red because the shared value no longer matched the
declaration. The review found it; a test now holds it.

The repair therefore goes where the damage was: into THIS worktree's own
`config.worktree`, with `git config --file`, and never into the shared
config. The value it writes is the declared relative one (`.githooks`), so
that the doctor and the gate read one promise and cannot disagree — the
declaration is a file precisely so that two readers have one policy.

Two more things followed from the same review, and both are the same rule
("the tool touches only its own tree"):

- The doctor resolves every path against the repository root, not against the
  current directory. Run from a subdirectory it used to report a healthy
  checkout as broken, and `--fix` used to write a broken relative path.
- The tool that measures the price of the verdict (`scripts/measure-verdict-cost.py`)
  measured the `auto-correct` phase **in the tree it was measuring** — a
  correcting detekt in the developer's checkout, shared with ten sessions. The
  instrument of "the verdict does not correct" corrected the tree. The
  correction now runs in a throwaway copy the tool creates and removes, and
  the tool refuses to correct the tree you are working in.

## Links

- `scripts/doctor.sh` — one call that says whether the checkout is sound
- `scripts/check-checkout-invariants.py` — the gate
- `config/checkout-invariants.json` — the declared invariants
- `scripts/check-git-environment.py` — the mechanical rule
- `scripts/check-agents-size.py` — the cheat sheet's size
- [2026-10-23-a-test-fixture-must-not-reach-the-developers-repository.md](2026-10-23-a-test-fixture-must-not-reach-the-developers-repository.md) — the cause
- [2026-10-23-a-push-keeps-its-exit-code-and-its-connection.md](2026-10-23-a-push-keeps-its-exit-code-and-its-connection.md) — the keepalive, measured
