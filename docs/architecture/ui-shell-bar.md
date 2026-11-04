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

`ScreenActionsHolder.onTopLevelRouteChanged()` clears the transient slots
(`actions`, `selectionTitle`, `onExitSelection`) because a screen without a bar
of its own never publishes them; the previous screen's buttons would otherwise
stay in the bar. The shell calls it in composition, ahead of the entries it
draws, so the arriving screen's own publication has the last word of the frame.

It does **not** clear `title`/`navigationIcon`/`override`. Those belong to the
screen that published them, and only that screen takes them down, in its own
`DisposableEffect` when it leaves the composition — and only its own: the
publication carries the identity of the screen that made it, so a screen leaving
during a navigation cannot erase the header the arriving screen has already
published (#2008).

The action slots are claimed and released under the same rule: a bar claims them
as its composition enters (`claimActions`) and releases them as it leaves
(`clearActions`). A bar leaving during a transition therefore does not empty the
slots of the bar arriving, and a screen that publishes a header only — the
calendar — claims no slots, so the previous bar's leave still empties its own.
