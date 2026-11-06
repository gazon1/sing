---
title: "The commit runs hk steps over the staged tree, and the hook keeps no logic of its own"
date: 2026-11-05
status: accepted
tags: ["tooling", "hooks", "git"]
---

# The commit runs hk steps over the staged tree, and the hook keeps no logic of its own

## Context

The pre-commit hook checked two things: ktlint over the changed Kotlin files,
and `scripts/compile-changed.py` over the modules the commit touches. The
isolation of unstaged work was the hook's own: `scripts/staged-tree.py` held
the working tree at the index and put it back afterwards.

The first migration step replaced that hold with `hk`: the hook became a
wrapper around `hk run pre-commit`, the file selection moved to `lint-staged`,
and `staged-tree.py` was deleted. Three things went wrong in that step, and
none of them announced itself:

- `scripts/generate-hk-pkl.sh` wrote the config through an **unquoted**
  heredoc, so `$PATH` was expanded into the committed `hk.pkl`. The file named
  one developer's mise/tool directories and could not work on another machine.
  The sync check regenerated it in the same environment, so it agreed with
  itself and passed.
- The config declared `fix = false, stash = "none"`, while `AGENTS.md` and the
  ADR it superseded said `fix = true, stash = "git"`. With `stash = "none"` a
  commit is checked against the **working tree** — the exact regression ADR
  `2026-10-24-the-commit-is-checked-on-its-own-tree.md` was written to prevent.
- `scripts/tests/test_pre_commit_hook.py` drove the real hook through a real
  commit, but its fixture carried no `mise.toml` and no `hk.pkl`. The `hk`
  shim answered "No version is set for shim: hk" and all six tests failed.
- The superseding ADR named in the frontmatter of the 2026-10-24 record did not
  exist.

`lint-staged` also arrived as a Node dependency (`package.json`,
`package-lock.json`, `lint-staged.config.mjs`, an `npm:` entry in `mise.toml`)
for one job: selecting the staged Kotlin files for one command. hk already
does that selection, and does it without a Node toolchain.

## Idea

- Keep hk, fix the declaration: the step's `glob` and `{{files}}` replace
  `lint-staged`, and `stash = "git"` restores the isolation the previous ADR
  chose.
- Keep the wrapper, and give it nothing but the hook name. File selection,
  ordering and the isolation are hk's; the wrapper only answers "is hk here"
  and returns its exit code.
- Drop the Node dependency rather than pin it: one file selection is not worth
  a second package manager in a Kotlin repository.
- Leave the pre-push hook alone. Its steps come from a registry
  (`scripts/src/sgate/gatekit/pre_push.py`), it checks out the tree being
  pushed into a throwaway worktree, and `scripts/check-hook-gates.py` holds its
  shape against that declaration. Expressing any of that as hk steps would be
  a rewrite of a working, tested check — the one thing the migration must not
  do.

## Decision

`hk.pkl` declares the pre-commit steps:

```pkl
["ktlint"] {
  glob = List("**/*.kt", "**/*.kts")
  check = "python3 scripts/ktlint-lint.py --require {{files}}"
}

["compile-changed"] {
  depends = "ktlint"
  check = "... python3 scripts/compile-changed.py"
}
```

with `fix = false` and `stash = "git"` on the hook. `.githooks/pre-commit`
remains the project's wrapper (`hk run pre-commit`, no `hk install`), and
`hk.pkl` is still generated from the version pinned in `mise.toml`.

`scripts/tests/test_hk_config.py` holds the declaration: the version matches
`mise.toml`, the generated file is the committed one, no absolute path or
`PATH` export appears in it, the ktlint step uses `{{files}}`, and the hook
isolates unstaged work. `test_pre_commit_hook.py` holds the wrapper: hk is
called with `run pre-commit`, a failing hk keeps the commit from happening, a
missing hk says how to install it, and a linked worktree is checked too.

## Rationale

**hk's `{{files}}` is the file selection, and it is safe.** Verified against
hk 1.23.0: a file named `Scratch Hk.kt` reaches ktlint as one argument, and a
commit with no staged Kotlin files runs no step and exits 0.

**`stash = "git"` is the isolation, and it is checked on the real case.** With
a staged change and an unstaged change in the same file, the steps see the
staged content; `git commit <path>`, which git runs against a temporary index,
leaves the other files untouched and commits only the named path.

**The wrapper has no logic because a hook with logic is the thing that broke.**
The old hook chose the tree, held it, and checked it; every one of those steps
had a failure mode of its own. The new wrapper can only fail in ways hk
already reports.

**The compile step stays in the commit.** It is not part of "make it fast": its
own ADR (`2026-10-23-the-commit-compiles-what-the-change-reaches.md`) and four
tests say a commit must compile, and moving it out would quietly restore a
commit that does not.

## Consequences

- A commit lints the Kotlin files it stages and compiles the modules it
  touches. Empty selection is a clean, silent pass — not an error and not a
  whole-tree lint.
- Unstaged **modifications** are held aside while the steps run. Unstaged
  **untracked** files are not: hk 1.23.0 keeps them in the worktree, so a new
  file outside the index is visible to the compile. That is the one difference
  from `staged-tree.py`, and it is the safer side of the trade — a linter that
  removes a developer's new file would be worse than one that reads it.
- `ktlint` is required: `--require` fails the commit instead of skipping the
  formatting check. hk, ktlint and mise are one installation (`mise install`),
  so a machine that has the hook's orchestrator has its linter.
- Node is gone from the repository: no `package.json`, no lockfile, no
  `lint-staged` in `mise.toml`.
- The pre-push hook is unchanged: the structural gates and the detekt verdict
  still come from the registry, against the tree being pushed.
- `scripts/check-hk-pkl-sync.sh` is gone. It was never registered as a gate
  and its invariant — the committed file is what the generator writes — is a
  test now, so it runs with the suite instead of never.

## Links

- `.githooks/pre-commit`, `hk.pkl`, `scripts/generate-hk-pkl.sh`
- `scripts/tests/test_hk_config.py`, `scripts/tests/test_pre_commit_hook.py`
- `scripts/ktlint-lint.py`, `scripts/compile-changed.py`
- Supersedes `2026-10-24-the-commit-is-checked-on-its-own-tree.md`
- `2026-10-23-the-commit-compiles-what-the-change-reaches.md`
