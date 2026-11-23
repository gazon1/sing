---
title: "The primary tab roots draw no Back arrow"
date: 2026-11-23
status: accepted
tags: ["navigation", "nav3", "shell", "android", "desktop", "calendar"]
---

# The primary tab roots draw no Back arrow

## Context

The Inbox, Today and Calendar roots are where a primary tab starts. They are
not screens in a back stack: the user asked for the tab, and the tab is where
they are. A Back arrow on such a root promises a step back that the root has no
meaning for.

The shells drew one anyway. Both derived their leading control from the
navigation state alone — `navigator.canGoBack()` — and that state says "there is
something to pop" whenever the top-level route differs from the one below it.
After a tab switch it always says so: the state remembers the tab the user came
from. So the arrow appeared on the Inbox and Today roots after any tab switch,
and on the Calendar root for a second reason — the calendar publishes its own
navigation icon to the shell bar (Android), and it published one unconditionally.

The navigation state is not wrong, and nothing here changes it: system Back,
the NavDisplay back, the nested calendar's own navigation and the editor return
flows keep working exactly as before. What was wrong is the *affordance* — the
root offered a step it cannot take.

On the same day, and for the same reason of visible affordance, another decision
went the other way on Desktop: the shell there draws one back control *always*,
and at the ultimate root of the start tab that control takes the window's close
path, because a control that is never drawn is a policy a user cannot reach
(`2026-11-23-the-desktop-shell-draws-one-back-control.md`). The two meet at the
start tab's root: this decision says the three primary roots draw no back arrow
on Desktop too, so the root has no visible close control any more.

## Idea

- Classify the screen on top by its route, not by its title.
- Keep that classification in one place, so Android and Desktop cannot drift.
- Leave the navigation state alone: the arrow disappears, the navigation does
  not.

## Decision

**A shared policy decides whether the screen on top is a primary tab root, and
both shells honour it.**

- `shell/PrimaryTabRoot.kt` (commonMain) holds `isPrimaryTabRoot(target)`: the
  Calendar, and the agenda graph on its Inbox or Today route, are roots;
  everything else is not.
- The decision is made on the destination the shell already resolves for its
  floating button — `shellFabTarget(current, topMostRoute, agendaRouteHolder.route)`
  — so the route inside the agenda graph (the one the graph publishes, see
  `AgendaRouteHolder`) decides, and the saved views and the project and tag
  views keep their own Back.
- Both shells draw their Back arrow only when the navigation state can pop
  **and** the screen is not a primary tab root. Android keeps the Menu button in
  the leading slot at a root — the control the shell already draws there — and
  Desktop keeps its drawer button.
- The calendar publishes no navigation icon at its root: `CalendarNavigator.canGoBack()`
  says whether the calendar's own stack holds anything behind the screen on top,
  and the root (the month the tab opens on) holds nothing. A nested route — a
  day — is pushed on top of it and keeps the arrow that returns to the root.
- On Android the nested day publishes that arrow to the shell's bar. On Desktop
  the calendar has no shell bar to publish to, so the calendar's own bar draws
  the arrow and claims the back control (`PublishScreenBackControl`), which
  stands the shell's own control down. The arrow belongs to the calendar: the
  shell's arrow pops the shell's stack, and a day sits on the calendar's stack.

## Rationale

- One owner for the decision. Android and Desktop asked the same question
  before and could have drifted apart; now they ask the same function.
- The route is the truth. A title or a label is a string, and a screen that
  renames itself would change what the shell draws. The agenda's route is
  published by the graph that owns it, so the shell asks the one place that
  knows.
- The state is not the bug. The navigation state keeps saying what it says;
  the root simply does not offer a step that the state would allow. That is why
  nothing about popping, the system Back handler or the window-close policy
  changed.
- The calendar owns its own stack. Whether the calendar has somewhere to go
  back to is a fact about the calendar's stack, not about the shell's — so the
  calendar answers it and publishes the arrow accordingly.

## Consequences

- The Inbox, Today and Calendar roots show no Back arrow, on Android and on
  Desktop, and the state that produced the arrow is untouched.
- Agenda subroutes (saved views, project and tag views) keep their Back, and so
  does every destination that is not one of the three roots — including the
  Upcoming tab, which the issue does not name.
- A screen that publishes its own navigation icon still wins: the saved-view
  screens and a nested calendar route keep the arrow they draw themselves.
- On Android the root's leading slot holds the Menu button, which is what the
  shell already draws at the root of the app.
- The Upcoming tab is a primary tab like the others and still draws a Back
  arrow after a tab switch. The issue names three roots, and widening the
  policy to every tab is a decision for the issue that asks for it.
- On Desktop the primary roots lose the visible close control they had since
  the one-back-control decision: the window's own close path is unchanged — it
  is the host's close request, still proved by `DesktopBackTest` — but there is
  no longer a button on screen that offers it there. The desktop has no system
  Back to fall back on, so what is left is the window's own close button and
  the File ▸ Quit item.
- On Desktop the nested calendar day draws its arrow in the calendar's own bar,
  not in the shell's bar: the day sits on the calendar's stack, and the shell's
  arrow pops the shell's.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/shell/PrimaryTabRoot.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/shell/FabActionResolver.kt`
- `shared/src/androidMain/kotlin/com/singularity/todo/AndroidShellNav3.kt`
- `shared/src/jvmMain/kotlin/com/singularity/todo/shell/DesktopShellNav3.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/calendar/presentation/nav/CalendarNavigator.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/calendar/presentation/screen/CalendarScreen.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/calendar/presentation/components/calendar/CalendarTopBar.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/shell/PrimaryTabRootTest.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/calendar/presentation/nav/CalendarNavigatorTest.kt`
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/desktop/PrimaryTabRootBackTest.kt`
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/calendar/presentation/screen/CalendarScreenBackTest.kt`
- `docs/decisions/2026-11-12-the-shells-button-follows-the-route-inside-the-agenda-graph.md`
- `docs/decisions/2026-11-23-the-desktop-shell-draws-one-back-control.md`
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/desktop/DesktopBackClosesWindowTest.kt`
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/desktop/DesktopSingleBackControlTest.kt`
- [#3093](https://github.com/gazon1/sing/issues/3093)
