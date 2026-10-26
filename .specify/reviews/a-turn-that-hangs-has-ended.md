---
change: a-turn-that-hangs-has-ended
verdict: pass
date: 2026-10-26
pr: https://github.com/gazon1/sing/pull/1182
reviewed: 0c12c6651000c77f3290c9237137b908e231118f
documents: sha256:9b5be47ed229b9af8d71111fd33d976cb8f8368894466b67c7d1209b7d0f1094
---

## What was reviewed

The change: `openspec/changes/a-turn-that-hangs-has-ended/` (proposal, design, tasks,
`skip_specs`), the ADR `docs/decisions/2026-10-26-a-turn-that-hangs-has-ended.md`, and the
code it describes — `AcpTurnRunner`, `AcpExternalAgentViewModel`, `AcpPermissionBroker`,
`AcpAgentConversation` — against FR-040.

The reviewer was a separate agent with read-only access, given the branch's diff (against
`feat/the-second-turn-is-refused-on-screen`, because this change is stacked on it) and the
artifacts. It ran the gates itself and was asked for defects with a location, not a summary.
The review took two passes, and the change was fixed between them.

`reviewed:` is the commit the second pass's findings were fixed against — the branch head at
that moment, `0c12c6651000c77f3290c9237137b908e231118f`. It is a pointer, not a claim the gate
can check: the pull request lands by squash, so the commit is not an ancestor of `main`.
`documents:` is the digest of the change's contract documents — the proposal and the design,
there being no spec delta (`skip_specs: true`, with the reason in `.openspec.yaml`) —
recomputed by `python3 scripts/check-review-verdicts.py --digest a-turn-that-hangs-has-ended`.

## The first pass: FAIL, one blocker

The blocker was the one thing a change about sessions dying must not get wrong.

**The reader's Stop could be reported as a lost session.** `cancel()` launched a coroutine
whose first act was `openSession.cancel()` — a suspension — and only afterwards recorded
`Cancelled`, which is *also* the flag the turn reads to tell "the reader stopped this" from
"the session died". An agent that obeyed quickly closed its stream inside that window, so the
turn saw no completion and no recorded stop, called `onSessionLost`, and forced a reconnect —
punishing the reader for pressing Stop. The ADR said the cancel "records its own outcome
first"; the code recorded it second. Fixed by recording synchronously, before anything
suspends, and the test now drives the race (the fake's `cancel()` is held open past the
stream's close) so it fails if the order regresses.

Should-fix findings, all closed: a lost session left a permission request on screen,
answerable under a "connection failed" banner; the producer coroutine dropped the cause of a
transport `CancellationException`; the event buffer was unbounded; the loop waited a further
idle window after a completion; `AcpAgentConversation.close()` cleared `session`
unconditionally (so a connect landing during the close was wiped); `AcpPermissionBroker.pending`
was read across coroutines to decide whether a turn is waiting on the reader; and an event
arriving exactly as a window closed was dropped.

## The second pass: FAIL on two defects the fix introduced, both closed

- **A throw escaped the intent.** Passing a built flow to the runner moved the construction of
  `prompt()` onto the screen's intent path, outside the turn body's `try`. The client builds
  the flow eagerly and throws when it is no longer connected — reachable exactly on the path
  this change owns (Stop on an agent that ignores cancellation terminates the client), so the
  next Send threw after the spinner was already up, out of `onIntent`. The runner now takes a
  *factory* and builds the flow inside the turn, so that throw is an ordinary failed outcome.
- **The stop flag raced.** `outcomeRecorded` is written synchronously by `cancel()` on the
  reader's thread and read on the turn's dispatcher; a stale read would reproduce the original
  blocker. It is `@Volatile` now.

The second pass also confirmed the nine earlier fixes were genuine rather than cosmetic, and
that the re-armed deadline cannot spin (each expiry costs a full window while a request is
pending) and that a `Finished(Failed)` completion is correctly not a session loss.

Notes recorded and deliberately not acted on: a hung agent is *closed*, not killed (a process
that ignores the close is ended by the ViewModel's `terminate` on dispose and by the cancel
path's grace), and the two gate commands in `tasks.md` carry `--rerun` because without it
Gradle reports UP-TO-DATE and runs nothing.

## How it was checked after the last fix

- `./gw :shared:jvmTest --tests "*AcpExternalAgentViewModelTest*" --rerun` — pass: the idle
  window ends a turn as failed with the reason; a session that dies mid-turn is marked
  disconnected and keeps the answer; a stopped turn is not a session loss (driven with the
  gated cancel); a completion that is *failed* is not a session loss; a session that cannot
  build its stream fails the turn instead of throwing; a lost session clears the request on
  screen; and the pre-existing permission tests (including the five-minute decline) still pass.
- `./gw :desktopApp:test -Ptest.tags=slow --tests "*AcpExternalAgentScreenTest*" --rerun` —
  pass: a lost session shows the reason and offers Connect again.
- `python3 scripts/lint-changed.py --base feat/the-second-turn-is-refused-on-screen` — clean.
- `openspec validate a-turn-that-hangs-has-ended --strict` — valid.

## Links

- ADR `docs/decisions/2026-10-26-a-turn-that-hangs-has-ended.md`
- OpenSpec: `openspec/changes/a-turn-that-hangs-has-ended/`
- FR-040 and US3 scenario 8, `specs/014-acp-client-agent-activity/spec.md`
- The gate `scripts/check-review-verdicts.py`
