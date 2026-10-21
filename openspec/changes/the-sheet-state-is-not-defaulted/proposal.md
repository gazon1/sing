# the-sheet-state-is-not-defaulted

Issue: — · Related: `docs/decisions/2026-10-21-the-sheet-state-is-not-defaulted.md`

Status: **accepted**
Tags: **task editor sheets state compose**

## What

The editor's sheets are opened by rows and rendered by a host, and both must be looking
at the same state. The state was made single, but it was still *optional*: the editor
fell back to a state of its own when the caller handed none over.

So the bug that #824 fixed was still available: a screen that opens a sheet and forgets
to hand the state over opens a sheet nobody renders — silently, with the screen looking
perfectly fine.

Now the state is a required parameter of both editors. There is no default, and there is
no second state.

The state also leaves the callback bundle: it is not a callback, it is a thing the editor
depends on, and it now sits in the editor's signature next to the model and the sources.

## Why

- A state with a fallback is two states waiting to disagree. The whole point of #824 was
  that there is one state.
- An omission the compiler catches cannot ship. The failure mode of the old bug was a
  silent no-op; now it is a compile error in the screen that forgot.
- A dependency belongs in the signature. The state is read in exactly one place, and the
  editor's signature did not say the editor's sheets depend on it.

## The shape of the fix

- Both editors take the state.
- The scaffold uses the state it was given; there is no fallback.
- The callback bundle no longer carries it.

## What not to do

- Do not give the state a default. A default is a second state.
- Do not keep the state among the handlers. It is not a handler.
