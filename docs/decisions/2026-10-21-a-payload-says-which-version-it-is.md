---
title: A payload says which version it is
date: 2026-10-21
status: accepted
tags: [mcp, tools, contracts, report]
---

# A payload says which version it is

## Context

The agent-facing surface has two payloads that a reader parses without the server's
help: `describe_toolbox` — what the toolbox is — and the registry snapshot the server
writes at startup — what it was, and which flags matched nothing.

Neither said which version it was. The reader had to infer it from the shape, and the
reader that inferred it wrong did so silently. The report did exactly that: it read the
snapshot's tool list as a list of strings, because that is what the report's own tests
wrote, while the server wrote a list of objects. The report's tests agreed with the
report, so the mismatch stayed invisible until somebody read a real snapshot — and the
symptom was not an error. It was a report that said nothing.

The same gap is on the other side of the wire: `describe_toolbox` publishes a
fingerprint of the catalogue and of the active toolset, so a client can tell *that*
something changed, but not *whether the shape it knows is the shape this server speaks*.
A client that read a renamed field would conclude that a tool does not exist.

## Decision

**Every payload an agent reads declares a version, and the reader is strict about the
version it knows.**

- `describe_toolbox` carries `toolset.surfaceContractVersion`, owned by the catalogue
  (`McpToolCatalog.SURFACE_CONTRACT_VERSION`), because the catalogue is what the surface
  *is*. The export of the surface (`config/docs/tool-surface.json`) carries the same
  number, so a reader of the export knows which contract it is looking at.
- The registry snapshot carries `schemaVersion`. Version 1 is the shape the server writes
  today; a snapshot without a version was written before the version existed, and its
  lists are strings — that is version 0.
- A reader reads the shape its version declares and nothing else. A version 1 list is
  read as objects, a version 0 list as strings. An entry that fits neither is *counted*,
  not guessed at. A version the reader does not know is not read at all, and the report
  says so instead of presenting an empty list as "nothing is registered".

The version is bumped when the shape changes in a way a client can observe: a renamed
field, a removed field, a changed meaning. A new field is not a bump — the payload may
only grow, and that rule was already written down where the payload is built.

## Rationale

A reader that accepts whatever it finds cannot say what it read. That is the whole
defect: not that the report crashed, but that it produced a plausible answer from data
it had misread.

Versioning the shape is what lets the reader be strict. "A string or an object, take
either" was a compromise that made the reader's answer unfalsifiable — the same
compromise the *tests* made, which is why they agreed with each other and with the bug.

Putting the version in the payload rather than in the documentation matters for the same
reason the fingerprint does: the reader is a program that may run against a server it
was not written for. It cannot ask.

The contract version and the snapshot version are different facts and are owned
differently. The first is the contract with a client that may be older than the server;
the second is the contract with a reader — the report — that outlives the server that
wrote the file. Confusing them would mean bumping the client's contract every time the
report needed to know a shape changed.

## Consequences

- The report is strict about the versions it knows, and says what it could not read: the
  entries that did not match their version's shape, and the versions it does not know.
- The old tolerance is gone. A snapshot in an unexpected shape no longer parses into
  "no tools" — it is reported.
- `describe_toolbox` grew a field. It is additive, so a client that ignores it is
  unaffected, and a client that reads it can tell a newer server from an older one.
- The report and the runtime are compared by a test that runs both: `ToolboxRuntimeContractTest`
  writes a snapshot and the records with the production writers and runs the production
  report over them. The two languages agree in a test, not in a hope.

## What the comparison found

The first run of that test failed — not on a version, but on a vocabulary. The writer
writes the outcome as `OK` (`ToolCallOutcome.OK`), and the report compared against
`ok`, so every real call was counted as an **error**: the report said every tool in the
toolbox failed, every time, and said it confidently. The report's own tests agreed with
the report, because they wrote the records the way the report read them.

That is the defect this whole wave is about, one level down: two sides of a contract
that had never been compared, and a report that was wrong in a direction nobody could
see. The reader now takes the writer's vocabulary, case-insensitively, and the
comparison that found it stays in the build.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/McpToolCatalog.kt` — the
  contract version's owner
- `mcp-server/src/main/kotlin/com/singularity/todo/mcp/RegistrySnapshot.kt` — the
  snapshot's schema version
- `scripts/mcp-toolbox-usage.py` — the strict reader
- `mcp-server/src/test/kotlin/com/singularity/todo/mcp/ToolboxRuntimeContractTest.kt` —
  the two sides, compared
- ADR `2026-10-21-the-toolbox-once-more` — the rule that the payload may only grow
