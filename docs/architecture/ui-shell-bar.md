# Android shell bar and selection mode

The Android shell owns the single TopAppBar. Screens publish through the
platform-aware `TopAppBar` wrapper.

- Normal mode: `TopAppBar` publishes `actions` to `ScreenActionsHolder`; the shell
  renders title/back/search button plus those actions.
- Selection mode: pass `selectionTitle` and `onExitSelection` to `TopAppBar`. The
  shell then shows "N selected" with an exit-selection back button and hides the
  search button. Desktop renders its own bar from the `title`/`navigationIcon`
  params, so the same composable works on both platforms.

## A screen that owns its header

A screen that draws a header of its own — the calendar's month, the agenda's
title with the greeting under it — does not draw a second bar on Android. It
publishes that header instead: `overrideShell = true` on the shared `TopAppBar`,
or `ShellOverride` for a screen without a bar. The shell draws the published
title and navigation icon in place of its own. On desktop the same composable
draws the real bar, so what the user reads is the same on both clients.

`title`, `navigationIcon` and `override` are one publication, written by
`ScreenActionsHolder.publishBar` and taken back by `retractBar`; `override` is
derived from `title`, and a screen cannot set one of them without the others.
The navigation icon is published as a question the shell asks when it draws the
bar, so a screen that starts or stops supplying one — the calendar gaining a
back button — does not keep the answer it gave when it entered.

## Which title a screen's bar carries

The screen that owns the header decides the words, and both clients show the
same ones. The shell's destination name is a fallback for screens that publish
nothing, not a second opinion.

The agenda's bar therefore carries the **agenda's own title**, taken from its
definition, and not the shell's tab name:

- the three tabs read "Inbox", "Today", "Upcoming";
- on a working day the Today tab reads "Work Day" where the tab is the work-day
  preset — the same word desktop has always shown;
- a project or tag list reads the project's or tag's name;
- the saved-view results screen reads the view's name.

The greeting sits under the title in the same publication, so it reaches the
Android bar with it and leaves with it.

## A published header survives navigation

Every publication and every claim is recorded **against the screen that made
it**, and only that screen can take it down. Nothing is a single slot that the
last writer wins: that is what let one screen's leave erase another screen's
header (#2008) and one bar's leave empty another bar's buttons (#1962 review).

- The **header** (`publishBar`, `retractBar`) belongs to the screen that
  published it. Only that screen takes it down, in its own `DisposableEffect`
  when it leaves the composition — and only its own: the publication carries
  the identity of the composable that made it, so a screen leaving during a
  navigation cannot erase the header the arriving screen has already
  published. When the newest header goes, the one behind it takes over: the
  screen behind it is still composed — the agenda is the shell's start route,
  so it stays composed while another tab is on top — and its header is what
  the bar must draw again. This is what brings the agenda's greeting back when
  the user returns to the agenda tab.
- The **action slots** (`claimActions`, `publishSlots`, `clearActions`) belong
  to the bar that claimed them, on the top-level route that bar was composed
  for. The shell asks for the slots **of the route it is drawing**
  (`actionsFor`), so the previous screen's buttons stop being drawn in the
  same composition that swaps the route — without a clear whose correctness
  depends on whether it ran before or after the arriving screen published. A
  bar that never claimed — a screen that publishes a header only, the
  calendar — has no slots of its own and cannot touch anybody else's, and a
  bar that leaves empties only the claim it made itself.

The shell draws a published title and a published navigation icon in its own
composition, keyed by the screen that published them: the content a screen
publishes is drawn content, and what it remembers belongs to that screen.

