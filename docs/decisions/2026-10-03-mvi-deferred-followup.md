---
date: 2026-10-03
adr: mvi-deferred-followup
deciders:
  - Singularity Developer
status: accepted
tags: [mvi, ui, compose, nav3, refactor]
title: MVI deferred follow-up — routing-when, CurrentProjectContent, @Immutable, lazy keys, tab reselect
---

# MVI deferred follow-up

Closes items 4–7 of `2026-10-03-mvi-refactor-residuals-mr-a` (MR-E retrospective), which
were deferred as "pure refactoring with no correctness impact". They do have impact —
two of them were latent defects, not just style.

## Context

MR-E unified the sheet/dialog primitive and deleted `IntentActions`, but left four items
open. Two were blocked on each other (the routing `when` had to move before
`CurrentProjectContent` could be simplified), and two were pure sweeps. All five are
closed here.

## Decision

### 1. Routing `when` hoisted to a top-level factory

`ProjectDetailContent` built its dispatcher inline:

```kotlin
val actions = remember {                      // ← no keys
    ProjectDetailActions { intent -> when (intent) { … } }
}
```

An unkeyed `remember` captures its collaborators once, at first composition. `nav` comes
from `LocalProjectsNavigator.current`; if the graph re-provides a different navigator the
dispatcher keeps calling the old one. The fix is `rememberProjectDetailActions(sheets, nav,
viewModel)` — a top-level composable with `remember(sheets, nav, viewModel)`. The `when`
stays exhaustive (no `else`): adding a `Routing` variant is now a compile error at one
place rather than a silent no-op inside a lambda.

### 2. `CurrentProjectContent` carries `ProjectDetailActions`, not ten nullable lambdas

The old shape had ten `((T) -> Unit)?` fields that were always passed non-null, so every
use read `currentContent?.onUpdateColor?.invoke(color)` — two safe-calls for one dispatch.
Worse, the class was a `data class`: its generated `equals` compares lambdas by identity,
so two structurally identical instances could never be equal. The type claimed value
semantics it did not have.

Replaced with a plain `@Immutable class` holding the eight values sheets read for display
plus one non-null `actions: ProjectDetailActions`. Two of the ten fields (`onUpdateName`,
`onUpdateDescription`) were never read by any sheet and are gone outright. Dispatch now
goes through the same named helpers the rest of the screen uses, so a typo in a sheet is a
compile error instead of a silent no-op.

### 3. `@Immutable` on the hot UI-state types

Compose treats an unannotated class as unstable, so skip-work never applies to it. The
`sealed interface *UiState` types and their widest read models are now annotated:
`TaskDetailUi` / `TaskDetailUiState`, `ProjectDetailUiState`, `ProjectsUiState`,
`SettingsUiState`, `CalendarUiState`, `AuthUiState`, `AgendaUiState` / `RenderedSection` /
`AgendaRowItem`. Annotating the sealed interface propagates to every variant, so new
variants are covered by default.

This is deliberately not a mass pass. Annotating all ~580 classes is a huge diff with no
logic change, and over-annotating is its own hazard: `@Immutable` is a promise Compose
cannot verify, so a class with a genuinely mutable field would silently skip
recomposition. Hot types only; the rest stays a per-PR judgement.

### 4. Keys on every lazy-list `items(` call

Non-keyed `items(` makes Compose reuse item state by index, so a list that reorders or
inserts shows the wrong row's expansion/selection/animation. All 13 remaining sites are
keyed on the item's stable id — `date` for daily usage, `toolName` / `modelId` for usage
rollups, `id` for tasks/notes/projects/tags/children, and `it` for the fixed 24-hour grid.

`AgendaContent` and `SavedSearchesRow` were already keyed (the earlier sweep miscounted
them by reading a multi-line `key =` as a separate declaration).

### 5. Tab reselect emits an event; screens opt into scroll reset

Tapping the tab you are already on was a no-op: `topLevelRoute` is already that route, so
no state change fires and the tab bar looks unresponsive. `Nav3State` now holds a
`MutableSharedFlow<NavKey>` and `onTabTapped` emits instead of navigating when the route
is unchanged. `Navigator.navigate` delegates to it, so every existing tab-bar call site
gets the behaviour for free.

`TabReselectScrollReset(route, listState)` is the opt-in for screens. It reads
`LocalNav3State` (new composition local, provided by both shells) and filters events to
its own route, so several screens can observe the same flow without coordinating. Only
top-level tabs pass a route.

**No screen is wired yet.** The infrastructure is in place and tested, but wiring each
screen means hoisting its `LazyListState` to the screen root, and that is a per-screen
change better made alongside whatever else that screen is doing. `AgendaContent` grew the
`listState` parameter it needs (it is still the list owner for the saved-agenda results
view); `AgendaScreen` itself was reverted to the multi-select version that landed on
`main` in parallel, which owns its own `Scaffold` and does not go through `AgendaContent`.
Adopting the helper there means re-doing the hoist against that structure — see
Consequences.

## Rationale

Items 1 and 2 were not style. The unkeyed `remember` was a real staleness bug, and the
`data class` with ten nullable lambdas had an `equals` that could never return true. Items
3 and 4 are correctness-adjacent: they do not change behaviour today but they remove
conditions under which Compose can skip a needed recomposition or show a stale row. Item 5
is the only user-visible behaviour change, and it is the platform behaviour users expect
from a bottom bar.

`MutableSharedFlow(extraBufferCapacity = 1, onBufferOverflow = DROP_OLDEST)` is the
upstream recipe's shape, but note the consequence, which the test now pins: with no
subscriber the value is **dropped**, not buffered. That is correct here — a reselect is
only meaningful to a screen that is currently composed and collecting — but it means the
collector must be running before the tap, which is exactly the production order.

## Consequences

- **The digest had a duplicated index.** `Index (slug → tags)` and `Active entries` listed
  the same ~385 slugs side by side, so every new ADR pushed the file 2 lines further over
  budget and the previous batch's response was to raise the cap — the ratchet failure the
  doc policy warns about, one increment per MR. The tags index is gone; tags remain
  reachable through the per-tag sections, and the title is the more useful half of an
  index. `DIGEST.md` went 1553 → 1164 lines and the budget came back down to 1250, so
  there is headroom again instead of a cap that has to be bumped every time.
- **`LocalNav3State` must be provided by any shell** that wants scroll reset. Both
  `DesktopShellNav3` and `AndroidShellNav3` provide it around their `NavDisplay`. A third
  shell that forgets will throw at first `TabReselectScrollReset` call, not silently.
- **Screens opt in individually, and none is wired yet.** Each is one hoist of the
  `LazyListState` to the screen root plus a `TabReselectScrollReset` call — do it per
  screen alongside whatever else changes there, not as a batch. `AgendaScreen` is the
  awkward one: the multi-select rework that landed on `main` in parallel gave it its own
  `Scaffold` and top/bottom bars, so its list is no longer inside `AgendaContent` and the
  `listState` that `AgendaContent` accepts does not reach it. Whoever wires agenda needs
  to hoist the state from the `AgendaContent` call site it now makes.
- **`AgendaDefinition` carries no route.** Anything matching a screen to a tab has to get
  the route from the nav graph (`AgendaNavContent` has it as a parameter), not from the
  definition.
- **Android does not compile** — `WrappingDriver.android.kt:13`, `SQLiteDriver`
  unresolved. Pre-existing on `main` since `9855ad73`, unrelated to this work, and
  therefore also unverified by CI (ADR `2026-10-02-tag-registry-single-source` notes every
  gate has `continue-on-error: true`). The `androidMain` edits here are two
  `CompositionLocalProvider` wrappers and one import, both mirrored on the JVM side that
  does compile and is exercised by `:shared:jvmTest`.
- `@Immutable` is a promise, not a proof. If a future edit makes one of these types hold
  mutable state, recomposition silently stops. The annotation carries that obligation into
  the next PR that touches the class.

## Links

- `2026-10-03-mvi-refactor-residuals-mr-a` — items 4–7, this closes them
- `2026-10-02-routing-state-on-screen` — routing state belongs on the screen, not the VM
- `2026-09-27-nav3-startroute-invariant` — superseded by the MR-A nav ADR
