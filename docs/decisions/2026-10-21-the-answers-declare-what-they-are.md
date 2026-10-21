---
title: The answers an agent reads say what they are
date: 2026-10-21
status: accepted
tags: [mcp, tools, contracts, report, search]
---

# The answers an agent reads say what they are

## Context

The toolbox surface now declares itself well: the catalogue owns the tool list, the
payload may only grow, the contract version is published. Three answers an agent reads
still did not say what they were.

**The tool-call record's vocabulary.** The writer stamps `outcome` with an enum name
(`OK`, `BUSINESS_ERROR`, `INTERNAL_ERROR`); the report read `ok` and counted everything
else as an error. The comparison test caught it: the report had been saying every tool
in the toolbox failed, every time, and saying it confidently. The reader was fixed to
take the writer's spelling — but the *vocabulary* was still declared twice, on two
sides, with nothing saying the two must agree.

**The outcome that is not in the vocabulary.** The reader's rule was "ok, or an error".
So a future outcome — or a typo — became a confident statement about a call that
failed. A reader that does not know the vocabulary cannot tell an error from a word it
has not read.

**The search's answer.** `search_docs` answers "where is the rule about X?" with an
envelope: `available`, `hits`, `warnings`, `error`. No version. The corpus policy had
just moved the directories around underneath it, so the answer was changing shape
without saying so.

**The version, too late.** `describe_toolbox` carries the surface contract version, but
a client learns it only by *calling* a tool. The MCP `initialize` handshake happens
first, and it has a free-text field the client reads on connecting.

## Decision

**Every answer an agent reads declares its version, and its vocabulary is declared
once.**

- `config/docs/tool-call-records.json` declares the record's schema version, its
  vocabulary (outcomes, sources, events) and its retention. The Kotlin writer is
  checked against it by a test; the Python reader reads it. The reader is now strict:
  an outcome the policy does not declare is **unknown** — counted separately and
  named — and not an error.
- The report's default window is the **retention**, not "everything": the writers
  delete what is older, so the span in which records still exist *is* the window, and
  the report says which span it looked at.
- `SearchEnvelope` carries `schemaVersion`, owned by the envelope itself. Additive, so
  a client that ignores it is unaffected.
- The surface contract version is in the `initialize` response's `instructions`, so a
  client learns which contract it has connected to *before* the first call.

## Rationale

The version is worth having only where a reader can act on it. An agent that reads a
field it does not understand has two possibilities — the server is newer, or the agent
is misreading — and only the version tells them apart. Publishing it at `initialize`
time is the difference between a client that can refuse to use a shape it does not know
and one that discovers it halfway through a task.

Strictness about the vocabulary is the same move, one level down. The reader's old
rule — anything that is not "ok" is an error — was a rule about *everything*, and it
was wrong about everything it did not recognise. "Unknown" is a category the reader can
report without claiming to know what it means.

The policy file is the arrangement the ADR corpus already uses: one declaration, read
by both sides, compared by a test. It is deliberately *not* read by the writer at
runtime — the writer is Kotlin in `commonMain`, and a file read there would need the
platform's file-system port for a policy that does not change between runs. The test
does the reading instead, and it is the test that would notice a rename on either
side.

The window is the retention because "all records" was never a window: the writer
deletes what is older than thirty days, so the report's "all" was already a bound it
did not name.

## Consequences

- The report names the outcomes it cannot read, and the records it skipped, and the
  span it looked at. Nothing is silently folded into a total.
- The writer's vocabulary has one declaration. A rename on either side fails
  `ToolCallRecordPolicyTest` instead of turning every call into an error.
- The search's answer says which version of the answer it is.
- A client learns the surface contract version when it connects.
- The policy file is a new thing to keep in step — one file, two readers, one test.

## Links

- `config/docs/tool-call-records.json` — the vocabulary, the version and the retention
- `scripts/mcp-toolbox-usage.py` — the strict reader
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/observability/ToolCallRecordPolicyTest.kt` —
  the writer against the policy
- `adr-core/src/commonMain/kotlin/com/singularity/todo/feature/ai/tools/AdrSearch.kt` —
  the envelope's version
- `mcp-server/src/main/kotlin/com/singularity/todo/mcp/Main.kt` — the version at
  `initialize`
- ADR `2026-10-21-a-payload-says-which-version-it-is` — the versions of the surface
