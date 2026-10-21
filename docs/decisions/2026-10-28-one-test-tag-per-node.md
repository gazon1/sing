---
title: "One test tag per node, and a gate that says so"
date: 2026-10-28
status: accepted
tags: [testing, compose, maestro, ui-automation, android]
---

# One test tag per node, and a gate that says so

## Context

The 2026-10-21 device run reported 39 failing flows out of 94. Four of them
(`smoke-delete-task`, `search-filter-pinned`, `tasks-time-tracking-start-stop`,
`tasks-open-task-detail`) failed on the same thing: an element that does not
exist.

They asked for `task_detail_title` and `task_detail_pin_button`. Both constants
were in `TestTags.kt`, both were *applied* — and neither was ever in the
semantics tree.

The reason is structural. `TaskTitleRow.kt` put two `testTag` calls on one
node:

```kotlin
.testTag(TestTags.TASK_EDITOR_TITLE_INPUT)
.testTag(TestTags.TASK_DETAIL_TITLE)
```

A Compose node has one test tag. The semantics merge keeps the outer one and
drops the second, silently: no error, no warning, and the constant still sits
in `TestTags.kt` looking perfectly correct. The desktop Compose tests read the
semantics tree directly and see the *first* tag, so they pass. The flow asks
for the second name and can never pass — and the failure says "element not
found", which reads like a product bug and sends the next person to the UI
instead of to the tag.

Measured on the device (2026-10-28, `uiautomator dump` of the task editor):
the title field carries `resource-id=task_editor_title_input` and no second
resource-id. The second tag was never there.

Two gates could have caught this and neither did. `UiAutomationSelectorTest`
checked that a *file* opening a window maps its test tags, and that no tag is
built from a translated label. Nothing looked at how many tags a node carries.
`validate-maestro-selectors.py` checked that every id a flow uses is *declared*
— and `task_detail_title` was declared. The gap is between the two: the
constant existed, the flow used it, and no check asked whether a node could
ever carry it.

## Idea

Two changes, one of them a gate.

**A. One tag per node.** A node that needs a second name gets a second node.
The dead constants go away, and the flows that used them assert what they
actually meant to assert: the *value* of the title field.

**B. A gate that counts the tags on a node.** The rule reads the modifier
chain: two `testTag` calls separated only by other modifiers are on one node.

## Decision

**A.** The second tag is removed from both sites
(`TaskTitleRow.kt`, `TaskEditorContent.kt`), the two dead constants are deleted
from `TestTags.kt`, and the four flows assert the title's *value* instead.

**B.** `UiAutomationSelectorTest` gains `no node carries two test tags`, with a
positive control that feeds the rule the shapes it must catch — adjacent calls,
calls across lines, a modifier between them — and one it must not (two calls in
different expressions).

### The value needs no second name

The obvious objection to removing `task_detail_title` is that the flows wanted
to assert *which* task the editor opened, and the field's id says only that
*a* title field is open. The answer is the value itself: the field publishes
it as the node's text.

Measured on the device (2026-10-28), the title field in the task editor is:

```
resource-id=task_editor_title_input  text='Buy milk'  class=EditText
```

So `assertVisible: { text: "Buy milk" }` answers exactly the question the
dead id was asking. No `contentDescription` is added: it would put the same
string into the tree twice, and a screen reader would announce the title
twice, for a selector that the tree already carries.

## Rationale

A gate that cannot fail is worse than no gate, because it is read as
evidence. The rule therefore has to have a positive control, and the control
has to feed the rule the *shape that actually happened* — the second
`testTag` right after the first — not a shape the pattern happens to match.

The same argument applies to the value: the premise behind the old workaround
("a `BasicTextField` publishes its value as `editableText`, and UIAutomator
does not surface that as the node's text") is false on this version of
Compose. The value is bridged to the node's text. Adding a `contentDescription`
on the strength of that premise would have added a second copy of the title to
every screen reader's output, and the flows would have kept asserting an id
whose only content was "the title is somewhere".

The rule is deliberately narrow. It reads the chain between two `testTag`
calls, so two calls in different expressions — different nodes — are not a
violation. The known limit is a modifier with a trailing lambda between the
two tags (`.testTag(A).semantics { … }.testTag(B)`), which the pattern does
not recognise; the shape that actually happened is recognised, and the control
proves the rule still fires.

## Consequences

- `task_detail_title` and `task_detail_pin_button` are gone. Four flows assert
  the title's value instead, which is a stronger statement: they check the
  editor shows *this* task, not merely that an editor opened.
- The gate fails on the next double tag, before a flow is written against it.
- A node that genuinely needs two names needs two nodes. That is a small cost
  paid once, against a class of failure that costs a debugging session.
- The `contentDescription` route stays available for a field whose value is
  *not* exposed as text. It is not needed here, and the device says so.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/detail/TaskTitleRow.kt`
- `.../detail/TaskEditorContent.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/UiAutomationSelectorTest.kt`
- `Maestro/flows/tasks/open-task-detail.yaml`, `Maestro/flows/tasks/04-delete.yaml`,
  `Maestro/flows/tasks/18-time-tracking-start-stop.yaml`, `Maestro/flows/search/05-filter-pinned.yaml`
- `docs/decisions/2026-10-28-the-sheet-host-maps-the-tags.md`
