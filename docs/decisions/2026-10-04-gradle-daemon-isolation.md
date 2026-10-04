---
title: "A worktree gets its own GRADLE_USER_HOME; the caches stay shared"
status: accepted
date: 2026-10-04
tags: [build, gradle, worktree, process, ci]
---

# Context

This repository is worked on in parallel git worktrees, and more than one of them
runs Gradle at a time. They all inherit `~/.gradle` from the same `$HOME`, and
that directory holds two things that do not tolerate concurrent tenants:

- **`daemon/<version>/registry.bin`** — the daemon registry. `./gradlew --stop`
  reads it and stops every daemon it lists. Run from one worktree, it therefore
  kills the daemons of all the others, and the symptom lands in the wrong
  checkout: an unrelated build dies with `Daemon disappeared` and no explanation.
- **`caches/` and `.tmp/`** — the module cache and the worker temp area, where
  concurrent builds contend on locks.

The instinct is to reach for the project-side switches. That does not work, and it
is worth recording why, because the ADR that introduced a project-local
configuration-cache store (`2026-10-04-configuration-cache-hardening`) sits right
next to this one and looks like it would cover it.

# Idea

Separate the two things that are actually shared.

`GRADLE_USER_HOME` is the boundary that matters — it owns the daemon registry,
the native dir, the notifications dir and `.tmp/`. Pointing it at a directory
inside the worktree makes those private, and `--stop` becomes a local operation.

But it also owns `wrapper/`, `caches/` and `jdks/`, and those are *not* what we
want to duplicate. Re-downloading a Gradle distribution, the full dependency
graph and a toolchain JDK per worktree costs gigabytes and minutes, and buys no
isolation: those files are not rewritten concurrently in a way that corrupts
them — Gradle's file locking exists for exactly that sharing.

So the user home is private, and three entries inside it are symlinks back to the
real `~/.gradle`.

# Decision

`./gw` at the repository root is the entry point. It creates the private home,
links `wrapper`, `caches` and `jdks` to the real ones if they are not linked
already, and execs `gradlew` with `GRADLE_USER_HOME` set. `daemon/`, `native/`,
`notifications/`, `kotlin-profile/` and `.tmp/` are created locally and never
shared.

`git worktree add` runs the `post-checkout` hook, with the previous HEAD set to
the null SHA — that is the only hook Git fires for a new worktree, and it is
verified here rather than assumed. The hook calls
`scripts/setup-gradle-home.sh`, which `gw` also calls, so there is one
implementation and no second recipe to keep in sync. The setup creates a
directory and at most three symlinks: it never starts Gradle, never touches the
network and never runs `--stop`, and a failure is reported without blocking the
checkout. A plain `git clone` fires the same hook, so clones are covered too.

For people who would rather not prefix their Gradle invocations, `.envrc` exports
the same variable for `direnv`; the symlinks are then the manual step, and the
file says so.

If `GRADLE_USER_HOME` is already set, `gw` leaves it alone. That is the opt-out,
and it is also what lets a script hand a different home to a specific build.

**Forgetting `./gw` is made hard in three places, and one obvious fix is
deliberately not taken.** `check.sh` and every `just` recipe now call `./gw`, so
nothing inside the repository can bypass it. `direnv` users need no prefix at
all: entering the worktree sets the variable, and plain `./gradlew` is already
isolated. And the hook prints the one-line reminder when a worktree is created.

The tempting fourth option is `systemProp.gradle.user.home=./.gradle-user-home`
in the project's `gradle.properties`, which would make plain `./gradlew`
isolated for everyone with no wrapper and no env var. It works — measured, the
launcher honours it and creates `daemon/`, `jdks/` and `caches/` there. It is
not used, because the system property **overrides the `GRADLE_USER_HOME`**
environment variable, and CI sets that variable to the directory
`gradle/actions/setup-gradle` caches. Committing the property would silently
relocate the whole Gradle home on every runner and re-download the distribution
and the entire dependency graph each run. The wrapper is the version of this
idea that cannot reach CI.

**Do not run `./gradlew --stop`.** Inside a shared home it is not a cleanup, it is
a shot at the neighbours. Inside a private home it is safe, which is the entire
reason for the private home.

# Rationale

The boundary was verified rather than assumed. With a private home in place:

- `./gw help` started a daemon that the global registry did not know about: the
  live daemon count went 2 → 3, and the new one appeared only in
  `.gradle-user-home/daemon/9.7.1/registry.bin`.
- `./gw --stop` then reported `1 Daemon stopped` and took the count back 3 → 2.
  The other two daemons — other worktrees' — kept running and the global
  `registry.bin` was left alone.

That is the whole claim, and it is the only one that matters: `--stop` inside a
private home stops exactly its own daemon.

Two things that look like solutions and are not:

- **`--project-cache-dir` / `--project-cache`.** These move the *project* cache
  (`.gradle/` in the checkout). The daemon registry lives in the user home and is
  untouched, so `--stop` still crosses worktrees. The project-local configuration
  cache store from the earlier ADR is a different concern and does not cover this.
- **`timeout 900 ./gradlew …`.** Visible in `ps`, easy to mistake for a Gradle
  stop. It is a `SIGTERM` from the shell, aimed at the process it was given.
  Gradle daemons are not in that process group, so it does not stop anyone
  else's build — it only makes your own look like it was cancelled.

# Consequences

- Every Gradle invocation in a worktree should go through `./gw`. `check.sh` and
  `just` recipes call `./gradlew` directly and are therefore only isolated when
  `GRADLE_USER_HOME` is already exported (`.envrc`, or an explicit export) —
  which is exactly the case `gw` detects and respects.
- `.gradle-user-home/` is gitignored.
- The symlinks point at `$HOME/.gradle`, so a machine with no populated
  `~/.gradle` simply gets a private home with no links and downloads normally.
  `gw` checks the source exists before linking.
- Worktrees on the same machine still contend on the module cache. That is
  accepted: it is a lock wait, not a corrupted cache or a killed build.
- CI is unaffected — it has one checkout, one build, and a fresh runner.

# Links

- `gw` — the wrapper, with the recipe and the don't-do-this list in its header
- `.envrc` — the same isolation for `direnv` users
- `docs/decisions/2026-10-04-configuration-cache-hardening.md` — the project-local
  configuration-cache store, a different problem
- `docs/decisions/2026-10-04-measurement-integrity.md` — the gates that consume
  this build's output
