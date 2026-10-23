---
title: "The bare flag lives in the worktree config: a repo-wide switch must not be switched on before the worktrees are ready for it"
date: 2026-10-23
status: accepted
tags: [git, worktree, tooling, config]
---

# The bare flag lives in the worktree config

## Context

`scripts/setup-worktree.sh` did three things: it turned on
`extensions.worktreeConfig` (repo-wide), it wrote `core.hooksPath = .githooks`
into the per-worktree config with `git config --worktree`, and it printed a
Gradle home. The second thing is per worktree. The first thing is not — it is a
switch on the whole repository, and it changes where git reads `core.bare`
from.

`core.bare` is the flag that says "this repository has no working tree". A
worktree of a bare repository is not bare, and says so in its own per-worktree
config:

```
.git/worktrees/<name>/config.worktree:
    [core]
        bare = false
```

`git worktree add` writes that line — but only when the extension is already
on. A worktree created before the switch never gets it.

So the switch breaks the worktrees it did not touch. Measured on 2026-10-23,
in this repository (a bare repository with 39 linked worktrees): one run of the
setup script in one worktree made **every** worktree unusable, each failing
with

```
fatal: this operation must be run in a work tree
```

on `git status`, `git add`, `git commit` and `git rebase` alike. The repair is
one line per worktree in `config.worktree` — and the repair was applied by
hand, to 39 files.

Two things made it worse than a broken afternoon:

1. **The write had two destinations.** `git config --worktree <key> <value>`
  resolves its target *through git*. In a worktree that git already considers
  bare, that resolution does not land in the worktree's own config — it lands
  in the shared config. A `core.bare = false` written into the shared config
  of a bare repository turns the repository itself into a non-bare one: the
  repair becomes a second, deeper breakage, and it is silent.
2. **The order was invisible.** The script wrote the per-worktree value and
   then flipped the repo-wide switch, with nothing in between. The switch was
   the last step of a script whose first step had already been forgotten by
   every other worktree.

## Decision

**`core.bare = false` is written to every worktree's own config, by file, before
the extension is switched on.** The extension is switched on last.

`scripts/setup-worktree.sh` therefore:

1. finds the per-worktree config **without git** — a worktree that git
   considers bare answers no questions, so the path comes from the `.git` file
   (`gitdir: <path>`) and the `commondir` file beside it;
2. writes `core.bare = false` into **every** worktree's `config.worktree` —
   this one and all the others — while the extension is still off and those
   files are still inert;
3. writes `core.hooksPath = .githooks` into *this* worktree's
   `config.worktree`;
4. switches `extensions.worktreeConfig` on, last;
5. checks the result in git's own words: `git rev-parse --is-bare-repository`
   must say `false`, the effective `core.hooksPath` must be `.githooks`, and
   the shared config's `core.bare` must be exactly what it was before.

Every per-worktree write goes through `git config --file <path>`, never
`git config --worktree`. `--file` touches exactly the file it is given: no
repository is resolved, and no shared config is reachable from it. The shared
config is written in exactly one place — the extension itself, which is a
repo-wide concept and belongs there.

The main entry of a bare repository is left alone. Git reports it as `bare`,
it is not a worktree, and a `core.bare = false` written there would un-bare
the repository.

## Consequences

- The setup script can be run in a worktree that is already broken, because it
  never asks git to resolve anything it could not resolve.
- The script no longer has a step that can break a repository it was not run
  in: the switch is last, and every worktree is ready for it before it flips.
- A `core.bare = false` in the shared config is no longer reachable from this
  script at all, and the script fails loudly if the shared `core.bare` changed
  while it ran.
- The per-worktree config is written for every worktree in the repository, not
  only for the current one. That is the price of a repo-wide switch: the
  switch is only safe if every worktree is prepared for it first.

See `docs/decisions/2026-10-04-gradle-daemon-isolation.md` for the other half
of this script — the per-worktree hooks path and the private Gradle home.
