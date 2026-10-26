# The refusal is reachable from the screen

Issue: — · Related: `docs/decisions/2026-10-23-acp-client-and-agent-activity.md`,
`openspec/changes/acp-external-agent-desktop` (REQ-EXTAG-007)

## Why

REQ-EXTAG-007 says the application **SHALL** refuse to begin a second turn while one is in
progress, and **SHALL** say why. Half of that was true and half of it was unreachable.

The ViewModel refused the second turn and emitted a message that said so — but the screen
disabled the input for the whole duration of the running turn, so the reader could never
press Send, so the refusal never happened, and the message never appeared. The rule was in
the code and could not be met by a person using it.

Two turns interleaved on one connection produce an answer neither turn asked for, so the
refusal itself is right. What was wrong is that the *screen* made the same decision a second
time — with a different mechanism (a disabled field) and a different outcome (silence),
instead of letting the one place that owns the rule say no in words.

This is the same defect shape the feature has already been bitten by: a behaviour verified
only through a test double, while the wiring the application actually uses took another
branch. The ViewModel test asserted the refusal; the screen made it unreachable.

## What Changes

- The external-agent screen keeps the input **enabled while a turn is running**, so a second
  message reaches the ViewModel and is refused there — with the message the ViewModel already
  emits, shown through the notification host that is already wired.
- The Stop control beside the field stays what ends a running turn; the input being enabled
  is not a second way to send two turns, it is the way the refusal is delivered.
- The ViewModel test now asserts the refusal's **message**, not only that the second prompt
  never reached the session — the "say why" half was previously unasserted.
- A screen test drives a running turn and asserts the input is enabled and the Stop control
  is present.

## Impact

- Affected specs: `ai/external-agent` — REQ-EXTAG-007 (no text change; the requirement is
  delivered where it was promised)
- Affected code: `feature/ai/external` (the screen, its ViewModel test, and a desktop screen
  test)
- What is explicitly **out of scope**: the run of a second turn is still refused, not queued;
  the built-in chat's silent `isLoading` guard is left alone (it has no requirement to say
  why, and changing it would be a different change)
- Supersedes nothing. Closes the "say why" half of REQ-EXTAG-007.
