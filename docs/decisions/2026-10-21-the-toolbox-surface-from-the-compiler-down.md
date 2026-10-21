---
title: The toolbox surface, from the compiler down
date: 2026-10-21
status: accepted
tags: [mcp, gates, tools, corpus]
---

# The toolbox surface, from the compiler down

## Context

Two waves of work on the agent-facing surface. The first (ADR
`2026-10-20-the-documented-toolbox-must-match-the-implemented-one`) made the catalogue
match the code. The second closed four gaps that the first one left:

1. the remote-config flag was keyed by a name read out of the bytecode, which could not
   be resolved (ADR `2026-10-20-the-kill-switch-was-never-applied`);
2. a flag that named *no* tool was silent;
3. the drift gate read the tool surface out of the Kotlin *sources*, and a parser that
   misreads a class does not fail — it reads less;
4. the MCP server's entry point reached into the global Koin container seven times.

This record covers the follow-up wave: the contracts an agent reads, the operator's
window on a running server, the two readers of the project's corpus, and the flag key
space.

## Decision

**The contract the agent reads is pinned by a test.** `describe_toolbox`'s payload, the
three MCP resources and the registry snapshot are the shapes clients parse. A renamed key
breaks every client, quietly. `ToolboxToolTest`, `ToolboxResourcesTest` and
`RegistrySnapshotTest` assert them key by key, and the payload builders became `internal`
so the test can see them.

**The local report reads the same registry the server writes.** The report
(`scripts/mcp-toolbox-usage.py`) had never been run against a real snapshot: its tests
wrote a registry with a *list of strings*, and the server writes a *list of objects*, so
the section that answers "which registered tool was never called" — the reason the
registry exists — raised a `TypeError`. The report now reads the shape the server writes,
accepts the older one, and names the flags that matched no tool.

**The corpus has one policy and a check that the two readers agree.** The Python reader
parsed `discover.maxDepth` and never used it, and globbed the top level while the Kotlin
reader walked the tree: a decision in a subdirectory was a decision to the app and
nothing at all to the gates. The Python reader now applies the declared depth, the Kotlin
side requires the markdown extension the Python glob already required, and
`AdrCorpusReadersAgreeTest` runs the Python reader and compares its answer with the one
the application computes.

**The flag key space is declared.** The export carries the names a configuration may use,
and the drift gate checks every flag key the documentation shows against that space. A
document that shows a flag naming a tool that does not exist teaches an operator to write
a flag that matches nothing.

**The local gate compiles the Android source set.** `check.sh` ran the JVM tests and
every module's detekt, and never the Android host tests — so a call that does not exist
in `kotlinx-datetime 0.8` passed a green detekt and 3266 green JVM tests, and only the
CI build said so. The host test is a Robolectric test: no emulator, no device.

## What could not be done, and why

**The tool list cannot be derived from the catalogue.** It used to be written out four
times — the tool factories and the agent's list, on each of the two platforms — so the
same forty-one tools were listed four times, and a tool added to one list and not to
another was a difference the wiring test could only report.

The catalogue now holds the *class* rather than a name in a string, which is the
prerequisite: the export reads the description from the very class the catalogue
declares, and the class cannot be missing.

The derivation itself is blocked by the Koin compiler plugin. It checks every definition
request against the *static* type it sees, and in a heterogeneous list that type is the
projection `Tool<*, *>` — for which no definition exists, because the definitions are for
the concrete classes. The plugin reports a missing definition for a type nobody asked
for, and it follows the call into a helper function, so moving the resolution out of the
module body does not help. `@Suppress` does not silence it, and the only resolution path
that bypasses the check is `InstanceRegistry.resolveInstance`, which is internal to Koin.

So the lists stay hand-written, and what keeps them in step is the wiring test: it
resolves the real list on the JVM and on Android and compares it with the catalogue. The
compile-time check that a tool has a definition is the price, and it is paid in the test
suite instead.

## Rationale

The wave is one question asked of the surface: *what do you know, and how do you know
it?* Each answer ends in something that can be compared — a test, an export, a declared
space, a cross-checked reader.

The report bug is the sharpest illustration. The report had nine passing tests and a
section that could not run, because the tests and the code agreed with each other and
neither with the server. A check that only compares two things that were written
together proves nothing; the fix is to make one of them come from somewhere else — the
server, the compiler, the Python reader.

## Consequences

- Three contracts an agent depends on are pinned by tests, and the report works against a
  real snapshot.
- The corpus has one declared policy; a divergence between the two readers is a failed
  test rather than a symptom.
- The flag key space is declared and checked.
- The local gate catches a broken Android source set.
- The catalogue holds the class, so the export and the documentation read the same
  declaration — and the tool list is still written out, because the Koin plugin checks
  definition requests by static type and a heterogeneous list has none.

## Links

- `mcp-server/src/test/kotlin/com/singularity/todo/mcp/` — the three contract tests
- `adr-core/src/jvmTest/kotlin/com/singularity/todo/feature/ai/tools/AdrCorpusReadersAgreeTest.kt`
- `shared/src/jvmMain/kotlin/com/singularity/todo/mcp/ToolSurfaceExport.kt` — the export
- `scripts/check-skill-drift.py` — the gate, now comparing the parser with the compiler
- `check.sh` — the local gate
