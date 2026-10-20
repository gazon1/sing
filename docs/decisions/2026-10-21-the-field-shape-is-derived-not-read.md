---
title: "The shape of a field is derived from its imports, not read out of its text"
date: 2026-10-21
status: accepted
tags: [architecture, text-fields, tests]
---

# The shape of a field is derived from its imports, not read out of its text

## Context

Two arch tests hold the text-field contract in place:

- `TextFieldShapeConventionTest` — every file that owns a text field is classified as
  *local*, *bridged* or *anchored*, and the classification is published in the inventory
  table of the `singularity-todo-text-field-states` skill;
- `TextFieldRewriteContractTest` — every bridged field site has a rewrite contract: the
  upstream, the kind of source the site is handed, the counter, and the file that
  advances it.

Both tests read the **source text** of the files they check. The shape convention test
walked the tree and asked whether a file's text contained `rememberTextFieldState(`,
`rememberBridgedTextFieldState(` or `rememberAnchoredTextFieldState(`.

That is the same trick that was just removed from the rewrite contract, and it was
removed for a reason: a text scan cannot tell a *call* from a *mention*. A file's KDoc
that names a helper classifies a file that owns no field at all, and a helper named in a
comment can hide a field that does. The rewrite contract moved its answer into a type —
`StaticText` and `OwnedText` — precisely so that the answer could not be written down
loosely. The shape convention kept the loose version.

The rewrite contract's own scans had the same flaw, in a milder form: the owner's source
was found with a regex over the file's text (`val x: OwnedText`), the counter was found
by searching the text for the word "revision", and the bridge's parameters were read out
of a regex over its declarations.

## Idea

Let the *compiler* say which shape a file has, instead of reading the file and guessing.

A file that uses `rememberBridgedTextFieldState` has to **import** it: the helper lives
in `com.singularity.todo.core.ui.textfields`, and no other package can reach a symbol
without an import. The import list is therefore the compiler's own record of the shape —
a mention in a comment imports nothing.

The catch is that the helpers used to live in `com.singularity.todo.core.ui.components`,
next to the fields that used them. A file in that package could call the helper with no
import at all, and the import-based derivation would have missed it. The helpers have to
move first.

## Decision

**The helpers move to their own package, and the shape is derived from the imports.**

- **`com.singularity.todo.core.ui.textfields` holds the helpers.** `TextFieldSource`,
  `TextFieldStateBridge`, `TextFieldShapes`, `TextFieldEdits` and
  `TextFieldTransformations` move there, and so do their tests. The package holds no
  field sites, so the helpers are excluded from the scan by their *path* rather than by
  a list of file names that would rot.
- **The shape is derived from the imports.** `TextFieldShapeScan` reads every file's
  imports through Konsist: an import of `rememberBridgedTextFieldState` means *bridged*,
  an import of Compose's `rememberTextFieldState` means *local*, an import of
  `rememberAnchoredTextFieldState` means *anchored*. The registry in the test stays — the
  *act of writing the classification down* is the point — but it is compared with the
  derived shape instead of with a text scan.
- **The rewrite contract's scans become structural too.** The bridged sites are the
  files that import the bridged helper; the owner's source is a property with a type;
  the counter is a property with a name; the bridge's parameters are a list of names, and
  a *public* one must not carry a `revision` parameter.
- **What Konsist cannot express stays textual, but is scoped.** Konsist 0.17.3 has no API
  for call sites, so "the counter *moves*" and "the site *constructs* a source" are read
  from the **declarations** of the file, with the comments stripped. A comment outside a
  declaration can no longer make a counter move, and a preview is excluded by its
  *annotation*, not by the word.

## Rationale

- **A mention is not a use.** The whole point of the rewrite contract's move to types was
  that a fact written in prose could be wrong without anything noticing. A text scan
  reintroduces exactly that: the file's own documentation becomes evidence about the
  file's behaviour.
- **The import is not a hint.** It is what the compiler needs in order to resolve the
  symbol. A file that imports the bridged helper *is* a bridged site, whatever else it
  says.
- **The same package was a hole.** The helpers sat in `core.ui.components`, and a field
  in that package could reach them without an import. Moving them is what makes the
  derivation exact; without the move, the derivation would have been "correct except for
  one package", which is the kind of exception that grows.
- **The registry is not derived.** A derived registry would classify every field the
  moment it was written. The skill's own warning is about a field nobody classified, so
  the hand-written registry stays.

## Consequences

- The `TextFieldStateBridge`, `TextFieldShapes`, `TextFieldEdits` and
  `TextFieldTransformations` files have moved, and so have the 31 files that import
  them. The API is unchanged; the package is not.
- `TextFieldShapeConventionTest` no longer contains a single `readText()`. Neither does
  `TextFieldRewriteContractTest`, except for the skill's own table — which is prose, and
  is meant to be read.
- The scan is per file, not per field: a file with two bridged fields of different
  upstreams is one row in the rewrite contract, and the per-field answer lives in the
  skill's table.
- The rewrite contract's "the counter advances" and "the site constructs a source"
  checks are still textual at the finest grain Konsist 0.17.3 offers. A comment *inside*
  a declaration can still fool them; a comment outside one cannot.

## Links

- `2026-10-20-the-rewrite-authority-is-a-type` — the decision that moved the answer
  into the type of the source.
- `2026-10-20-the-bridge-takes-a-revision-token` — superseded by the above.
- `.agents/skills/singularity-todo-text-field-states/SKILL.md` — the inventory and the
  rewrite table this change keeps honest.
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/TextFieldShapeScan.kt` — the
  derivation.
