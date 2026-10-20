---
title: Tool-call records are best-effort and payload-free
date: 2026-10-20
status: accepted
tags: [mcp, observability, tools]
---

# Tool-call records are best-effort and payload-free

## Context

The toolbox grew to 39 tools, each with a description, a catalogue entry, a place in the
documentation and a place in two DI lists. None of that said whether a tool was *used*.

What was recorded was the wrong thing. `llm_usage` holds one row per LLM call — tokens,
cost, duration — and the tool name in it is `chat_generate` or `chat_stream`, because the
recorder sits on the *port*, not on the tool. The write tools record nothing at all. So
"which tools do agents actually call, and which of them fail?" had no answer, and the
answer had to be a guess.

The guesses were expensive in a specific way: a tool that nobody calls is either dead
weight or a tool whose name nobody understood, and the two need opposite answers. Guessing
wrong means deleting a tool that was doing its job.

## Idea

- **A.** Record every call with its arguments and its result. The most useful record, and
  the one that turns a local log into a copy of the user's data.
- **B.** Record every call with a code and a duration, and accept that some calls are
  lost.
- **C.** Do not record anything; keep the toolbox small and judge it by review.

## Decision

**B.**

**The event.** `ToolCallEvent` carries: the schema version, the event kind, the timestamp,
the source (`MCP` or `IN_APP`), the tool name, the outcome (`OK`, `BUSINESS_ERROR`,
`INTERNAL_ERROR`), an error **code**, the duration, the session id and the client name.

**No payload.** Not the arguments, not the result, not the text of an exception. An
exception message can carry anything, and a record outlives the session it describes. The
error is a *code* — `validation`, `not_found`, `conflict`, `unauthorized`, `internal` — and
the caller that knows what its failures mean supplies it. A closed set of outcomes, because
a report that has to parse free text to know whether a call worked is a report nobody
reads.

**Where the measurement happens.** The MCP server measures at its **dispatch boundary**:
the registrar already owns the call, the arguments and the outcome classification, and
wrapping every tool there would break the `SimpleTool` cast its decoder relies on. The
in-app agent uses a **decorator**, because the tool itself is the only place that sees the
call. One event schema, two instrumentation points — deliberately, because a shared schema
does not require shared mechanics.

**The guarantee, stated honestly.** Best-effort: one record per *completed* attempt,
including the failed ones. Not a durable exactly-once log — a process killed mid-write
leaves a truncated line, and a write that fails leaves no line. The recorder counts what it
lost, and a **write failure never changes the outcome of the call**: an observation that
breaks the observed is worse than no observation.

**Storage.** Append-only JSONL, one file per process per day, in the application's own
data directory — owner-only permissions on the JVM, app-private storage on Android. A file
per process removes the question of interleaving between two processes that share the
directory; the reader merges them.

**The report is a local diagnostic, not a gate.** CI has no local telemetry, so a gate
over this data could only check the age of a file CI never writes. The report separates the
two sources — what happened, and what the toolbox was — and says "not observed in this
window" rather than "unused", because the absence of a record is not proof that no call
happened.

## Rationale

The measurement is for tuning the toolbox, and tuning needs counts, not transcripts. A
count of which tools are called and how often they fail answers the question; a transcript
of what was said does not, and it costs the user their data to keep.

The "best-effort" wording is deliberate. A promise of exactly-once durable logging would be
a promise the file format cannot keep: a process can die between the call and the write,
and a line can be half-written. Naming the limitation is what makes the number usable —
a report that says "12 calls" when 12 calls happened and "12 calls" when 12 of 15 were
recorded looks the same to the reader.

## Consequences

- "Which tools does nobody use?" has an answer, and the answer names a window.
- A tool that fails often can be found before an agent trips over it.
- The records reveal *activity* — which tool, when, from which client — even without a
  payload. That is why they live in the application's own directory, with restrictive
  permissions, and with a retention window.
- A write failure is visible in the dropped-record counter and invisible to the caller.
- The in-app agent's tool usage becomes measurable for the first time.

## Links

- `shared/.../core/observability/ToolCallLog.kt` — the event, the port and the recorder
- `shared/.../core/ai/RecordingTool.kt` — the in-app decorator
- `mcp-server/.../mcp/ToolRegistrar.kt` — the dispatch boundary
- `scripts/mcp-toolbox-usage.py` — the report
