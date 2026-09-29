---
title: "Card-level AI actions are deferred: they mutate without preview or undo"
date: 2026-09-30
status: accepted
tags: [tasks, ai, ux, gap]
---

## Context

The unwired-surfaces plan listed **PR-2.6 — "Task AI button on task card (4 call
sites)"**: pass `onAiClick` at `ProjectDetailContent.kt:422`, `SearchScreen.kt:177`
and `ArchiveScreen.kt:67/129`, so the AI button appears on task cards in lists as
well as on the detail screen.

The plan was written from a grep, not from reading `TaskAiSlot`. Reading it changes
the answer, so this records why the PR is not implemented as specified.

## What the AI actions actually do

`TaskAiSlot.execute` maps each `TaskAiAction` to an immediate write:

| Action | Effect |
|---|---|
| `RefineTitle` | overwrites `task.title` with the model's rewrite |
| `GenerateDescription` | overwrites `task.description` |
| `GenerateChecklist` | `createTask` for **each** returned step, parented to the task |
| `Decompose` | `createTask` for **each** returned subtask, parented to the task |
| `SuggestTime` | reports a suggestion, writes nothing |

There is no preview, no confirmation, and no undo for any of them. `onSuccess` emits a
snackbar saying what happened, after the write.

## Idea

Wire it anyway — the button is `onAiClick?.let { AiActionButton(onClick = it) }`, the
callback is already on `TaskCardActions`, and the detail screen already shows it.

## Decision

**Deferred.** `onAiClick` stays `null` at all four list call sites, which renders no
button (`DefaultTaskCardTrailing` only emits it when the callback is non-null). Each
site now carries a comment saying so and pointing here.

## Rationale

The same action has very different meaning at the two surfaces.

On the **detail screen** the user opened the task deliberately, is looking at its
current title, and watches it change. A mistake is visible and correctable — the title
is right there in an editable field.

On a **card in a list** the user is scanning forty rows. Tapping an icon on one of
them would rewrite that row's text with no preview, and for `GenerateChecklist` /
`Decompose` would materialise N real child tasks from a list-row tap. A snackbar
arriving afterwards is a *report*, not a *confirmation* — the write has already
landed by the time the user learns of it. On mobile, where these lists are the primary
surface, the mis-tap rate on a 32 dp icon next to pin and delete is not small.

The plan's own premise was that a missing callback is a defect. That premise holds
when the missing thing is a read or a navigation, and inverts when the missing thing
is a destructive write. Wiring it would have converted a *visible absence* into a
*silent data change* — the exact failure mode the previous ADR
(`2026-09-29-destroyed-but-not-deleted-callbacks.md`) documents as worse than a
missing button.

`SuggestTime` is the one action that writes nothing and would be safe on a card. It
is not split out: a single AI button on a card that silently does nothing for four of
its five options is a worse affordance than no button.

## Consequences

- Task AI remains reachable exactly where it was made reachable (PR-1.6): the task
  detail top bar. Nothing regressed; four sites stay as they are.
- If card-level AI is wanted later, the prerequisite is a preview-and-confirm step —
  show the proposed title, the proposed description, or the list of subtasks, and
  write only on confirm. That is a feature, not a wiring change, and it belongs in its
  own change with its own ADR.
- The four call sites are now commented rather than silently omitted, so the next
  reader sees a decision instead of an oversight.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/viewmodel/slot/TaskAiSlot.kt:55-92`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/TaskCard.kt:107`
- `feature/archive/ArchiveScreen.kt`, `feature/search/SearchScreen.kt`,
  `feature/projects/presentation/screen/ProjectDetailContent.kt`
- Plan reference: v3 §Phase 2, PR-2.6
- Same family: `2026-09-29-destroyed-but-not-deleted-callbacks.md`
