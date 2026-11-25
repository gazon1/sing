---
title: "The selected top-level route is saved state, not a remembered value"
date: 2026-11-25
status: accepted
tags: [nav, saved-state, rotation, maestro]
issue: 3191
---

## Context

The shell holds the selected top-level route — the tab, or the menu entry — in a
`MutableState` created by `remember(startRoute) { mutableStateOf(startRoute) }`
(`Nav3StateFactory.android.kt`). The back stacks below it are saved properly:
each graph gets its own `SavedStateConfiguration` and its stack is restored by
`rememberNavBackStack`.

A rotation is a configuration change. `MainActivity` declares no
`android:configChanges`, so the Activity is recreated and the *whole* Compose
state is rebuilt. The back stacks are restored from the saved state; the
selected route is not, because `remember` does not survive recreation. The shell
falls back to the start route (`Today`), the stack that was open — say the Inbox
with the task editor on top — is never composed again, and the app looks reset
rather than broken.

`Maestro/flows/lifecycle/03-rotate-in-editor.yaml` is the alarm for exactly
this, and it failed with
`Assertion is false: id: task_editor_title_input is visible`. The contract it
checks is already written down: ADR
`2026-09-29-saved-state-config-must-be-per-graph.md` says that "rotation and
process-death restore keep the nested screen again". The flow was not the
defect; the shell was.

## Idea

1. **Save the route like a back stack.** Give the top-level route a saver built
   on the same polymorphic `NavKey` registration the stacks use, and hold it in
   `rememberSaveable`.
2. **Save nothing and let the flow change.** Narrow the flow to something that
   does not survive a rotation, and record the product gap as an accepted
   exception.
3. **Keep the route in the Activity instead of Compose.** Hold it in a ViewModel
   so it outlives the recreation.

## Decision

**Option 1.**

`navKeySaver(allowedRoutes)` (in `Nav3SavedState.kt`) encodes one `NavKey` with
`encodeToSavedState` under the app's own `navSavedStateConfig()`, and the shell
holds the selected route in `rememberSaveable(stateSaver = navKeySaver(...))`.
Two guards make the restore total rather than hopeful:

- a payload this build cannot decode restores as `null` instead of throwing, so
  an old saved state degrades to the start route rather than taking the app
  down;
- a restored key outside `allowedRoutes` — a route that is no longer a top-level
  route — is refused, because the shell would otherwise point at a route it has
  no stack for.

`Nav3State`'s invariant moves with it. It used to require that the state
initialise to the *start route*; now it requires that the route have a *back
stack*. The restored route is the one the user was on, and the restored
Inbox stack is the one the app has to show; requiring the start route would
reject exactly the state the saver produces.

## Rationale

- **The contract is already in an ADR.** The rotation contract says the nested
  screen is kept. Option 2 would silently retire a flow that exists to guard a
  documented product promise, and would turn the debt registry into a graveyard
  for regressions that are still regressions.
- **The route is a piece of navigation state, and the rest of the navigation
  state is already saved.** Saving one key and not the others is the kind of
  asymmetry that produces exactly this bug: the bug is invisible in code review
  because every individual line looks right.
- **Option 3 moves the state out of Compose but not out of the Activity.** A
  ViewModel scoped to the Activity survives the configuration change, but the
  saved state is what survives *process death*, and the contract names process
  death too. A saver is the mechanism the rest of the shell already uses.
- **The saver is the app's own.** `navSavedStateConfig()` registers
  `subclassesOfSealed(AppNavKey.serializer())` under `NavKey`, so a route the
  stacks can restore this can restore too. A hand-written `when` over the route
  would be a second registration to keep in step with the first.

## Consequences

- `lifecycle/03-rotate-in-editor` passes again, and stays as the alarm: the
  selected tab survives the rotation, the Inbox stack is composed again, and the
  editor is where the user left it.
- `Nav3State` accepts a route other than the start route. The invariant that
  remains — the route must have a back stack — is the one that keeps the screen
  from silently disappearing.
- A saved state this build cannot decode falls back to the start route rather
  than crashing the app on recreation.
- **The tab *history* is not restored; only the tab is.** The route the user was
  on is saved; the route they were on before that one is not. `Nav3State` keeps
  `_previousTopLevelRoute` in memory, and the recreated shell starts it at the
  start route. So Back at the root of a tab returns to the start route after a
  rotation, not to the tab that was active before the one you switched to. This
  is the contract, not an oversight: the previous route was never saved state,
  and before this change the rotation reset the tab itself, so the history was
  lost anyway. Saving it as well would mean turning a private field into
  observable state and widening the `Nav3State`/`Navigator` API for a route the
  user is unlikely to notice — a wider surface for a behaviour nobody asked
  for. If the tab history is ever wanted, it is its own decision.
- The desktop shell is unaffected: `rememberNavBackStackTyped` uses
  `rememberInMemoryNavBackStack` there, and the top-level route on desktop is
  held in memory.

## Links

- Issue: https://github.com/gazon1/sing/issues/3191
- Alarm: `Maestro/flows/lifecycle/03-rotate-in-editor.yaml`
- Contract: `docs/decisions/2026-09-29-saved-state-config-must-be-per-graph.md`
- Related: `docs/decisions/2026-09-29-single-sealed-navkey-root.md`,
  `docs/decisions/2026-10-28-the-keyboard-was-already-down.md`
