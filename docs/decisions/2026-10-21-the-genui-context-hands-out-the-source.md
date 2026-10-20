---
title: "The GenUI context hands out the source: the field never builds its own"
date: 2026-10-21
status: accepted
tags: [architecture, text-fields, genui]
---

# The GenUI context hands out the source: the field never builds its own

## Context

The rewrite contract says: **a field never builds its own source.** The source says who may
rewrite the field, and only the owner knows that — so the owner builds the source and the
field passes it through.

Every field site in the app obeys this except one. The GenUI renderer builds its own:

```kotlin
val bound by ctx.value(f.path).collectAsStateWithLifecycle(initialValue = null)
val current: String = (bound as? JsonPrimitive)?.content ?: f.initial
val serverRevision by ctx.serverRevision().collectAsStateWithLifecycle(initialValue = 0L)
val state = rememberBridgedTextFieldState(
    source = OwnedText(current, serverRevision),
    ...
)
```

The reason was recorded as a fact: the renderer's owner is a **server**, and the renderer
is "the boundary where the server's value and the server's count are read". But look at
where the two numbers come from — `ctx.value(f.path)` and `ctx.serverRevision()` are both
the *data context's* observations. The renderer read the value and the count from the
context and assembled a source out of them, which is building the source.

The exception was a hole in the contract, not a fact about the world. It was also the only
hole: the rewrite contract test carried a `buildsSource` flag so that this one row could
be checked differently from all the others.

## Idea

The component that knows the value *and* the server's count of writes is the data
context. It should build the source and hand it out whole, exactly like a ViewModel
hands out the source of a field it owns.

## Decision

**The data context builds the source of a bound field, and the renderer passes it through.**

- **`DefaultDataContext.textSource(path, fallback)` hands out a `BoundTextSource`** — the
  source as it is right now, and the stream of what it becomes. The context holds the
  value at the path and the server's count of writes, so it is the only component that can
  say "this value is a rewrite".
- **The renderer reads it and hands it to the bridge.** It no longer constructs
  `OwnedText(...)`, and the exception is gone: the rewrite table's row for the GenUI
  renderer names the **data context** as the owner, like every other row names an owner
  that is not the field itself.
- **The contract test loses its `buildsSource` flag.** The check "a field site does not
  build its own source" now applies to every row without an exception, and the check
  "the owner declares a source of that kind" accepts an owner that *hands the source
  out* from a function — the context builds it in a private function, because that is
  how a source that changes over time is built.
- **The controller can be read synchronously.** `SurfaceController.currentServerRevision`
  is the *reading* the flow cannot give: a caller that holds a value needs the count that
  goes with it, and a flow cannot be read synchronously.

## Rationale

- **The exception was a hole.** The renderer did not know what the server wrote; the count
  came from the context already, and the value came from the context already. What the
  renderer added was the *assembly* — and the assembly is the answer to "who may rewrite
  this field?", which is the one question a field must not answer for itself.
- **One rule beats one rule plus an exception.** Every exception in a contract is a place
  where the contract is not checked. The GenUI row was checked by a different code path
  (`buildsSource = true`), which is the shape a hole takes when it is written down
  honestly.
- **The context already knew everything.** No new information had to move anywhere: the
  context was already the component that observed the value and the count. The change is
  that it now *assembles* them, instead of letting the renderer do it.
- **The source is built once per path.** The context keeps the source it handed out, so a
  recomposition does not restart the observation and the field keeps the value it has.

## Consequences

- The GenUI renderer no longer mentions `OwnedText`. The rewrite contract's exception
  disappears from the skill, from the contract test and from the table.
- The GenUI layer now speaks the text-field contract: `feature.genui.render` depends on
  `core.ui.textfields` for the type of the source it hands out. That is the dependency the
  contract implies, and it was previously hidden behind a locally built source.
- A field bound to a surface path is now *always* an owned field: the server may replace
  its value with anything, and the count that says so comes with it.
- The new test pins the behaviour the owned source buys: a server rewrite that lands on a
  value the field had already sent replaces the field's text. A static source would fail
  that test, which is the point of the two kinds being two types.
- The count and the value now arrive together, in one source. Before, they were two
  independent observations, and a value could in principle arrive with the count of a
  different moment.

## Links

- `2026-10-21-the-contract-is-published-once` — the contract is published once, in the
  skill's tables.
- `2026-10-21-the-field-shape-is-derived-not-read` — the shape is derived from the
  imports.
- `2026-10-20-the-rewrite-authority-is-a-type` — the decision that moved the answer into
  the type of the source.
- `.agents/skills/singularity-todo-text-field-states/SKILL.md` — the rewrite table, which
  no longer has an exception.
