---
title: The gate reads the surface the compiler saw
date: 2026-10-20
status: accepted
tags: [gates, mcp, tools]
---

# The gate reads the surface the compiler saw

## Context

`scripts/check-skill-drift.py` — the gate *the toolbox surface is declared once* — checks
three things: that the catalogue and the tool classes agree, that the ADR CLI's command
mapping points at real tools, and that the two generated documentation blocks are in sync
with the code.

The first two checks read the Kotlin **sources**. The parser is a set of regular
expressions over the text of `McpToolCatalog.kt` and of every file in
`feature/ai/tools/` — and a regular expression that misreads a class does not fail. It
reads *less*, and the gate reports a surface smaller than the code. A tool class whose
declaration the regex does not recognise is a tool the gate cannot see, and the gate
passes.

That is the shape of defect this whole change was about: a check that passes on a tree it
did not really read. The catalogue had 32 drifted descriptions and 5 tools documented as
capabilities they did not have, and nothing compared them — because a comparison that
reads the wrong thing is not a comparison.

## Idea

- **A.** Leave the parser alone; it is covered by 29 controls, and a parser bug is a bug
  in the parser.
- **B.** Export the same surface from the **compiled** classes and compare the two: what
  the gate parsed against what the compiler saw.
- **C.** Replace the parser's input: the gate reads the export and never looks at the
  sources.

## Decision

**B.**

`./gradlew :shared:exportToolSurface` writes `config/docs/tool-surface.json` from the
compiled classes: the catalogue's entries, and the `NAME` and `DESCRIPTION` constants read
from the tool classes themselves. The file is committed, and a test in `:shared` says it
is current.

The gate then compares what it parsed with what the export says, and reports a problem
when they differ — a tool the export has and the sources do not, a tool the sources have
and the export does not, and a description the gate read differently from the class that
declares it.

The three facts line up:

- the code declares the surface,
- the export is that surface, written down,
- the gate's parser is that surface, read back out of the sources.

C was not taken. The gate reads the sources on purpose: a gate that runs a compiler is a
gate nobody runs, and the *sources* are what a reader of the repository looks at. What
was missing was not a different input but a second opinion on the same input.

## Rationale

The export is a projection, not a copy. It is generated from the code, a test says it is
current, and it holds no fact that the code does not already hold — so it does not
violate the "one fact, one owner" rule that the catalogue change was built on. It is the
same kind of artifact as `docs/scripts/gate-registry.md`, which is generated, committed
and checked.

The comparison is deliberately narrow: names and descriptions. Those are the facts the
parser reads, and they are the facts that a misread declaration would change. Comparing
more would make the check brittle for no gain; comparing less would leave the hole open.

The alternative to all of this — a parser that fails loudly on anything it does not
recognise — is what the renderer already does for the *CLI* declarations: an unreadable
declaration is an error, not a skip. Extending that to the tool classes would mean
recognising Kotlin class declarations by regex, which is the same problem one level
deeper.

## Consequences

- A class the parser misreads is now a **red gate**, not a smaller surface.
- The export is a file to keep in step with the code, and the test says when it is not.
  A change to a tool's description without the export is a failed build, with the command
  to fix it.
- The export needs the JVM target to run, so it lives in `:shared/src/jvmMain`. The
  manifest is a JVM-side artifact; the gate that reads it is Python, and the two meet
  at the file.
- The gate's 29 controls still hold: the new checks report *additional* problems, and
  the controls assert that a specific problem is among them.

## Links

- `shared/src/jvmMain/kotlin/com/singularity/todo/mcp/ToolSurfaceExport.kt` — the export
- `shared/src/jvmTest/kotlin/com/singularity/todo/mcp/ToolSurfaceExportTest.kt` — the
  "the committed export is current" test
- `scripts/check-skill-drift.py` — `surface_problems()`
- `config/docs/tool-surface.json` — the generated surface
- `docs/architecture/mcp-server.md` — the surface, as the server exposes it
