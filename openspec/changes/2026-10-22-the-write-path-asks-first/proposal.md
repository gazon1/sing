# The write path asks first

Issue: — · Related: `docs/decisions/2026-10-22-the-write-path-asks-first.md`

## What

Let the chat *propose* a change to a task instead of making it.

The write tools write. `update_task` changes a task the moment the model calls it, and
the model called it because the model thought it should. Sometimes that is right — the
user said "rename it to X" and the model did it. Sometimes it is not: the model
decided, mid-conversation, that a task ought to have a different due date, and the
change landed without anyone having looked at it.

The machinery for "a change nobody has agreed to yet" already exists in the app:
proposals, a proposal repository, an apply use case that validates before it claims,
and a rejection fingerprint that stops the same change being proposed twice. It is
used by the delete tools. It is not reachable from the chat.

## The change in behaviour

- A new tool, `write_task_proposal`, **proposes** a change to a task and reports the
  proposal it made. It does not apply anything.
- The proposal is a real proposal: it shows up in the app's confirmation screen with the
  rest, it is decided item by item, and the apply path is the one the app already
  uses.
- The tool says what it did — "proposed", not "changed" — and its answer carries the
  line the user will read.
- An operation that is not one of ours, a missing value, a missing item, or a task
  that does not exist, is refused with a message that says which.
- The four delete tools build their proposals through the same builder the new tool
  uses, so the proposal machinery is written once. Their proposals also carry a
  fingerprint now, which they did not before — the rejection guard was inert for
  them.

## What is not in this change

- The other write tools still write. Changing them would change what their names
  mean, which is a change to the server's contract, and the contract change is its
  own piece of work with its own version bump.
- The confirmation screen is not new. It exists; the proposals it shows now include
  the ones the chat makes.
- Nothing about how a proposal is applied changes. The apply use case already
  validates before it claims, and it is not touched.

## Why now

Because the chat can now call tools, and the first thing it will do with a tool that
can change a task is change a task. A write path that asks first is what makes the
chat safe to let loose on someone's actual data — and the machinery for it is already
in the app, unused by the chat.
