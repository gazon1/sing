---
title: "The count-less read says it is count-less"
date: 2026-10-21
status: accepted
tags: [architecture, genui, text-fields]
---

# The count-less read says it is count-less

## Context

A GenUI surface holds a data model. A field bound to a path in it needs two things: the
value at that path, and whether the value was replaced from outside. The second one is a
count — without it, a rewrite that lands on a value the field had already sent is
indistinguishable from a lagging upstream, and the bridge's echo guard drops it.

The count travels with the value: `textSource(path, fallback)` hands out the value and
the count of the server's writes to that path, in one object.

The context has a **second** read of a path, and it is the one that has been used
everywhere else:

```kotlin
fun value(path: UiPath): Flow<JsonElement?> = controller.value(surfaceId, path)
```

It returns the value and nothing else. For the values it is used for — a checkbox's flag,
a due date on a card, a project's name on a card — that is exactly right: they are not
text, and nothing rewrites them from outside.

The trouble is the name. `value` reads like *the* way to get a value, and it is the wrong
way for the one value whose whole contract depends on the count travelling with it. A
renderer that reads a bound field's text through `value` gets the text and loses the
count, and nothing says so: the field still shows the server's value, until the server
rewrites the field to something the field had already sent. Then the rewrite is dropped
as an echo, and the two disagree without either of them speaking.

The contracts that exist did not cover this hole:

- "a field site does not build its own source" catches a renderer that *assembles* a
  source — but a renderer that reads the path through `value` and hands the *text* to a
  local field has not built a source at all;
- "the rewrite table's rows are complete" catches a site that stops being bridged — but
  the renderer that reads the path *and* the count from two different places is still
  bridged, and still holds the count by luck.

## Idea

Name the count-less read for what it does not carry, and hold the line that a text field
does not use it.

## Decision

**The count-less read is `valueWithoutCount`, and a field that bridges a surface path
reads the path through `textSource`.**

- **`DefaultDataContext.value(path)` is renamed `valueWithoutCount(path)`.** The five
  call sites are a checkbox's flag and four values on a card — none of them text. The
  rename says, at the place where the choice is made, what the read does not carry.
- **`TextFieldSourceContractTest` holds two rules.** A file that bridges a field bound to
  a surface path reads that path through `textSource`, and never through
  `valueWithoutCount`; and the source the context hands out carries the count — its
  current value is an `OwnedText`, and an `OwnedText` declares the count of its
  replacements. The second rule is what keeps the first honest: a source whose text were
  a plain string would satisfy the first rule and break the second.
- **The contract test reads the call from the declaration's text.** Konsist 0.17.3 has no
  call-site API, so this is the same technique the rewrite contract uses for the two
  checks it cannot express structurally. Comments are stripped and only declarations are
  read, so a mention outside a declaration cannot make the rule pass.

## Rationale

- **The name is the diagnostic.** This repository keeps contracts in the shape of the
  code: a count that lives in the source cannot be frozen at the call site; a write that
  is named says what it was. A read that is named for what it lacks says, at the call
  site, that the count is not there — which is the one fact a reader of that call needs
  and cannot get from the type.
- **Two rules, because one is not enough.** "Reads through `textSource`" is a claim about
  a call; "the source carries the count" is a claim about a type. The first can be
  satisfied by a source that lost the count on the way; the second cannot.
- **The rule is narrow on purpose.** It applies to the files that bind a field to a
  surface path, and it says nothing about the count-less read of a value that is not
  text. The read is still there, still correct, and still named for what it is.

## Consequences

- `DefaultDataContext.value` is gone; `valueWithoutCount` is its name. Five call sites
  changed, and the diff is the record of which reads carry a count.
- A renderer of a bound text field has one way to read its path, and that way carries the
  count.
- The GenUI layer's contract is now stated in two places, and both are checked: the
  rewrite table says *who* may rewrite a field, and this test says *how the field reads
  the value*.
- The check is textual, and it says so. A call written in a shape the scan does not
  recognise could slip past the first rule — which is why the second rule is about the
  type the renderer ends up holding.

## Links

- `2026-10-21-the-genui-count-travels-with-the-value` — the count moved into the value.
- `2026-10-21-the-genui-context-hands-out-the-source` — the context builds the source and
  hands it out whole.
- `.agents/skills/singularity-todo-text-field-states/SKILL.md` — the rewrite table and the
  read-path rule.
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/TextFieldSourceContractTest.kt` —
  the contract.
