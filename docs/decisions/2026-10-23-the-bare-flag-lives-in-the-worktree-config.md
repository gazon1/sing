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

`git worktree add` does not write that line. Measured on git 2.56: the command
copies the MAIN worktree's `config.worktree` into the new worktree's — and the
main worktree of a bare repository has no such file, so the new worktree gets
nothing at all. A worktree created before the switch never gets it either.

So the switch breaks the worktrees it did not touch, and it breaks the ones
created afterwards too. Measured on 2026-10-23, in this repository (a bare
repository with linked worktrees): one run of the setup script in one worktree
made **every** worktree unusable, each failing with

```
fatal: this operation must be run in a work tree
```

on `git status`, `git add`, `git commit` and `git rebase` alike. The repair is
one line per worktree in `config.worktree` — and the repair was applied by
hand, to every one of those files.

Two things made it worse than a broken afternoon:

1. **The repair command had more than one destination.** `git config` resolves
   where a value lands, and the command decided the place:

   - plain `git config core.bare false` always writes the **shared** config —
     from any worktree, including a worktree git already considers bare. That
     is how a `core.bare = false` reached the shared config of the bare
     repository, and it un-bared the repository for every worktree at once;
   - `git config --worktree core.bare false` resolves differently: in a linked
     worktree it lands in that worktree's own `config.worktree` (measured on
     git 2.56), but in the **main entry** of a bare repository it lands in the
     main entry's `config.worktree`, where the same value makes the repository
     answer `false` to `git rev-parse --is-bare-repository` — the repository
     itself is un-bared, silently.

   Neither form shows the destination in the command itself, and both are
   silent: the write succeeds, and the damage shows up somewhere else — in
   another worktree, or in the repository itself.
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
5. checks the result in git's own words: in a worktree,
   `git rev-parse --is-bare-repository` must say `false` — and in the bare
   repository's own main entry it must say `true`, because that entry is the
   repository and not a worktree of it; the effective `core.hooksPath` must be
   `.githooks`, and the shared config's `core.bare` must be exactly what it was
   before.

Every per-worktree write goes through `git config --file <path>`, never
`git config --worktree`. `--file` touches exactly the file it is given: no
repository is resolved, and the destination is the script's decision rather
than git's. `git config --worktree` resolves its target through git, and where
that resolution lands depends on the entry the command runs in — in a linked
worktree it is that worktree's own `config.worktree`, in the main entry of a
bare repository it is the main entry's `config.worktree`, and without the
extension it is the shared config (or the command is refused outright, when
the repository has more than one working tree). Plain `git config` always
writes the shared config, from any worktree. The shared config is written in
exactly one place by this script — the extension itself, which is a repo-wide
concept and belongs there.

The main entry of a bare repository keeps its bare flag. Git reports it as
`bare`, it is not a worktree, and a `core.bare = false` written into its own
`config.worktree` would un-bare the repository — `git rev-parse
--is-bare-repository` answers `false` there, and the repository's own files
become a working tree. The script writes the hooks path into that entry and
leaves `core.bare` alone there, and its check expects `true` in that entry and
`false` in every worktree.

Git's own documentation says the same thing about the shared value: with the
extension on, `core.bare` and `core.worktree` are per-worktree settings, and
"core.bare should not be shared if the value is core.bare=true"
(`git help worktree`, git 2.56). The shared `core.bare = true` of a bare
repository is therefore not a value any worktree may inherit — which is
exactly what the per-worktree `core.bare = false` prevents.

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
- A worktree created **after** the switch needs the same repair: `git worktree
  add` copies the main worktree's `config.worktree`, and the main entry of a
  bare repository has no `core.bare = false` to copy. A new worktree is born
  broken there, and the setup script — run once per worktree — is what repairs
  it.

See `docs/decisions/2026-10-04-gradle-daemon-isolation.md` for the other half
of this script — the per-worktree hooks path and the private Gradle home.
