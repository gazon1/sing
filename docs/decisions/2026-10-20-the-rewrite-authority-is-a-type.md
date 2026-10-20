---
title: "The authority to rewrite a field is a type, not a row in a table"
date: 2026-10-20
status: accepted
supersedes: 2026-10-20-the-bridge-takes-a-revision-token
---

# The authority to rewrite a field is a type, not a row in a table

## Context

The rewrite contract — *who may replace this field's text, and how does the field
learn about it* — was published in two places: a registry inside
`TextFieldRewriteContractTest`, and the rewrite table in
`.agents/skills/singularity-todo-text-field-states/SKILL.md`. The test compared the
two and read the **source text** of every call site to check the code against the
table.

That arrangement worked, and it had three failure modes. All three were silent,
and all three cost a rewrite:

- **A field whose upstream owned the value passed no counter.** The bridge's echo
  guard drops a rewrite that lands on a value the field produced earlier, because
  from the field's side that is indistinguishable from a lagging upstream. The
  counter is the way out, and a field that forgot it simply lost the rewrite.
- **A field passed a counter that never moved.** `revision = 0` is a number, and
  the registry said the field carried a revision, so the check passed. The rewrite
  was dropped, and the code looked like it was honoured.
- **A field forgot the question altogether.** The parameter that carried the answer
  — `revision: Long = 0` — had a default. A call site that said nothing about
  authority was compiled, ran, and lost its rewrite.

The create-mode task editor was the standing proof. `TaskTitleRow` and
`TaskDescriptionField` are shared by the create screen and the detail screen. The
detail screen's upstream owns the value and needs the counter; the create screen's
upstream does not. The registry had **one row per file**, so it could not say both,
and the create screen silently used the detail screen's counter — a frozen `0`.

## Idea

Make the answer part of the type the bridge is handed, so that a call site cannot
express the wrong answer.

## Decision

The bridge takes a **source**, and the source's type says who may replace the
value.

```kotlin
sealed interface TextSource { val text: String }

sealed interface StaticTextSource : TextSource
sealed interface OwnedTextSource : TextSource { val revision: Long }

data class StaticText(override val text: String) : StaticTextSource
data class OwnedText(override val text: String, override val revision: Long) : OwnedTextSource
```

- **A static source** says the upstream replaces the value only with one the field
  never produced: it loads the value once, or it clears it. The echo guard is
  enough, and there is no counter.
- **An owned source** says the upstream owns the value and may replace it with
  anything, including a value the field produced. It carries the count of the
  replacements, and the bridge lifts its echo guard for the value that arrives
  with a new count.

Three properties follow from the shape, and each of them closes one of the failure
modes above:

- **The counter is not a parameter.** `rememberBridgedTextFieldState` has no
  `revision` argument, public or defaulted. A call site cannot freeze a counter,
  because there is nothing to freeze: the count is a property of the source.
- **The question cannot be left out.** The source is required, and its type says
  what it means. A field that forgets the authority does not compile.
- **The kind is the type.** The two sources are unrelated types, and the bridge has
  an overload for each. An owned value cannot be handed to the bridge as if it were
  a static one.

**The owner builds the source; the field passes it through.** The source is the
answer to "who may rewrite this field?", and only the owner — the component that
holds the value and knows who else writes it — knows the answer. A field that
built its own source would be answering a question it cannot know the answer to.
So the sources are built in the state classes: `ChatViewModel.State.inputSource`,
`AnnotationDraft.startSource`, `TaskDetailUi.titleSource`, `ProjectDetailDraft.nameSource`,
`EditorState.Editing.titleSource`, and so on for every site.

**The one documented exception** is the GenUI renderer. Its owner is a server the
renderer does not own, and the renderer is the boundary where the server's value
and the server's count are read; it builds the source there. The count still comes
from the data context, not from the renderer.

**A component that serves two upstreams takes the base type.** The task editor
serves the create draft and the detail draft, and the two are different kinds of
upstream. `TaskEditorContent` and the two fields under it take a `TextSource`, and
the bridge's internal overload reads the kind off the value. The `when` there is
exhaustive — `TextSource` is sealed with exactly two permitted shapes — so a third
kind of source cannot be added without the bridge saying what it means.

**The table stays, but it is now documentation.** The rewrite table still lists
every bridged field site, its upstream, and the kind of source the code uses. What
changed is what the table is *for*: it is no longer the place where the answer
lives and the code is checked against it — it is the human-readable index of the
same answer the code states in its types. `TextFieldRewriteContractTest` still
compares the two, and still fails when a site is missing from the table, so the
index cannot drift away from the code. It also checks what the types cannot: that
the owner declares a source of the claimed kind, that an owned source's counter is
declared *and* advanced somewhere, and that a field site does not build its own
source.

## Rationale

The registry was not wrong about the facts. It was wrong about where the facts
should live. A convention checked by a lexical test is a convention: it holds
exactly as long as every author remembers it, and the three failures above are
what "forgets" looks like when the cost is a lost rewrite rather than a failed
build.

Moving the answer into the type changes who can get it wrong. The field site — the
place where all three failures happened — can no longer express them. What remains
is the owner's choice, and the owner's choice is *visible in the code*: an
`OwnedText` at a call site says "this upstream owns this value", and a reviewer can
see it without opening a table in another file.

The two kinds are the two behaviours, not two styles. The static shape is not
"the upstream never writes" — it is "the upstream writes values the field never
produced", which covers the chat input the ViewModel clears and the search query
the ViewModel empties. The owned shape is "the upstream may write anything", which
covers a synced entity. A field that needs the counter and a field that does not
are different *contracts*, and they are different types.

Why not one sealed type with the count inside it? Because the count must not exist
for a field that has no replacements to count. A static source without a count is
not a weaker owned source; it is the truth about that upstream, and the bridge
behaves accordingly without being told to.

Why the owner builds the source rather than the field? Because the question is
about the *upstream*, and the field is not the upstream. A field that constructs
`StaticText(...)` for an upstream that rewrites with field-produced values has
answered a question it could not know the answer to — and it answered it wrongly in
the direction that loses work. The owner is the only component that can answer it.

## Consequences

- **The three failure modes are closed by construction.** A call site cannot pass
  a frozen counter, cannot omit the counter of an owned upstream, and cannot forget
  the question — the bridge's API does not admit the mistake.
- **The contract test shrank into what types cannot say.** It no longer has to
  parse `revision = …` arguments to guess whether a counter can move; it checks
  that the owner declares the source and that the counter is declared and advanced.
- **A shared component cannot demand a kind.** The task editor takes a
  `TextSource` and reads the kind off the value. That is the price of one
  component serving two upstreams, and it is paid in one place: the bridge's
  internal overload.
- **A sealed UI state declares the source as an extension property.** The notes and the
  projects carry their search query in every variant, as a required constructor parameter.
  The source is a *view* of that query — `val NotesUiState.searchQuerySource` — rather than
  a second property of the interface: a property of the interface would ask every variant to
  carry the source as well, and the query and the source would then be two values that could
  disagree. The detekt rule `NoDroppedUiStateProperty` asks the same thing, and the extension
  answers it in the direction that keeps one value.
- **The owner can still lie.** An owner that hands out `OwnedText(text, 0)` forever
  has claimed an authority it never uses. The contract test checks that the
  counter is declared and advanced *somewhere*; that the owner advances it on
  every replacement, and never for a keystroke, is the owner's own contract —
  proved by the draft-state tests.
- **The GenUI renderer builds a source**, and the contract test knows it: the row
  says so. A second such exception would need the same, and that is a signal to
  look for a different shape.
- **The old answer vocabulary is gone.** "none", "the value itself" and "the value
  and a revision token" collapse into two kinds, and the difference between "none"
  and "the value itself" — whether anything rewrites the field at all — moves into
  the prose of the upstream column, where it belongs: it is a fact about the world,
  not about the types.

## Links

- `openspec/changes/the-source-says-who-may-rewrite-the-field/`
- `docs/decisions/2026-10-20-the-task-and-the-project-learn-of-a-rewrite.md`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/TextFieldSource.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/TextFieldStateBridge.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/TextFieldRewriteContractTest.kt`
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/core/ui/components/TextFieldStateBridgeUiTest.kt`
- `.agents/skills/singularity-todo-text-field-states/SKILL.md` — section 9
