---
name: singularity-todo-genui-catalog
description: How to add, change or remove a component in the GenUI catalog — the one declaration that generates the prompt, the validator and the JSON Schema, plus the renderer that must agree with it. Use when touching feature/genui/catalog, UiNode, Material3Catalog, DomainComponents, or when a model "invents" a component that already exists.
---

# Adding a component to the GenUI catalog

## The rule everything else follows from

The catalog is **one declaration** from which three things are generated: the system prompt the
model reads, the validator that rejects what it must not send, and the JSON Schema. A component
therefore has exactly one place it is *described*, and describing it twice is how the model ends up
being offered something the client refuses.

The two failure modes are silent and opposite:

| Failure | Symptom |
|---|---|
| Schema, no renderer | Surface renders with a hole in it. No error anywhere. |
| Renderer, no schema | Component the model is never told about, so never uses. |

`SingularityCatalogTest` checks both directions. It will fail the moment one side moves alone.

## The four places a component touches

Adding `sparkline` means all four, in this order:

1. **`catalog/UiNode.kt`** — a `UiNode.Sparkline` data class. This is the parsed, validated shape.
2. **`catalog/SingularityComponents.kt`** — `sparklineSchema(): A2uiComponentSchema`, and its name
   added to the list in `componentSchemas()`. **Properties are declared against the wire**, not
   against the node type: name them as the model must spell them.
3. **`core/A2uiNodeFactory.kt`** — `decode` reads the JSON object into the node. It is a `when` over
   the catalog, so adding a kind without a branch is a compile error, not a silent gap.
4. **A renderer** — `render/material3/atoms/…` and one `registerSparkline()` in
   `Material3Catalog.install()`, or `render/domain/DomainComponents.kt` for anything that knows about
   tasks, projects or dates.

```kotlin
private fun sparklineSchema(): A2uiComponentSchema = A2uiComponentSchema(
    name = "sparkline",
    description = "A row of values drawn as bars.",   // the model reads this verbatim
    properties = listOf(
        A2uiProperty("values", A2uiType.JSON, true, "The bars, oldest first."),
        A2uiProperty("max", A2uiType.INT, false, "Scale. Inferred when absent."),
    ),
)
```

**Describe it in one sentence a model can act on.** The description is the prompt. "Emphasis" tells a
model nothing; "A short label in a coloured pill. Use for state, not for free text." tells it when
to reach for the component.

## Domain components

`task_card`, `due_date` and `project_chip` are the ones a model reaches for first, because they are
the app's nouns. The rule that keeps them honest: **they read only the surface's data model, never a
repository.** A renderer that queries a repository during composition turns a screen into an I/O
operation the model did not ask for.

Dates use the existing `formatDueChip` / `formatRussianDueDate` helpers. A third copy of
"Today/Tomorrow" in this project is a regression, not a convenience.

## Properties and types

`A2uiType` is closed. Its members, as declared in
`shared/src/commonMain/kotlin/com/singularity/todo/feature/genui/catalog/A2uiCatalog.kt`, are:

```
STRING  INT  BOOL  NODE_REF  NODE_REFS  TONE  DIRECTION  PATH  JSON
```

**Read that declaration rather than trusting this list.** An earlier version of
this skill enumerated three array types and a container enum that do not exist in
this repository, and its worked example called one of them — an agent following
it would emit property types the validator rejects, and would not know that
`NODE_REF` or `PATH` exist at all. That text survived because
`check-doc-dead-refs.py` could not see enum constants, so the invented names
were reported as dangling *and* the real members were reported as dangling too,
which made the report indistinguishable from noise. The gate now indexes enum
entries. If you add a member to `A2uiType`, update this list in the same commit;
the gate will name the member if you do not.

Anything a model can mis-spell should be a property with an
enumerated `values` list, not free text — the validator then answers with the legal values, which is
the single most useful thing to send back.

Required vs optional is a real decision: an optional property that the renderer treats as mandatory
is a surface that renders wrong with no error.

## Functions

Functions are separate from components and live in `function/BuiltinFunctions.kt`, registered in
`A2uiFunctionRegistry`. Two rules:

- **Validated at parse time, resolved at render time.** An unknown `${…}` function is an
  `INVALID_TEMPLATE` rejection the model can correct; the data it references usually arrives later.
- **Positional arguments only.** Named ones were dropped on purpose: they double the grammar and
  allow a permutation the calling side cannot see.

If you add a function, it must not become a third copy of an existing formatter. Search first.

## Wiring, or the "done but does nothing" defect

A component that is declared, parsed, validated, tested — and never rendered — is the most common
defect in this layer, and the repository audits for it
(`scripts/find-unwired-surfaces.py`). Two checks that catch it here specifically:

- The registry installs every renderer (`Material3Catalog.installAll`, used by the DI module).
  `Material3Catalog.install` alone covers only the Material3 half — it is kept for that reason and is
  not what production uses.
- Add the component to `shared/src/jvmTest/resources/genui-corpus/*.jsonl`. The corpus plays real
  responses through the real pipeline, so a catalog that can no longer express our own screens fails
  a test instead of failing a user.

**The corpus cannot prove the renderer draws anything.** It checks parsing and validation, never
pixels. Only a Compose test can. If you add a renderer, that is the gap — and the feature the corpus
was built for is the gap that finally needed closing.

## Tests that must go with a component

- `SingularityCatalogTest` — schema ↔ renderer in both directions (already automatic).
- A parser case in `A2uiParserTest` — a wire line becomes the node you expect.
- A corpus line — the catalog can still express a screen that uses it.
- A rejection case in `A2uiValidatorTest` — a malformed use is refused, not rendered wrong.

Each test class needs a `@Tag`: `fast` for pure in-process, `slow` when it touches a real file, the
database, or the clock.

## Gotchas found the hard way

- **The wire id is not the surface's address.** A message's `surfaceId` groups the lines of one
  answer. The caller's `SurfaceId` decides which answer — always `UiEvent.retargeted(id)` before
  applying, or two answers' screens land on one address.
- **Prose around a surface is not a violation.** The framer routes prose to `FramedLine.Prose`; the
  parser only ever sees message-shaped lines. A `{`-line that will not parse is a rejection
  (`Failed`), a blank line is a skip, and prose is neither.
- **A truncated tail is not a contract breach.** Once something has applied it is `ADVISORY`, because
  re-asking under the same token limit truncates it again. Only truncation with nothing applied is
  worth retrying.
- **Severities decide what the user sees.** `COMPONENT` drops one node, `ADVISORY` reports and keeps
  everything, `MESSAGE` drops the message. A forward reference to a component a later message defines
  is `ADVISORY` — dropping it would empty a surface that was about to fill in.

## Reference

- `catalog/A2uiCatalog.kt`, `catalog/SingularityComponents.kt`, `catalog/CatalogPrompt.kt`
- `ADR 2026-10-05-genui-catalog-as-contract` — why the catalog is the contract
- `openspec/changes/genui-catalog-contract/specs/genui-catalog/spec.md` — REQ-GC-001…006
- `docs/CONTEXT.md` — Surface, Catalog, Severity