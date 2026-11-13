---
title: The git operations are GitPython operations
date: 2026-11-12
status: open
tags: [ci, tooling, git]
---

# Context

Issue [#2853](https://github.com/gazon1/sing/issues/2853) asked for the custom
wrappers over the git CLI to be replaced by GitPython. The question was which
wrappers those were.

The two helpers of the publication — `scripts/ci/allure_pages.py` and
`scripts/ci/allure_report_link.py` — have no git wrappers at all. The first one
works with the file system, and the second one talks to the GitHub API through
the `gh` CLI. GitPython replaces wrappers over `git`; it has nothing to say
about `gh`.

The wrappers that do exist live elsewhere. `scripts/git_changed.py` asks git
which files changed since a base, and `scripts/src/sgate/control_scope.py` asks
git how far the current tree drifted from the base of the checkout. Both were
written as a hand-rolled `subprocess.run(["git", ...])` around a string of
arguments, with the parsing of the output and the failure of the command left
to the caller.

The inventory of the scripts that run git commands found three kinds:

- **git wrappers** — a function that builds an argument list, runs git, and
  parses the result: `scripts/git_changed.py`,
  `scripts/src/sgate/control_scope.py`;
- **one command, used once** — a script that runs a single git command for its
  own narrow purpose, and keeps its own output format: `git status --porcelain
  -z` in `scripts/changed-modules.py`, `git worktree list --porcelain` in
  `scripts/src/sgate/commands/checkout_state.py`, and the one-shot commands in
  `scripts/documentation/check.py`, `scripts/test-impact-map.py`,
  `scripts/pre-push-verdict.py`, `scripts/ktlint-lint.py`,
  `scripts/staged-tree.py`, `scripts/ci-verdict.py`,
  `scripts/prune-merged-branches.py`, `scripts/checkout-invariants.py`,
  `scripts/check-hooks-path.py`, `scripts/check-baseline-ratchet.py`,
  `scripts/run-android-ui-tests.py`, `scripts/check-doc-dead-refs.py`;
- **not git** — the `gh` CLI for the GitHub API, and the ordinary system
  tools.

# Idea

1. **Migrate the wrappers, not the one-shot commands.** A wrapper is a layer
   with an API of its own; the one-shot commands are not, and rewriting them
   would replace one call with a library call that has to be understood first.
2. **Use the library directly.** No second wrapper on top of GitPython — the
   point of the migration is to stop owning a layer.
3. **Keep the git commands that GitPython does not cover well.** The porcelain
   output of `git status -z` and the worktree listing are formats GitPython
   does not model; they stay in `subprocess`.
4. **One answer to "which tree is this git command about?"** A hook runs in the
   tree of the checkout it belongs to, and a git command run from the wrong
   tree reads another tree's state. The environment that makes the command
   read its own tree is shared by every caller, so it is written once.

# Decision

**The two wrappers are GitPython.** `scripts/git_changed.py` opens the
repository with `Repo(path)`, reads the base commit and the merge base with
`repo.merge_base(...)`, and asks for the differences with `Commit.diff(...)` —
`git.INDEX` for the staged set, `None` for the whole working tree. The paths
that changed come from the diff itself (`change_type`, `a_path`, `b_path`) and
are handed to git as a pathspec. `scripts/src/sgate/control_scope.py` does the
same for the distance between the checkout and its base.

**The one-shot commands stay in `subprocess`.** `scripts/changed-modules.py`
parses the bytes of `git status --porcelain -z`, a format that is deliberately
not the porcelain of `Repo.status()`. `scripts/src/sgate/commands/checkout_state.py`
reads `git worktree list --porcelain`, whose format GitPython does not expose.
The remaining scripts run one git command each, for their own purpose, and
their behaviour is not changed.

**GitPython is a dependency of the project, pinned.** `GitPython==3.2.1` is
declared in the existing manifest, `scripts/src/pyproject.toml`, next to the
other runtime dependencies of the scripts. The CI jobs that run the scripts
install the manifest through `.github/actions/setup-python-tools`, so the
dependency arrives where it is needed without a second, parallel installation
path.

**The local lint path finds the dependency itself.** The `hk` step runs
`python3 scripts/lint-changed.py` with the python of the shell, and the system
python has no GitPython. `scripts/lint-changed.py` therefore re-runs itself
with the interpreter of the tooling venv — the one
`scripts/ci/setup-local-tools.sh` installs, named after the checkout — when
its own interpreter cannot import `git`, and names that installer when there
is no such venv to run with.

**The tree a command reads is decided in one place.** `sgate.gatekit.gitenv.own_tree_env()`
is a context manager that takes `GIT_DIR`, `GIT_WORK_TREE`, `GIT_INDEX_FILE` and
`GIT_COMMON_DIR` out of `os.environ` for the duration of the call and puts them
back afterwards — GitPython reads `os.environ` itself, so the names have to be
gone from the process, not from a copy of it. Both migrated wrappers run inside
it. A hook that inherits a `GIT_DIR` from the checkout it was invoked in
therefore reads its own tree, not the tree of another checkout on the same
machine.

**The states git can be in are states the helpers handle.** A path that is not a
repository, a repository without commits, a detached HEAD, a base that does not
exist, a repository whose index is broken, and a dirty working tree are all
covered by tests. The failure of a git command names what git said: GitPython
3.2.1 fills `stderr` with the reason, and the exit code is the fallback for
the commands that leave the reason empty.

# Rationale

- The wrappers were the layer: each of them parsed git's output by hand, and
  the parsing was where the bugs were. GitPython hands back the objects, not
  the text.
- A one-shot command is not a layer. Replacing `subprocess.run(["git", "rev-parse", ...])`
  with `repo.commit(...)` in a script that runs one command is churn, and the
  behaviour of the script is unchanged either way.
- GitPython and the `gh` CLI are different surfaces. The GitHub API stays
  behind the `gh` client described in
  `2026-11-12-the-publication-says-only-what-it-verified.md`; mixing the two
  would have turned a fix of the publication into a migration of everything.
- A pinned version in the existing manifest keeps one installation path. A
  separate `requirements.txt` would have been a second thing to keep in sync
  with the scripts, and the CI already installs the manifest.
- The environment of the caller decides which tree is read. Two checkouts on
  one machine, one of them with a `GIT_DIR` pointing at the other, is a real
  arrangement on this machine, and the answer belongs to one function.

# Consequences

- The scripts now need GitPython installed. It is in the manifest, so the CI
  jobs that run them install it, and a local checkout needs
  `bash scripts/ci/setup-local-tools.sh` — the scoped lint re-runs itself with
  that venv's interpreter rather than failing on the import.
- `Repo.is_dirty()`, `Repo.untracked_files`, and the porcelain status differ
  in their details; where the porcelain bytes are the contract
  (`scripts/changed-modules.py`), the command stays.
- A second layer over GitPython would reintroduce what the migration removed.
  The migrated code calls the library directly.
- The failure messages of the two wrappers changed: they now name the git
  exit code and the reason git gave, if it gave one.

# Links

- Issues: [#2853](https://github.com/gazon1/sing/issues/2853)
- Code: `scripts/git_changed.py`, `scripts/src/sgate/control_scope.py`,
  `scripts/src/sgate/gatekit/gitenv.py`, `scripts/src/pyproject.toml`,
  `scripts/lint-changed.py`,
  `scripts/tests/test_git_python_states.py`,
  `scripts/tests/test_lint_changed_gitpython.py`,
  `scripts/tests/test_rumdl_changed.py`, `scripts/tests/test_control_scope.py`,
  `docs/engineering/script-tooling-audit.md`
- Related: `2026-11-12-the-publication-says-only-what-it-verified.md`
