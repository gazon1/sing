# Design — the review is a place you can go

The rationale for this change lives in
`docs/decisions/2026-10-22-the-review-is-a-place-you-can-go.md`. This file records the
shape of the implementation and what would have to be undone.

## The shape

- `feature/proposals/presentation/ReviewViewModel.kt` — observes the pending
  proposals, exposes the waiting changes, and turns "apply" and "no" into calls on
  the app's own `ApplyProposalItemUseCase`.
- `feature/proposals/presentation/ReviewScreen.kt` — the list. Each change is a row
  with two buttons.
- `feature/ai/tools/FirstStepTool.kt` — the tool, and the prompt behind it in
  `Prompts`.
- `feature/nav/` — one destination, its icon, its place in the menu, its family, and
  one entry in the shared entry provider.

## Why the screen lives in the proposals feature and not in a new one

It is a list of proposals. The proposals feature already owns the repository, the
apply path and the item model, and a new feature would have wanted a new navigation
family and its own routing rules for a screen that holds no state of its own.

## Why the destination is in the assistant's family

The screen shows what the assistant proposed, so opening it from the chat is a push
within one feature rather than a cross-feature switch. The family classification is an
exhaustive `when`, so a destination that was not classified would not compile.

## Rollback risk

**Low.** The change adds two files for the screen, one for the tool, and one
destination. Undoing it means deleting the files, removing the destination (which the
exhaustive `when` in `ScreenFamily` and `AppDestination` will point at), and removing
the tool from the app's list and the server's catalog.

The one thing that is not trivially undone is the proposals that now have somewhere
to go: the ones the delete tools made, and the ones the chat makes, were always
stored. This change only makes them reachable.
