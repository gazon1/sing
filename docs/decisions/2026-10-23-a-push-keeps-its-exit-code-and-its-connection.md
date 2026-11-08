---
date: 2026-10-23
status: accepted
deciders: [maintainer]
---

# A push keeps its exit code and its connection

## Context

Two failures ended the same way — a push that reported success and sent
nothing — and neither was visible in the log.

1. **The pipe ate the exit code.** `git push … | tail -40` reports the exit
   code of `tail`, and closes the pipe early enough that git dies of SIGPIPE
   (141). Measured in one session: six pushes left with 141 and an exit code
   that said "success". The rule that was written to prevent this ("do not
   pipe a command's output into `tail`/`head`") changed nothing: in the same
   session 100 of 145 bash calls still piped into `head`/`tail`. The rule was
   aimed at the harmless case (reading output) and said nothing about the
   dangerous one (a command whose exit code matters).
2. **The connection died while the hook ran.** Git opens its SSH connection
   to the remote *before* the pre-push hook runs. The hook's detekt verdict
   takes minutes, so the connection sits there with nothing on it, GitHub
   closes it, and the push fails with 141 — after the hook printed
   `=== Pre-push OK ===`. The keepalive that prevents this lived in
   `scripts/setup-worktree.sh`, which has to be run by hand in every
   worktree: so a fresh worktree, or the canonical checkout itself, could
   still lose the connection.

## Idea

Both failures are mechanical, so both get a mechanical answer. The rule
becomes positive — *the exit code matters → write the output to a file* —
and the two cases it protects get a tool: a push wrapper, and a keepalive that
is installed once for the whole repository.

## Decision

- **`scripts/push.sh` is the way to push.** It writes git's output to a file
  (never to a pipe), captures the exit code immediately, prints the tail of
  the log and the code, and exits with git's own code. The log path is unique
  per run and printed, so the whole log can be read afterwards.
- **The keepalive lives in the shared git config.** `just setup-hooks` writes
  `core.sshCommand` with `ServerAliveInterval=30` into the repository's shared
  config, and only when no value is set there: a configured value may carry a
  different binary, a port or an identity, and overwriting it would break the
  push in a way nobody would read as this. The per-worktree copy in
  `scripts/setup-worktree.sh` is gone — the keepalive is harmless in every
  worktree, and a rule that has to be applied by hand in each of them is a
  rule that is missing in one of them.
- **The rule is stated in the positive form.** Summing output for reading
  (`| head`, `| grep -c`, `| wc -l`) is fine and stays; the rule is about the
  commands whose exit code carries the verdict — `git push`, a gate, a test —
  and they write to a file.

## Rationale

- A rule that bans the harmless case gets ignored, because ignoring it costs
  nothing until it costs everything. The rule has to name the case it is
  about, and that case is the exit code.
- The wrapper makes the rule unnecessary to remember: the tool that cannot
  lose the code is the tool to reach for.
- The keepalive belongs to the repository, not to the worktree: `core.sshCommand`
  is harmless everywhere, and `just setup-hooks` already writes the shared
  config (`core.hooksPath`), so it is written next to it — in one place, once.

## The keepalive, measured

The keepalive above was an estimate: the connection "is closed by GitHub
after some minutes of idleness", so a keepalive "should" hold it. Measured on
2026-10-23, on the real remote, with a 13-minute idle period (780 s) between
the connection and the command that used it — twice, once with
`ServerAliveInterval=30` and once without:

| connection | result |
|---|---|
| plain `ssh` | exit 124 (the timeout fired), after 780 s of idle |
| with `ServerAliveInterval=30` | exit 124 (the timeout fired), after 780 s of idle |

**Both connections survived thirteen minutes of idleness.** The keepalive
buys nothing at that interval, which means the 141 this ADR was written
about is not a plain idle timeout: an idle SSH connection to GitHub was
closed by neither side during the whole measurement.

So the keepalive stays — it is one line, it is harmless, and it removes a
class of failure whose cause was never established — but the ADR no longer
claims a mechanism it did not measure. What the measurement rules out is the
easy explanation ("GitHub closes idle connections after N minutes"): on this
remote, it does not.

The 141 that was measured had a different context, and it is now explained
by [2026-10-23-a-test-fixture-must-not-reach-the-developers-repository.md](2026-10-23-a-test-fixture-must-not-reach-the-developers-repository.md):
the `core.sshCommand` in the config at that moment was not the keepalive at
all — it was `ssh -p 2222 -i /home/me/key`, a **test fixture** that a test
had written into the shared config, pointing at a port and a key that do not
exist. The connection was not closed by an idle timeout; it was never
established.

## Consequences

- `scripts/tests/test_setup_worktree.py` now holds the worktree script to what
  it must *not* do (set no `core.sshCommand` of its own) and to what it must
  leave alone (an existing value).
- The keepalive reaches a worktree that was created without running
  `setup-worktree.sh` — the case that made the per-worktree copy unreliable.
- `AGENTS.md` says "the exit code matters → write to a file", names
  `scripts/push.sh`, and points at `just setup-hooks` for the keepalive.

## Links

- `scripts/push.sh` — the push that keeps git's exit code
- `justfile` — the `setup-hooks` recipe that writes the shared keepalive
- `scripts/tests/test_setup_worktree.py` — the worktree script's new contract
- `AGENTS.md` — the rule, in the positive form
