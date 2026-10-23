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
  configured, the Gradle home is private per worktree. `--fix` repairs what is
  safe to repair (bare, hooks path) and touches nothing else: a configured
  ssh command may carry a different binary, a port or an identity, and
  overwriting it would break the push in a way nobody would read as this.
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

## Links

- `scripts/doctor.sh` — one call that says whether the checkout is sound
- `scripts/check-checkout-invariants.py` — the gate
- `config/checkout-invariants.json` — the declared invariants
- `scripts/check-git-environment.py` — the mechanical rule
- `scripts/check-agents-size.py` — the cheat sheet's size
- [2026-10-23-a-test-fixture-must-not-reach-the-developers-repository.md](2026-10-23-a-test-fixture-must-not-reach-the-developers-repository.md) — the cause
- [2026-10-23-a-push-keeps-its-exit-code-and-its-connection.md](2026-10-23-a-push-keeps-its-exit-code-and-its-connection.md) — the keepalive, measured
