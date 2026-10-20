---
title: The documented toolbox must be the implemented toolbox
date: 2026-10-20
status: accepted
tags: [mcp, tools, ci, docs]
---

# The documented toolbox must be the implemented toolbox

## Context

`McpToolCatalog` declared 39 tools with a name, a class, a domain, annotations **and a
description**. The tool classes declared the same tools with the same name and **their own
description**. Two copies of one fact, and nothing diffed them.

The copies had drifted apart in 32 of the 39 tools. Most of the drift was harmless wording,
but five described a *different capability* than the tool implements:

| Tool | The catalogue said | The tool does |
|---|---|---|
| `smart_rewrite` | rewrites task or note content | crafts a concise task title |
| `list_linked_tasks` | tasks linked to a task or note | tasks linked to a **project** |
| `suggest_tags` | tags for a task or note | tags for a **note** |
| `refine_task` | title, description and metadata | the task **title** |
| `search_tasks` | full-text search | search by **title** |

And four `delete_*` tools were documented as "Permanently deletes a task" while the tools
create a *proposal* that needs confirmation. That is the worst shape of the defect: the
documentation says the dangerous thing happens when the safe thing happens.

The catalogue's docstring claimed more than it delivered in the other direction too — it
said "add it to [catalog] here, and it is automatically included in DI and the MCP
registry", and the DI lists are maintained by hand.

The consequence was not a crash. It was that the *documented* toolbox and the *exposed*
toolbox were two different things, and only one of them existed.

## Idea

- **A.** Keep both copies and add a gate that reports the differences. The debt stays
  visible, and the gate becomes a list of 32 things to ignore.
- **B.** Derive the DI lists from the catalogue. One source, but the catalogue would have
  to hold constructors, and a Python renderer parses that file today.
- **C.** Give every fact exactly one owner, and check the other representations against it.

## Decision

**C.**

* The **tool class owns the description**. It is the text the MCP server puts into the
  JSON schema, so it is the text the agent reads. The catalogue no longer carries one.
* The **catalogue owns the declared surface**: which tools exist, which class implements
  each, which domain it belongs to, and the annotations.
* The **DI lists stay hand-written** and are now *checked* rather than assumed:
  - a runtime test resolves the actual Koin list on the JVM and on Android and compares
    it with the catalogue — names, descriptions, and no duplicates;
  - the drift gate compares the catalogue with the tool classes, and the ADR CLI surface
    with the catalogue, through an explicit command→tool mapping declared in
    `AdrSurface.kt`.
* The **CLI surface and the MCP surface are different interfaces** and are not required to
  have identical shapes — the mapping between them is declared, and the mapping is what
  the gate checks.

The gate was extended rather than replaced: its subject was already "a document must not
describe tools the code no longer has", which is this defect one level down. It was renamed
to *the toolbox surface is declared once*, because it now checks more than the documents.

## Rationale

A duplicated fact that nobody diffs is a bug waiting to happen; the duplication is the
defect, and the gate is only the symptom. Removing the second copy makes the class of
defect impossible rather than merely detectable, and it costs nothing at runtime: the
server already read the description from the class.

The DI lists are a separate question. They *are* two hand-maintained lists, and deriving
them would need the catalogue to hold constructors — which would break the Python
renderer that reads the file as text. A runtime test proves what a static derivation
could only approximate: that Koin actually resolves the tools the catalogue declares, on
each platform.

## Consequences

- The catalogue is smaller and no longer carries prose that can disagree with the code.
- The generated "ADR tools" section in the two documents now shows the *real* descriptions,
  including the long `write_adr` guidance that the catalogue had paraphrased.
- The delete tools are documented as the proposals they are.
- Adding a tool means touching the catalogue and both DI modules, and the build fails if
  one of them is missed.
- A description change on a tool class changes the documentation automatically, because the
  documentation is generated from the class.

## Links

- `docs/architecture/mcp-server.md` — the surface as it is now
- `scripts/check-skill-drift.py` — the gate, and the positive controls for it
- `scripts/tests/test_render_adr_tools_section.py` — the controls, one per mismatch type
- `desktopApp/src/jvmTest/.../ToolSurfaceWiringTest.kt` — the runtime check
- `shared/src/androidHostTest/.../ToolSurfaceWiringTest.kt` — the same, on Android
