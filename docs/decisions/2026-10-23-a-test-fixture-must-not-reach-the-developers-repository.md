---
title: "A test fixture must not reach the developer's repository"
date: 2026-10-23
status: accepted
tags: [gates, tests, tooling]
---

# A test fixture must not reach the developer's repository

## Context

On 2026-10-23 the gate suite's own tests damaged the repository they ran in.

The damage was not subtle. A commit by `Hook Test` — the identity one fixture
writes into its own temporary repository — landed on `gazon1/roadmap-batch2`
and deleted 12124 files: the whole project, in one commit. The same tree
reached the remote `main`, where the repository was left with a single file
(`README.md`) until it was restored by hand. The same fixture wrote
`core.sshCommand = ssh -p 2222 -i /home/me/key` into the shared repository
configuration — the value from its own test, where it asserts that an existing
ssh command is left alone. With that in place, every `git fetch` and every
`git push` in every worktree of this repository tried to reach GitHub on port
2222 with an identity file that does not exist here, and failed.

The cause is one line of git's behaviour: **git exports `GIT_DIR` (and
`GIT_WORK_TREE`, `GIT_INDEX_FILE`, `GIT_COMMON_DIR`) for the hooks it runs.**
A test that creates a repository of its own, inside a hook, inherits those
names, and every git command it runs then means something else:

- `git init` reinitialises the repository the hook is about, not the test's own
  directory;
- `git add -A` stages the test's directory against *that* repository's index,
  so every tracked file of the developer's project looks deleted;
- `git commit` writes the test's tree onto the developer's branch.

That is why the damage happened during a push and not during a local run: the
same suite, run by hand, had no `GIT_DIR` to inherit and created its own
repositories as it should. The pre-push hook runs the suite, so every push
carried the trap with it — and the branch being pushed was the branch that
moved.

The production code already knew this. `scripts/pre-push-verdict.py` scrubs
those names for the tree it judges, with a comment that names the trap. The
*tests* did not: they created repositories with the inherited names in place.

## Decision

A fixture's git commands may not inherit the repository of the hook that runs
them.

1. **The names are removed.** Every git command a fixture runs gets an
   environment without `GIT_DIR`, `GIT_WORK_TREE`, `GIT_INDEX_FILE` and
   `GIT_COMMON_DIR` — the same scrubbing `scripts/pre-push-verdict.py` does.
   `scripts/tests/git_sandbox.py` is the one place that does it, and every
   fixture that creates a repository uses it.

2. **The walk up is bounded.** The same environment sets
   `GIT_CEILING_DIRECTORIES` to the fixture's own directory, so a command that
   does not find the fixture's repository fails instead of finding somebody
   else's. This is the second half of the contract, and it covers the case the
   scrubbing does not: a temporary directory that is inside another repository.

3. **The fixture says so when it is not its own repository.** After creating
   it, a fixture reads `git rev-parse --show-toplevel` and fails the test when
   the answer is not the fixture's directory. The failure is in the test
   output, where it is read, and not in the developer's history, where it is
   discovered later.

4. **The sandbox carries the identity a commit needs.** Git refuses a commit
   it cannot attribute, and it looks for the identity in the environment
   first and in the configuration files after. Where that identity comes from
   is the machine: a workstation has a global `~/.gitconfig`, and a runner may
   have none — and may have `user.useConfigOnly` set, which is precisely the
   setting that forbids git's own guess. A fixture that commits under the
   sandbox therefore worked on the machine that wrote it and failed in CI,
   with the failure saying only "exit status 128". The sandbox carries
   `GIT_AUTHOR_NAME`, `GIT_AUTHOR_EMAIL`, `GIT_COMMITTER_NAME` and
   `GIT_COMMITTER_EMAIL`; git reads those before any configuration file, so
   the commit is attributed to the fixture and to nobody else, and no
   configuration file is read or written.

The hook scrubs the names too, for the suite it runs: that is the belt to the
fixtures' braces, and it protects a fixture nobody has looked at yet.

The answer has one owner for everything that can reach the package:
`sgate.gatekit.gitenv` holds `tree_env()` and `sandbox_env()`, and the gates
and the fixtures both take it from there. The gate `check-hooks-path.py` is
one of the consumers — it reads the configuration of the repository it is
placed in, and under a hook the inherited `GIT_DIR` would have it answer about
another repository entirely.

One script keeps its own copy, and the reason is worth writing down:
`scripts/pre-push-verdict.py` travels into the tree that is being pushed,
alone, and that tree need not carry the package. A script that cannot import
the answer has to carry it.

The same rule landed on `main` in parallel, in PR #992: the hook drops the
environment for every step it runs in the pushed tree, and two of the fixtures
were fixed there. This change adds what that one did not have — the fixture
that writes `core.sshCommand` (`test_setup_worktree.py`), the gate that reads
the repository it is placed in, the owner the four copies of the rule now
share, and the incident written down as a test.

## Consequences

- The three fixtures that create a repository (`test_pre_push_hook.py`,
  `test_setup_worktree.py`, `test_pre_push_verdict.py`) now hold the tree they
  work in.
- `scripts/tests/test_git_sandbox.py` is the incident written down as a test:
  it sets `GIT_DIR` the way a hook sets it, shows that a command in the
  fixture's directory then means the *outer* repository, and shows that the
  sandbox makes it mean the fixture's own.
- A test that cannot create its repository fails a test instead of damaging a
  branch. That is the point: the damage was silent, and the silence was the
  defect.
- A fixture that commits under the sandbox commits as the fixture, on a
  workstation and on a runner alike. The suite stopped depending on the
  developer's global git configuration — a dependency that made the suite pass
  where it was written and fail where it runs.

## What it does not solve

The repository configuration is shared by every worktree, so a fixture that
escapes into it affects worktrees that are not even running the tests. The
scrubbing stops the escape; it does not undo an escape that already happened.
Cleaning up after an incident is a separate step — `core.bare`,
`core.sshCommand` and `core.hooksPath` were all restored by hand on
2026-10-23.
