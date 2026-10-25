# the-source-says-who-may-rewrite-the-field

Issue: — · Related: `docs/decisions/2026-10-20-the-rewrite-authority-is-a-type.md`

Status: **accepted**
Tags: **text field rewrite contract types**

## What

The answer to "who may rewrite this field?" moves from a **registry** to a
**type**.

The contract was published in two places — a table inside
`TextFieldRewriteContractTest` and the rewrite table in the text-field skill —
and the test compared them by reading the source text of every call site. The
code was checked against the table; the table was the place the answer lived.

The bridge now takes a **source**, and the source's type says who may replace
the value:

- a **static** source says the upstream replaces the value only with one the
  field never produced — it loads it once, or clears it;
- an **owned** source says the upstream owns the value and may replace it with
  anything, and it carries the count of the replacements.

The counter is no longer a parameter of the bridge. It is a property of the
source, built by the component that owns the value.

## Why

Three failures were possible under the registry, and all three were silent:

- a field whose upstream owned the value passed no counter, and the rewrite was
  dropped when it landed on a value the field had produced;
- a field passed a counter that never moved — `revision = 0` — and the registry
  said it was fine, because a constant is a number;
- a field forgot the whole question, because the parameter that carried the
  answer had a default.

The create-mode task editor was the standing proof. `TaskTitleRow` and
`TaskDescriptionField` are shared by the create screen and the detail screen.
The detail screen's upstream owns the value and needs the counter; the create
screen's upstream does not. The registry had one row per file, so it could not
say both — and the create screen silently used the detail screen's counter, a
frozen `0`.

## The shape of the fix

- The bridge takes a source. There is no `revision` argument to freeze, to
  forget, or to default.
- The source's type is the answer. The two kinds are unrelated types, and the
  bridge has an overload for each, so an owned value cannot be wired as if it
  were static.
- The owner builds the source; the field passes it through. A field that built
  its own source would be answering a question it cannot know the answer to.
- A component that serves two upstreams takes the base type and reads the kind
  off the value. The task editor is the one such component, and the bridge's
  internal overload is the one place the kind is dispatched.
- The table stays, as the human-readable index of the same answer. The contract
  test keeps comparing it with the code, and now also checks what the types
  cannot: that the owner declares a source of the claimed kind, that an owned
  source's counter is declared *and* advanced, and that a field site does not
  build its own source.

## What not to do

- Do not put the counter back as a parameter. A parameter can be frozen at the
  call site; a property of the source cannot.
- Do not give a static source a count. A field whose upstream never rewrites
  with a field-produced value does not need one, and a count that never moves
  is a claim the code does not honour.
- Do not let a field build its own source. The source is the answer, and only
  the owner knows it. The GenUI renderer is the one documented exception,
  because its owner is a server.
- Do not drop the echo guard to make a rewrite land. The guard is what stops
  the field's own push from replacing its own text.
- Do not keep the registry as the place the answer lives. It is the index now:
  the code says the answer in its types, and the index says it in prose.
