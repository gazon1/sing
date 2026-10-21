---
title: "The sheet host maps the test tags, and the gate asks the host"
date: 2026-10-28
status: accepted
tags: [testing, compose, maestro, ui-automation, android]
---

# The sheet host maps the test tags, and the gate asks the host

## Context

A sheet renders into its own Android window. Android maps a Compose `testTag`
to a UIAutomator `resource-id` only where the semantics property
`testTagsAsResourceId` is set, and the app root sets it once — which covers
the main window and nothing else. The 2026-10-04 decision
(`2026-10-04-testtag-visibility-helper.md`) introduced
`Modifier.mapTestTagsAsResourceIds()` for exactly this, and the rule it left
behind was a *file-level* rule: "a file that opens a window and contains a
testTag must apply the helper".

The file-level rule has a blind spot, and it is the shape most of this
project's sheets have. The file that opens the window is not the file that
holds the content:

| window | file that opens it | file that holds the tagged controls |
|---|---|---|
| `TaskEditorSheetHost` | `feature/tasks/…/TaskEditorSheetHost.kt` | `RecurrencePickerSheet.kt`, `TagsPickerSheet.kt`, `ProjectPickerSheet.kt`, `AttachmentsSheet.kt`, `ChecklistEditorSheet.kt`, `DependencyPickerSheet.kt`, `TaskEditorPrioritySheet.kt` |
| `BottomSheetHost` | `core/ui/components/sheet/BottomSheetHost.kt` | `DatePickerSheet.kt`, `ListPickerSheet.kt`, `MultiSelectSheet.kt` |

The host's file contains no `testTag(` of its own, so the file-level rule sees
nothing and says nothing — while the tagged controls sit in a window whose
mapping nobody asserted. The natural "fix" is to add the helper to each of
the eight content files: eight chances to forget one, and a check that says
nothing about the nine files that would come next.

## Idea

Make the *host* the unit. A host renders content it did not write, so the
host is the only place that knows which window the content will land in. The
host asserts the mapping, once; the content files stay free of an Android
automation concern they cannot own.

## Decision

**The host maps.** `BottomSheetHost` applies
`Modifier.mapTestTagsAsResourceIds()` to the sheet it opens.
`TaskEditorSheetHost` already did.

**The gate asks the host.** The file-level rule stays — it still owns the case
of a window whose content is written in the same file. A second rule asks a
different question of the same windows: *does this window render content it
did not write itself?* If it does, the mapping has to be in that file, because
the file that wrote the content cannot know which window it will end up in.

The rule is precise about what "content" is. It looks for a call to a
**composable parameter** of the enclosing function: `content()` where the
parameter is declared `@Composable … -> Unit`. A plain callback
(`onDelete()`) is not content, and a window that calls one is not a host.

### The property is inherited, not applied per node

Compose reads `testTagsAsResourceId` from a node's *ancestors*: the
accessibility delegate walks up the semantics tree of the window
(`AndroidComposeViewAccessibilityDelegateCompat`, Compose 1.12.0), and stops
at the first ancestor that sets it. So asserting it once, on the sheet's root,
covers the whole subtree — including content written in another file.

Measured on the device (2026-10-28), with the priority sheet open:

```
resource-id=priority_option_none   text=''        ← the id IS mapped
resource-id=priority_option_high   text=''
text='No priority'                                  ← the label is there too
```

The ids inside a `ModalBottomSheet` reach UIAutomator as resource-ids, and the
sheet's plain text is in the tree as well.

## Rationale

The eight-file version and the one-file version produce the same tree. Only
one of them survives the next sheet: the one that does not depend on someone
remembering. That is the whole argument, and it is the same argument the
2026-10-04 decision made for dialogs — the file simply moved from "the file
that opens the window" to "the window's owner", which is a rule about
*windows* rather than about files.

The gate has to follow the change, or it checks a shape that no longer
exists. A file-level rule that demands the helper in eight content files
would be satisfied today and would keep eight places to forget; a host-level
rule asks the one question that matters — *who owns this window?* — and the
answer is the host.

The rule is deliberately narrow about "content". An earlier draft matched any
lower-case call in the window's body and found 24 candidate hosts, 12 of them
false positives (`rememberTextFieldState()`, `onDismiss()`). The type is the
discriminator: a composable parameter is content, a callback is not. With the
type in the rule, the tree has exactly two hosts — `BottomSheetHost` and
`TaskEditorSheetHost` — and both map.

## Consequences

- `BottomSheetHost` covers every sheet that uses it, including the ones that
  do not exist yet.
- The eight content files do not carry the helper, and do not need to.
- The new rule has a positive control that feeds it both shapes: a host that
  renders caller content, and a window that renders its own — plus the
  callback case, so the rule cannot be widened into crying wolf.
- A sheet opened by a `ModalBottomSheet` in a *feature* file (there are
  seventeen call sites) is still covered by the file-level rule, which demands
  the helper in that file when the file also holds tags. That is unchanged.
- The measured result contradicts the premise this work started from: the
  sheet content was reachable, and the 2026-10-21 failures in that area had a
  different cause. The five `attachments-*` flows failed because the
  attachments sheet has no trigger in the UI at all — `AttachmentsCallbacks.onOpen`
  is never invoked — not because the ids were lost. That is filed separately.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/sheet/BottomSheetHost.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/TaskEditorSheetHost.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/UiAutomationSelectorTest.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTagResourceId.kt`
- `docs/decisions/2026-10-04-testtag-visibility-helper.md`
- `docs/decisions/2026-10-28-one-test-tag-per-node.md`
