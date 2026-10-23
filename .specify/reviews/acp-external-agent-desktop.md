---
change: acp-external-agent-desktop
verdict: pass
date: 2026-10-23
pr: https://github.com/gazon1/sing/tree/feature/the-external-agent-is-hosted
reviewed: 277e4d17b899cdf7c76c7e92e7f18738135f8a1d
documents: sha256:9590dcd5898ee5f3f8e702ef4b13d5c7b8459eacf8001396077cf0e6dd37ac1f
---

## What was reviewed

The external-agent screen and the client behind it, on the branch
`feature/the-external-agent-is-hosted`:

- `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/` — the client,
  the launcher, the session operations, the event adapter, the registry.
- `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/external/` — the
  ViewModel and the screen.
- `shared/src/jvmMain/kotlin/com/singularity/todo/core/process/` — the
  subprocess and the process tree.
- The specification, the plan and the decision record for the feature.

The review was a second agent's, given the diff and the decision record rather
than the session that wrote it: an agent reviewing its own diff inherits the
assumptions the diff was built on.

## What was found, and what was done about it

Two findings were blocking. Both are fixed.

**The agent's process was orphaned when the connect was cancelled.** The
process is spawned before the handshake, but the ViewModel only held the
session after `connect()` returned — so a connect that was cancelled (the
reader navigated away, the screen's scope closed) left a third-party process
running with the user's credentials and the application's data directory open,
and nothing else would have closed it.

The session is now held *before* the handshake, a cancelled or failed connect
ends the agent, and a connection whose session was closed while the handshake
was in flight is not reported as connected. A connection that never completed
is killed rather than closed politely: there is no conversation to close.

**A stopped turn could end the next one.** A turn that was stopped keeps
running for a moment — the agent is asked to stop, and the request is a
request — and the reader can send another message while it winds down. The
outcome was written whenever it was unset, so the stopped turn's outcome
landed on the running one: the reader was told a running turn had finished,
and two prompts were in flight on one session.

Every outcome now carries the turn it came from, a turn that is no longer the
current one cannot write to the state, and the next turn waits for the
previous one to end.

Three further findings were fixed:

- The urgent teardown of the process tree consumed the graceful teardown's
  flag, so a tree that had been asked politely once could never be
  force-killed — and an agent that ignores the polite signal outlived the
  session that ended it. The two paths have separate flags now.
- A refusal to begin a second turn was silent. It is a message now.
- `DuplexProcess.close()`'s KDoc claimed it closed the process; it closes the
  pipes. The process is ended by the process tree, and the KDoc says so.

A second review of the fixes found three more, all of them in the same
places, and all three are fixed:

**The screen's teardown still did not escalate.** The urgent path asked the
tree to leave and returned — and the escalation that makes leaving certain
lived in the graceful path, which the screen's teardown never runs. So an
agent that ignores the polite signal (one real bridge does) outlived the
screen that ended it. The urgent path carries the escalation itself now, on
a thread of its own, because the caller is being cleared and has no scope
left to wait in.

**The turn-ownership test did not exercise the guard it was written for.**
The first turn's script waited for the test and then never said anything, so
the turn never emitted a completion and the guard — a turn that is no longer
the current one cannot write to the state — was never evaluated. The test
now releases the first turn *before* the second one starts, so its completion
is waiting when the second turn begins, and the assertion is about that
completion: the running turn is still running, and the stopped turn did not
end it.

**The cancel's teardown waited for the wrong turn.** It re-read the mutable
job field when it timed out, and by then the field held the *next* turn's
job: a teardown that gave up on a turn that was still running, or waited for
one that had not started. The job of the turn being stopped is captured when
it is stopped.

The activity a stopped turn produces is now guarded by the turn it belongs
to as well. The outcome was the visible half of the same bug: a turn that
was stopped kept running for a moment, and what it said on its way out
belonged to that turn, not to the one the reader was looking at.

## What is still open

One finding is deferred, and it is recorded here rather than forgotten: the
client's four mutable fields (transport, protocol, client, session) are not
synchronised, so a `close()` racing a `connect()` could leave a live session
bound to a closed transport. No reachable path does that today — the screen
only closes a session it already holds — so it is latent rather than live. It
is the next piece of follow-up work, and the fix (a lifecycle lock, and a test
for closing during a turn) is where it belongs.

## How the fixes were checked

- The targeted test classes pass, including the two new ones: a connect that
  is cancelled ends the agent, and a stopped turn cannot end the turn that
  follows it. The second one fails when the turn-ownership guard is removed —
  it is the assertion about the running turn that the guard protects.
- The process tree's tests use a process that really ignores the polite
  signal — a shell with `trap "" TERM` — and assert that the graceful path
  still ends it and that the urgent path ends it on its own.
- The decision record and the architecture notes were extended to match, and
  the digest regenerated.
