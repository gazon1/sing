# the-genui-context-hands-out-the-source

Issue: — · Related: `docs/decisions/2026-10-21-the-genui-context-hands-out-the-source.md`

Status: **accepted**
Tags: **text field rewrite contract genui source**

## What

The GenUI renderer stops building its own source. The **data context** builds it and hands
it out whole, and the renderer passes it to the bridge like every other field in the app.

The rewrite contract said: *a field never builds its own source* — the source says who may
rewrite the field, and only the owner knows that. The GenUI renderer was the one documented
exception, and the exception was a hole rather than a fact: the renderer read the value
*and* the server's count of writes from the data context, and assembled a source out of
them. The context already knew everything the source needed.

## Why

Every exception in a contract is a place where the contract is not checked. The rewrite
contract test carried a `buildsSource` flag so that this one row could be checked by a
different code path — which is what a hole looks like when it is written down honestly.

The row now names the **data context** as the owner, like every other row names an owner
that is not the field itself, and the exception disappears from the skill, from the table
and from the test.

## The shape of the fix

- `DefaultDataContext.textSource(path, fallback)` hands out a `BoundTextSource` — the source
  as it is right now and the stream of what it becomes. The context holds the value and the
  server's count, so it is the only component that can say "this value is a rewrite".
- The renderer reads it and hands it to the bridge. It no longer constructs `OwnedText`.
- `SurfaceController.currentServerRevision` is the *reading* the flow cannot give: a caller
  that holds a value needs the count that goes with it.
- The contract test loses its `buildsSource` flag, and the owner check accepts an owner that
  hands the source out from a function.
- A new test pins what the owned source buys: a server rewrite that lands on a value the
  field had already sent replaces the field's text.

## What not to do

- Do not build the source in the renderer again. The renderer answers "who may rewrite this
  field?" every time it assembles one.
- Do not bring the exception back as a flag. If a row needs a different check, the rule is
  wrong, not the row.
- Do not read the value and the count separately any more: they arrive together, in one
  source, so they cannot belong to two different moments.
