---
title: The toolbox, once more
date: 2026-10-21
status: accepted
tags: [mcp, gates, tools, di]
---

# The toolbox, once more

## Context

The second wave (ADR `2026-10-21-the-toolbox-surface-from-the-compiler-down`) left five
things that the work itself pointed at:

1. the tool list was written out four times — the factories and the agent's list, on each
   of the two platforms;
2. the local report read the call records without knowing their schema version;
3. the resource URIs and the payload's compatibility rules were contracts that no test
   pinned;
4. `ToolAnnotations` sat alone in a package the catalogue had just left;
5. the flag key space was declared in a build artifact, where an operator writing a
   remote configuration would not look.

## Decision

**The list is written once — and it could not be.** The two blocks — the use cases and
tools, and the list the agent is given — are byte-for-byte identical in the two platform
modules, and moving them to `commonMain` as `Module.aiToolBindings()` is the obvious
change. It was tried, and the Koin compiler plugin refuses it: it checks each
definition against the graph of the compilation the definition is *written in*, and the
tools need `PromptExecutor` and `LLModel`, which are bound per platform.

The chain behind that is real, not incidental: `PromptExecutor` needs
`PromptExecutorPort`, which needs the executor, which needs `SecureStoragePort` — and the
secure storage is the platform's own (the Android keystore against a file). So the
dependency the tools have cannot be bound in common code, and the tools cannot be
declared in common code either.

Which leaves two ways to have one copy, and both cost more than the duplication:

- declare the tools with explicit lambdas that take the platform dependencies as
  parameters — which trades the plugin's constructor check for a shorter file;
- generate the platform block from one template, and check the generated block the way
  the tool surface is checked — a second generated artifact, in the module that is
  least about generation.

So the two copies stay. What keeps them in step is the wiring test: it resolves the
real list on the JVM and on Android and compares both with the catalogue, so a tool
bound on one platform and not the other fails the build. That is exactly the failure the
duplication once permitted — the Android agent could create and update a project but had
no tool to read the existing ones — and it is now a red build instead of a surprise.

**The report knows the version.** A record whose `schemaVersion` is not the one the script
knows is counted and *named*, and is not read as a call. Its fields may mean something
else, and a total that quietly included it would be a number the script cannot stand
behind.

**The contracts are pinned.** The resource URIs are asserted, and the payload's
compatibility rule is written down where the payload is built: a new field is allowed, a
renamed or removed field is not.

**The annotations sit with the catalogue.** `ToolAnnotations` moved to `feature/ai`, next
to the catalogue it belongs to, because a package that held one declaration was a leftover
rather than a boundary.

**The key space is published.** The architecture document now carries a generated block
with every flag key, grouped by domain — the same arrangement as the ADR tool block, so
the gate compares it with the catalogue and the list cannot go stale. An operator
configuring a server can read the names the server will honour.

## Rationale

Every one of these is the same move: a fact that was written twice, or read by something
that did not know what it was reading, now has one owner and a check.

The version check is the sharpest. The records are append-only and outlive the code that
wrote them — that is the reason the telemetry is payload-free and best-effort. A report
that read a future record as a call would state a number it could not support, and the
only symptom would be a number that is quietly wrong.

The list consolidation is the cheapest of the five and the one with the longest reach:
four copies of the same forty-one tools became two, and the one that remains is the
list the Koin compiler plugin can check.

## Consequences

- The tool list is still written twice. The duplication is now *named*: the two blocks
  are identical, the wiring test keeps them in step, and the reason they cannot be
  merged is the Koin plugin's per-compilation graph check.
- The report refuses to count what it cannot read, and says so.
- The payload and the URIs are contracts, pinned and with a stated compatibility rule.
- The flag key space is visible where an operator reads it, and cannot drift.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/di/AiToolBindings.kt` — the
  list, once
- `scripts/mcp-toolbox-usage.py` — the version check
- `mcp-server/src/test/kotlin/com/singularity/todo/mcp/ToolboxResourcesTest.kt` — the
  URIs
- `scripts/render-adr-tools-section.py` — the generated flag key block
- `docs/architecture/mcp-server.md` — where the key space is published
