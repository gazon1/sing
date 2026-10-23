---
date: 2026-10-23
status: accepted
deciders: [maintainer]
---

# The shared config is one file, and every worktree writes to it

## Context

The canonical checkout's `.git/config` was corrupted three times in one
session, and each corruption had a different symptom:

- `core.bare = true` — every command in the checkout refused ("this
  operation must be run in a work tree"), so nothing could be committed and
  no tree could be judged;
- `core.sshCommand` replaced by another value — the keepalive was gone, and
  a push died with 141 after the hook had already said the tree was clean;
- `core.hooksPath` lost — git silently ran NO hooks, so the pre-push verdict
  never happened and nothing said so.

Each time the cause had to be found from scratch, because the symptom names
the failure and never the cause. Three sessions of diagnosis for one cause is
the cost of not having a tool that answers the question.

## The cause, measured

The checkout is not one repository: it is **one common `.git` and about twenty
worktrees**. `git worktree list` shows them all pointing at
`.git/worktrees/<name>`, and they share `.git/config` — a `git config` run in
any of them lands there, unless the key is worktree-scoped.

Git also exports `GIT_DIR`, `GIT_WORK_TREE`, `GIT_INDEX_FILE` and
`GIT_COMMON_DIR` to every hook it runs, and those variables **override the
directory a git command was pointed at**. A test that runs `git config` in
its own temporary repository, in an environment where `GIT_DIR` is exported,
therefore writes into the repository the environment names.

The two facts meet in the test suite. Measured on 2026-10-23: the suite was
run with `GIT_DIR` pointed at a scratch repository, and the scratch
repository's config came back holding

```
sshCommand = ssh -p 2222 -i /home/me/key     # from test_setup_worktree.py
hooksPath = .githooks                          # from test_pre_push_hook.py
[user] email = verdict@example.com            # from test_pre_push_verdict.py
[remote "origin"] url = /tmp/push-sh-…/remote.git   # from test_push_sh.py
```

— four values, none of them belonging to the scratch repository, all of them
of the shape that had broken the checkout. `ssh -p 2222 -i /home/me/key` is
the fixture value of `test_setup_worktree.py`; `hooksPath` and the remote
URL are the fixtures of the other two. The value that had made the push die
with 141 was a **test fixture**, written into the shared config by a test
that thought it was configuring a temporary repository.

## Decision

- **The fix is in the code that writes.** A test that runs git in a tree of
  its own runs with the exported variables removed:
  `scripts/tests/_git_env.py` provides `GitEnvTestCase` (a base class that
  removes them for the duration of the test) and `clean_git_env()`. The
  tests that call git in a temporary repository use the base class.
- **The hook's gates already ran with a cleaned environment** — that was
  added on 2026-10-23 for a different reason (the pushed tree's index) — and
  the list now includes `GIT_COMMON_DIR`.
  `GIT_OBJECT_DIRECTORY` and `GIT_QUARANTINE_PATH` are deliberately NOT
  removed: they hold the objects of the commits being pushed, and the
  verdict has to see them.
- **The rule is mechanical.** `scripts/check-git-environment.py` finds the
  scripts that read a tree that is not their own checkout's root and fails
  when they do not neutralise the variables. It found a real violation the
  day it was written — in the script that measures the verdict's price.
- **The checkout has a doctor and a gate.** `scripts/doctor.sh` answers
  "is this checkout sound?" in one call and repairs what is safe to repair;
  `scripts/check-checkout-invariants.py` holds the checkout to the
  declaration in `config/checkout-invariants.json` (not bare, the hooks are
  there, the keepalive is there).

## Rationale

- A rule that is only enforced on the surface where the damage was seen
  leaves the other surfaces free to make it again. The environment has to be
  cleaned where the command is issued, not where the symptom shows up.
- The declaration is a file, so the gate compares the live checkout against
  a written promise, and the doctor and the gate read the same promise. A
  gate and a doctor that disagree are two policies.
- The keepalive measurement belongs here as well: see
  [2026-10-23-a-push-keeps-its-exit-code-and-its-connection.md](2026-10-23-a-push-keeps-its-exit-code-and-its-connection.md).

## Consequences

- The test suite can no longer write into a repository it did not mean to
  write into, whichever checkout it runs in.
- `scripts/check-git-environment.py` is a blocking gate, with a control that
  appends such a command to the gate's own source and requires the gate to
  fail.
- The checkout doctor is a tool, not a gate: it reports the state of a
  machine, and a machine is not the repository. The gate is the part that
  can be a gate.

## Links

- `scripts/tests/_git_env.py` — the environment a test's git runs in
- `scripts/check-git-environment.py` — the mechanical rule
- `scripts/doctor.sh` — one call that says whether the checkout is sound
- `scripts/check-checkout-invariants.py` — the gate
- `config/checkout-invariants.json` — the declared invariants
- [2026-10-23-a-push-keeps-its-exit-code-and-its-connection.md](2026-10-23-a-push-keeps-its-exit-code-and-its-connection.md) — the keepalive and its measurement
