---
title: "An allow-list of handler names is a smaller snapshot of the same wrong idea"
date: 2026-10-07
status: accepted
tags: [tooling, testing, ci]
---

# An allow-list of handler names is a smaller snapshot of the same wrong idea

## Context

`NoEmptyOnClickLambda` exists to catch one thing: a control a user can press
that does nothing. Until 2026-10-07 it reported **zero** findings on a codebase
containing at least eight of them.

The rule's KDoc has always said the gate is the *parameter* being an event
handler. The implementation had drifted away from that in two steps:

1. It was gated on an 11-name allow-list of **callees** — `IconButton`, `TextButton`,
   … A call to anything not on the list was invisible.
2. That list was replaced by a list of ten **parameter names** — `onClick`,
   `onConfirm`, `onDelete`, `onDismiss`, `onRetry`, `onSave`, `onBack`, `onToggle`,
   `onEdit`, `onCheckedChange`. Better, and still wrong in the same way.

The commit message that introduced the callee list explained the removal of the
older composable-function allow-list precisely: an allow-list only ever sees the
call sites somebody already thought to enumerate. The ten-name list reproduced
that defect one level down. Five live defects sat outside it:

| Handler | Site | What the user got |
|---|---|---|
| `onAttachFile` | `TaskCreateScreen` | No way to attach a file, at all |
| `onAiAction` | `AgendaNavGraph.jvm` | An enabled AI submenu whose five items did nothing |
| `onWriteNote` | `TaskDetailContent` | A "Write note" chip that did nothing |
| `onAddChecklist` | `TaskDetailContent` | An "Add checklist" chip that did nothing |
| `onCheckToggle` | `TaskCreateScreen` | A completion checkbox that did nothing |

Widening the gate to the shape `on` + uppercase letter reported **18** findings
across 13 files, of which these five were among them.

## Decision

The gate is a **shape**, not a list: `on` followed by an uppercase letter, which
is the convention this codebase already uses for callbacks. It is a strict
superset of the ten names, and it covers names that do not exist yet.

Two names are exempt, and both exemptions are argued rather than tolerated:

- **`onSuccess` / `onFailure`** — `kotlin.Result.fold`'s parameter names. `fold`
  names them, so no caller chooses them, and an empty `onSuccess` branch *is* the
  reason to call `fold`. All 40+ occurrences in the repository are fold labels;
  none is a callback.
- **Empty `onValueChange` beside `readOnly = true` on the same call** — a read-only
  field cannot produce a value change, so the handler is unreachable by
  construction rather than unwired. Material3 still requires the parameter.

Both are pinned by tests, including the near-miss controls (`onResult`,
`onSuuccessful`) that stop either exception from growing into the new stale list.

## Two holes the widening exposed

**A preview function's *name* was never checked.** The exemption walked out to the
enclosing declaration and `break`ed at the function boundary *before* testing the
name, so `fun SettingsScreenPreview()` was exempt only if it also carried
`@Preview`. It did not. Three findings on a preview — all false — is how a rule
teaches a reader to ignore it.

**`noopClick` is `internal` to preview code**, which means production call sites had
no sanctioned way to express a deliberate no-op. The fix was not to widen that
constant but to let the parameters *be null*:

- `TaskEditor{Priority,Estimate,DueDate}Row` dropped `.clickable(onClick = x ?: {})`
  for TaskChip's existing conditional pattern. A dead click handler still renders a
  ripple; no `clickable` at all does not.
- `onDataChange` on the GenUI data context became nullable. Its own KDoc had always
  said a read-only surface may ignore it — but a non-null parameter forced
  `{ _, _, _ -> }` at both read-only call sites.
- `FirstRunSection`'s two dead chips became nullable, so a chip that cannot act is
  not rendered.

## `find-unwired-surfaces.py`: a `@Preview` is not a call site

Detector 1 asked "does any file mention this name?", and a component's own previews
answered yes. `ReminderTile` is fully implemented, previewed twice — light and dark
— and called by nothing outside its own file.

The corpus is now counted with preview bodies blanked (`_without_previews`), which
covers both the `@Preview` annotation and this project's `PreviewSamples` convention
(a function named for its role calling `PreviewThemed`, with no annotation).

The obvious cheaper implementation — "count references in *other* files" — was
written, run against the real tree, and **wrong**: it reported `TagCard`, which is
called by `TagList` in the same file, which is called by the screen. A correct
implementation flagged for deletion is worse than no check. Co-location is not
preview-ness, and `test_a_caller_in_the_same_file_is_a_caller` pins it.

## Consequences

- The gate reports live defects instead of zero. Each one had to be *decided* —
  wired, made nullable, or recorded — which is the cost of a gate that measures.
- Two new exemptions exist. Both are narrow, argued in the source, and covered by a
  near-miss negative control, because an exemption that cannot be tested is the same
  defect this rule was written to catch.
- `ReminderTile` is reported and baselined against a deferred-backlog entry rather
  than deleted: deciding which row the reminders screen should render needs the
  screen, and deleting working code to satisfy a gate is what this ADR's predecessor
  warned against.
- The suffix list gained `Tile`, `Row` and `Dialog`. Widening it alone changed
  nothing, because those components were self-referenced — the preview-body fix is
  what actually moved the count. Recorded here so the next person does not credit
  the suffixes for the findings.
