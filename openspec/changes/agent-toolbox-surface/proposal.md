# agent-toolbox-surface

## What

Give the agent that already works with this project an honest surface:

- the toolbox it is connected to — which tools exist, which are switched off, which build;
- the knowledge of the project — searchable through the API it already writes to;
- a record of what it actually called.

Three results, three slices, one change.

## Why

The toolbox was described in four hand-maintained places, and the descriptions had
drifted: the catalogue carried a second copy of every tool description, and 32 of 39
copies differed from the text the agent is actually told. Five described a *different
capability*, and four `delete_*` tools were documented as permanent deletes while the
tools create a proposal that needs confirmation. Nothing diffed any of it.

The knowledge was worse: 692 decisions, 149 skills and 13 specifications were reachable
only through a shell, so an agent asking "what did we decide about the sync conflict?"
had to leave the tool surface and grep a repository. The skills told it to do that, which
is a missing tool rather than a working workflow.

And nothing measured the third thing. `llm_usage` records LLM calls, labelled
`chat_generate`; the write tools record nothing. So "which tools do agents use, and which
of them fail?" had no answer, and a tool that nobody calls — dead weight, or a tool whose
name nobody understood — could not be told from another.

## How

**Slice 1 — a trustworthy surface.**

- The tool class owns its description; the catalogue no longer carries a copy.
- The catalogue owns the declared surface: name, class, domain, annotations.
- A runtime test resolves the real Koin list on the JVM and on Android and compares it
  with the catalogue — names, descriptions, no duplicates.
- The drift gate compares the catalogue with the tool classes, and the ADR CLI surface
  with the catalogue, through an explicit command→tool mapping.
- The MCP server honours the remote-config kill switch: a disabled tool is never
  registered, so it is absent from `tools/list`. The rule it applies is the same one the
  in-app agent applies — and the rule was fixed first, because neither consumer had
  actually honoured the flags until then (design.md, F16).
- `describe_toolbox` reports the build version, the checkout, the two fingerprints and
  the configuration status, from the one snapshot the registrar built.

**Slice 2 — searchable knowledge.**

- `search_decisions` — the structured question, with the status of every hit.
- `search_docs` — the scattered question, scoped to decisions, architecture, skills or
  specs.
- One engine, one corpus-rule owner (`AdrCorpusConfig`), and an envelope that separates
  "nothing matched" from "nothing was searched" from "the call was wrong".
- `singularity-adr search` — a thin adapter over the same storage.

**Slice 3 — observable usage.**

- A versioned, payload-free event; a stable error code instead of an exception message.
- Measured at the MCP dispatch boundary, and through a decorator in the in-app agent.
- Best-effort: one record per completed attempt, a count of what was lost, and a write
  failure that never changes the outcome of a call.
- A local report that separates what happened from what the toolbox was.

## Known limitations (documented in design.md, not fixed here)

- The kill switch is **advisory**. When the configuration cannot be read, every tool is
  enabled — including one an operator meant to disable. `configurationStatus` says so.
- The flags are **global**: the remote-config cache is a single row, so a disabled tool is
  disabled for every profile.
- The search reads the files it searches on every call. Fine at this size; a cache is a
  later decision.
- The records are best-effort. A process killed mid-write leaves a truncated line, and the
  report counts it.

## References

- `docs/decisions/2026-10-20-the-documented-toolbox-must-match-the-implemented-one.md`
- `docs/decisions/2026-10-20-one-search-implementation-two-question-shapes.md`
- `docs/decisions/2026-10-20-tool-call-records-are-best-effort-and-payload-free.md`
- `docs/architecture/mcp-server.md` — the surface as it is now
