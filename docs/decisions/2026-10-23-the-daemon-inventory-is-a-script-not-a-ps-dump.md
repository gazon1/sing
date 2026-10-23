---
date: 2026-10-23
status: accepted
deciders: [maintainer]
---

# The daemon inventory is a script, not a `ps` dump

## Context

On a host where ten agent sessions share one machine, the question "who owns
this Gradle daemon, and how long has it been alive?" is asked whenever a
build blocks on another build's cache lock. The answer was found with

    ps -eo pid,etime,args | grep GradleDaemon

which prints about 40 KB of command lines — the full classpath of every
daemon on the machine. The harness kept the dump as an artifact, and the
question was still unanswered, because the answer was somewhere inside it:
which daemon belongs to which worktree.

## Idea

A script that answers the question and prints nothing else.

## Decision

`scripts/gradle-daemons.sh` prints one line per Gradle daemon: the pid, the
age, the Gradle version and the worktree the daemon belongs to. It reads
`/proc/<pid>/cmdline` (NUL-separated) and matches the daemon's main class
exactly, so a JVM that merely mentions Gradle on its command line is not
counted as a daemon, and it takes the age from `/proc/<pid>/stat` against
`/proc/uptime`.

The worktree comes from the daemon's own command line: `-Dorg.gradle.projectcachedir`
points into the worktree, and the `.gradle-user-home` segment of the classpath
names it. A daemon that runs on the *global* Gradle home carries no worktree,
and the script prints `?` for it — the honest answer, not a parse failure.

## Rationale

- The dump was the answer to a different question. "Show me every command line
  on this machine" is not "which worktree owns this daemon".
- `/proc` is what the daemon's own command line says, read without
  interpretation. `ps` reformats it into something a human reads; the script
  reads it as data.
- A daemon on the global home is a fact worth printing plainly: it is the one
  that can lock another worktree's cache.

## Consequences

- The question has one command, and its output is a table a person can read
  in one glance instead of a 40 KB artifact.
- `AGENTS.md` names the script where the daemon rule already lives, next to
  the rule that two Gradle calls must not run at once.

## Links

- `scripts/gradle-daemons.sh` — the inventory
- `AGENTS.md` — the worktree rules it serves
- `docs/decisions/2026-10-04-gradle-daemon-isolation.md` — why the daemons
  are separated in the first place
