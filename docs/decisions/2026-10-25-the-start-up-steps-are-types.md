---
title: "The start-up's steps are types, and the order is a list"
date: 2026-10-25
status: accepted
tags: [mcp, architecture, startup]
---

## Context

The tool server's start-up was one file of five hundred and eighty-nine lines with
fifteen functions in it: the container, the dependencies, the database, the profile
check, the profiles, the configuration, the server, the shutdown hook, and the two
decisions that could refuse.

The order those steps run in is a contract, and it is a contract with three things:

- the shutdown hook, which closes the server, so it has to be installed after the
  server exists;
- the refusals, which have to happen before the container is started, because a refusal
  after it leaves a half-started process behind;
- the client, which is told one thing while the process leaves with another.

But only the sequence of statements knew that order. The twelve steps were written down
in the architecture notes, and the code's order was a side effect of how the function
happened to be written — nothing said "the shutdown hook comes last", and nothing said
"the profile is refused before the container is started". A step that moved up or down
would have changed what the client is told, and nothing would have said so.

`#994` asked for exactly this: "Give the tool server's start-up an order that is
written down, and a name for each step".

## Idea

A step is a type, and the order is a list of the steps.

## Decision

1. **Each step is a type, in a file of its own.** A step has a name — the name the
   order is written down in — and the steps that have something to decide say it as a
   value rather than as an exit.

2. **The order is written down once.** `ToolServerStartupOrder` holds the list, and the
   runner walks it. The runner does not repeat the order: it does not call the steps in
   sequence, it walks the list that names them.

3. **The steps share a context, and the context says what each step produces.** A step
   reads what the steps before it produced and writes what it produces; the fields are
   nullable because the order decides when each of them is filled. A step that reads a
   field the order has already filled reads it with `checkNotNull`, so an order that was
   edited fails at the step that was broken rather than skipping the step.

4. **Nothing about the behaviour changes.** The order, the log lines, the JSON-RPC codes
   and the exit codes are exactly what they were. A refusal is refused at the same
   point, with the same code, the same message and the same exit code, and the log
   lines are the same lines under the same tag.

5. **The order is pinned by a test.** A test that asserts the list of titles is what
   says the order did not change by accident — and a test that asserts the order builds
   the server is what says the runner's own check cannot fire.

## Rationale

**Why a type and not a function.** A function has a name too, but a list of function
*references* is a list of things to call, and the connection between the list and the
calls is the reader's job to hold in their head. A step that is a type carries its
behaviour with it, so the list *is* the order and the runner is the only thing that
knows how to walk it.

**Why a file each.** The file is the unit a reader opens. One file with ten steps in it
is the thing that was wrong: five hundred and eighty-nine lines, and a reader who
wanted to know what the refusal did had to find it in a file about the server.

**Why the context rather than parameters.** The steps are a sequence, not a call
graph: each one needs what the ones before it produced, and passing eleven parameters
through ten calls says the same thing in a worse way. The context says what the
sequence produces, in one place, and the fields say which step fills each of them.

**Why the behaviour is pinned to the letter.** The client is told one thing and the
process leaves with another, and both are contracts with whoever runs the server. A
refactor that "roughly preserves" them is a refactor that breaks one of them without
saying so.

## Consequences

- The start-up is seventeen files instead of one, and the architecture notes carry the
  file map.
- The runner is four lines long, and the order is a list a test can read.
- A step that needs a product of an earlier step reads it from the context, and the
  context's KDoc says which step writes each field.
- The runner's own check — that the server exists when the sequence ends — is backed by
  a test that says which step builds it, so the check cannot fire without a test
  failing first.
- The start-up's refusals are still values, and the entry point is still the only thing
  that acts on them.
- The entry point's own steps — reserve the channel, parse the arguments, serve the
  frames — are not the start-up's, so they are not in the list.

## Links

- Issue [#994](https://github.com/gazon1/sing/issues/994) — the order that is written
  down, and a name for each step
- ADR `2026-09-05-koin-suspend-bridge` — the container the graph is built in
- ADR `2026-09-07-mcp-stdio-blocking-lifecycle` — how the frames are served
- `docs/architecture/mcp-server.md` — the twelve steps, and the file map
- `shared/src/jvmMain/kotlin/com/singularity/todo/mcp/ToolServerStartupOrder.kt` — the
  order
- `shared/src/jvmMain/kotlin/com/singularity/todo/mcp/ToolServerStartupStep.kt` — what a
  step is
- `shared/src/jvmTest/kotlin/com/singularity/todo/mcp/ToolServerStartupOrderTest.kt` —
  the test that pins it
