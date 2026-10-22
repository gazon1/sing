---
title: "The contract types are not UI"
date: 2026-10-28
status: accepted
tags: [architecture, text-fields, compose, contract, genui]
---

# The contract types are not UI

## Context

Four types describe **who owns a value** and **how many times it was replaced**:

- `FieldOwnedText` — the text of a field that the field alone writes, with its three
  named writes (`seeded`, `echoed`, `cleared`);
- `FieldOwnedTextAsString` — its serializer;
- `TextSource` with its two shapes, `StaticText` and `OwnedText` — what a bridged field
  is handed, and who may rewrite it;
- `Revisioned<T>` — a value and the count of the times it was replaced from outside.

All four lived in `com.singularity.todo.core.ui.textfields`, next to the Compose
helpers (`TextFieldStateBridge.kt`, `TextFieldEdits.kt`, `TextFieldShapes.kt`,
`TextFieldTransformations.kt`).

About thirty files outside that package imported them. Every one of them was reaching
into a **UI** package to learn a fact that has nothing to do with the UI: that a value
carries a count of its replacements, that a seed says where a value came from. A
feature that owns a value — a task draft, a note, a project's name — had to depend on
the UI layer to say so.

The dependency was paid for twice. The GenUI schema layer could not use `Revisioned`,
because "the schema layer must not depend on the UI layer's types", so it declared its
own pair of the same shape: `data class PathValue(val value: JsonElement?, val
revision: Long)`. The app had two types for "a value and the count of its
replacements", and the reason for the second one was a package name.

The helpers had to stay in `core.ui.textfields` — the arch test that derives a field's
shape from its imports needs a package that holds no field sites, and the helpers must
be unreachable without an import — but nothing says the *contract* types have to live
there.

## Idea

**A fact about ownership is not a fact about the UI.** The count of the replacements,
and the promise that a seed came from outside the field, are about values and their
owners. They are needed by whoever holds a value, and the holder is often not a
composable: a ViewModel, a draft state, a data model.

So the contract types move to a neutral package — `com.singularity.todo.core.text` —
and the Compose helpers stay behind in `core.ui.textfields`, where the arch test can
still find them and where they belong.

With the contract types neutral, the reason the GenUI schema layer kept its own pair
is gone, and there is one `Revisioned` in the app.

## Decision

**The contract types live in `com.singularity.todo.core.text`; the Compose helpers
stay in `com.singularity.todo.core.ui.textfields`.**

- `core.text` holds `FieldOwnedText`, `FieldOwnedTextAsString`, `TextSource`
  (`StaticText`, `OwnedText`) and `Revisioned<T>`. It is a neutral package: it names
  no field, no composable and no screen.
- `core.ui.textfields` holds only the helpers. The arch test
  (`TextFieldShapeScan`) still excludes that package from the field-site scan by its
  path, and the import of `rememberBridgedTextFieldState` /
  `rememberAnchoredTextFieldState` is still mandatory everywhere.
- `FieldOwnedTextAsString` keeps its serial name,
  `com.singularity.todo.core.ui.textfields.FieldOwnedText`. The name is part of the
  **backup file format**: a backup written by an older build has to restore. The
  package moved; the name on the wire did not.
- The GenUI schema layer uses `Revisioned<JsonElement?>` where it used `PathValue`.
  `PathValue` is deleted.

The schema layer's own storage changed with it: the per-path count is kept as a
`Revisioned<JsonElement?>` rather than as a bare `Long`, because the count and the
value it counts are two facts about the same thing — the same argument that made
`Revisioned` worth having. The count numbers the GenUI hands out do not change
meaning: a server write moves it, a field's own write does not.

## Rationale

The alternative is to leave the contract types where they are and to keep the GenUI's
own pair. That arrangement has a cost that grows with every new owner of a value: a
new feature that wants to say "this value was replaced from outside" has to know that
`Revisioned` lives under `core.ui`, and the schema layer has to keep a second type in
step with the first. The two can drift, and the drift is silent — both compile, both
are used, and the counts mean slightly different things.

Moving the types is the smaller change of the two. It is a package move and an import
rewrite; no behaviour changes, no call site changes shape, and the helpers stay
exactly where the arch test expects them. The GenUI then loses a type instead of
gaining a rule.

Moving them also makes the question the package answers explicit: `core.text` says
"these are facts about text values", not "these are facts about text fields". The
GenUI's data model holds text values and now says so with the same type the fields
use, instead of with a type that exists because the other one was in the wrong place.

## Consequences

- The GenUI schema layer depends on `core.text`, a neutral core package, and no
  longer on the UI layer. The GenUI's data model knows nothing about text fields, and
  it does not have to.
- There is one `Revisioned` in the app. The GenUI's `PathValue` is gone, and the
  count the schema hands out is the same kind of count the drafts hand out.
- `FieldOwnedTextAsString`'s serial name is now a name of a package that no longer
  holds the type. That is deliberate and it is the price of not breaking the backup
  format; a future rename of the wire name would break every backup ever written, so
  the name stays.
- The closed ADRs that say `core.ui.textfields` holds the contract types
  (`2026-10-21-the-count-travels-with-the-value`,
  `2026-10-21-the-static-kind-is-a-guarantee`,
  `2026-10-21-the-field-shape-is-derived-not-read`) describe the arrangement as it
  was. They are not edited; this record supersedes them on that one point.
- `core.text` was not empty before this move — it already held `TextMetrics.kt`, the
  measurement of a visible string. The package now holds the text-related types that
  are not about fields.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/text/TextFieldSource.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/text/Revisioned.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/text/FieldOwnedText.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/text/FieldOwnedTextAsString.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/textfields/TextFieldStateBridge.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/genui/schema/DataModel.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/TextFieldSourceContractTest.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/TextFieldRewriteContractTest.kt`
- `docs/decisions/2026-10-28-a-seed-does-not-carry-the-field-text.md`
