---
title: "Every text field is classified, and the classification is checked against the code"
date: 2026-10-20
status: accepted
tags: [ui, compose, testing, conventions]
---

## Context

The skill `singularity-todo-text-field-states` tells a developer to pick one of three
shapes for a text field — *local*, *bridged*, *anchored* — and it publishes an
inventory: a table of every field in the app and the shape it has.

The inventory was prose and it was wrong in three ways, all of them the same way.

**It listed the local fields as a sentence.** "The auth forms (`LoginScreen`,
`AttachAccountScreen`, …), the sheets and dialogs that commit on a button
(`AttachmentsSheet`, `AddTagDialog`, …), the quick-add row of the notes list,
`ProjectDetailBody`'s two fields, `ProfileSwitcherScreen`, `ChatInputBar` and the
regexp sheet of `SavedAgendaScreen`." A list of composable *names*, not paths, in a
sentence — which is readable and uncheckable. Two files that own a local field were
not in it at all: `SaveSearchDialog` and the local field of `AiProviderSettingsScreen`.
Nobody noticed, because nothing could.

**Nothing checked it against the code.** A field added last week is not in the
inventory, and the inventory is a document, and documents do not fail builds. The
skill's own line — "a field that is not in this table has not been classified yet" —
was a request, not a check.

**The bridged and anchored tables were per field, and a file can hold both.**
`AiProviderSettingsScreen` has a local field, a bridged field and two anchored ones.
`ChatScreen` has a bridged chat input and local `ChatInputBar` states. A table keyed
by "where" could not say "this file has two shapes" — so the file's *shape*, the thing
a reader wants to know, was nowhere to be found.

## Idea

Publish the classification where it can be checked, and check it.

## Decision

**The classification is a registry in a test, and a table in the skill, and a test
compares the two with the code.**

`TextFieldShapeConventionTest`
(`shared/src/jvmTest/kotlin/com/singularity/todo/arch/TextFieldShapeConventionTest.kt`)
holds the registry: one row per file, with the shapes that file's fields have. The
skill's inventory table is the human-readable copy. Three things are checked:

1. **Every file that owns a field is classified.** A file that calls one of the three
   helpers and is not in the registry fails the build. A new field is a new
   classification, and the classification is a decision somebody has to make on
   purpose.
2. **The registry matches the code.** The shapes a file declares are the shapes its
   helpers implement. A file that claims *local* and calls the *bridged* helper fails —
   the claim is what the next reader trusts.
3. **The skill matches the registry.** The inventory table lists every classified file,
   with the same shapes. The skill is a *build input* of the test: the Gradle task
   declares the `.agents/skills` directory as an input, so editing the table re-runs
   the test instead of leaving a stale verdict behind.

**The inventory is keyed by file, and the shapes are a set.** A file with fields of
two shapes lists both — that is the fact a reader wants, and the previous per-field
tables could not say it.

**The registry is written out, not derived.** The scan could compute the shapes from
the code; the second check does exactly that. The registry is written out anyway,
because *writing it down* is the point: a classification nobody wrote down is a
classification nobody made. A derived registry would make every field in the app
"classified" the moment it was written — which is the state the skill's warning is
about.

## Rationale

**A convention that is not checked is a suggestion.** The skill has been telling
developers to pick a shape since the migration. The advice was good and the table was
wrong in the first month, and nothing noticed. A rule that fails the build is the only
kind of convention that survives a deadline.

**The code and the document have to agree, and only a test can say so.** The code
knows which helper a file calls. The document knows why. A test that compares them is
the only thing that keeps the two from drifting, and the drift is what makes a
document untrustworthy.

**The skill is an input, not a comment.** A test that reads a file outside its module
has to declare that file as a task input, or Gradle re-reports a verdict about a table
it never re-read. The declaration is in `shared/build.gradle.kts`, next to the Maestro
flows, for the same reason.

**The scan is lexical, and the limits are stated.** The test sees the helper a file
calls, not the field it belongs to: a file with three fields of two shapes is
classified as both, and the per-field answer lives in the table's "Field" column. A
field whose *owner* is wrong in a way the helper cannot express — a bridged field
whose upstream is not the ViewModel — is not visible here. The test says so in its
own KDoc, because a check that overstates its reach is worse than no check.

## Consequences

- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/TextFieldShapeConventionTest.kt`
  — new: the registry and the three checks.
- `shared/build.gradle.kts` — the `.agents/skills` directory is a declared input of
  `:shared:jvmTest`, and `skills.root` points at it.
- `.agents/skills/singularity-todo-text-field-states/SKILL.md` — the inventory is one
  table keyed by file, with a shape column; the two files that were missing are in it.
- **The registry has to be updated when a field is added.** That is the cost, and it is
  the point: the classification is a decision, and a decision nobody makes is not a
  decision.
- The test reads the skill from outside the `shared` module, so it depends on a
  system property. A test run without Gradle — an IDE running the class directly —
  fails with a message saying so, rather than skipping the check.

## Links

- `.agents/skills/singularity-todo-text-field-states/SKILL.md` — the procedure and the
  inventory
- `docs/decisions/2026-10-19-text-field-state-migration.md` — the migration the
  inventory describes
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/TextFieldShapeConventionTest.kt`
  — the convention test
