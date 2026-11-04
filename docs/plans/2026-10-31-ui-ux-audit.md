# UI/UX Audit — Singularity Todo KMP

**Audit date:** 2026-10-31
**Audited build:** `ae254d973` (the `main` of that day), built and installed on a 360 dp Android phone
**State of the findings checked:** 2026-11-04, against `f07faf5f7`

**Method:** every top-level destination was walked on the device and captured, then the UI
sources were read, and every finding was checked against the whole issue tracker. Nothing that
was already filed was repeated.

**The verdict, in the audit's own words:** the app looks tidy and grown-up — one palette,
meaningful icons, undo snackbars, empty states, light and dark themes. It is above the average
for a mobile todo. It is not *light*, though: every screen carries its own bar inside the shared
frame, five bottom-bar items lead to thirteen more, and three of the five show overlapping data.

## Provenance, and how to read the states

The audit's own report was never committed: `docs/plans/2026-10-31-ui-ux-audit.md` did not
exist at any commit in this repository, and the audit survives only as issue #1747. This document
restates that issue's findings and adds, for each one, what the base commit says today and which
issue owns it. Where the two disagree, the base is the truth.

The states are read from the base commit, not from a device:

- **fixed** — the base contains the change; the commit that made it is named.
- **open** — an open issue carries the finding.
- **unowned** — nothing in the tracker carries it.

Where the base's own sources could be read to confirm a finding, the section says so.

Three of the findings were true of the audited build and are fixed in the base — #2, #4 and #10.
The compile break of finding 1 was already fixed before the audited build was cut, so it was not
what the audit walked. Both are called out where they occur.

## The findings at a glance

| # | Finding | State | Where it is tracked |
|---|---|---|---|
| 1 | `main` did not compile | the known break was fixed before the audited build | #1717 |
| 2 | the status-bar icons followed the system theme | **fixed** | — |
| 3 | AI Chat opened on a blank screen | **unowned** | #2223 |
| 4 | the Upcoming tab had no "Add task" button | **fixed**; the refinements are open | #1958, #2029, #2030, #2057, #2059 |
| 5 | the agenda greeting is never drawn on Android | **open** | #1753, #1939, #1962, #2011 |
| 6 | the task editor puts due date and priority below three empty cards | **open** | #1754, #1942, #2010 |
| 7 | the desktop lists have no scrollbar | **open** | #1755 |
| 8 | the desktop app never takes the keyboard focus | **open** | #2016, #2060, #2092 |
| 9 | the mouse cursor does not change over clickable rows | **open** | #1757, #1946, #1947, #2018 |
| 10 | "Quick search" was the only menu entry without an icon | **fixed** | — |
| 11 | Inbox, Today and Upcoming are nested subsets of each other | **open** | #1948, #1949, #1950, #1954, #2022 |
| 12 | smaller presentation polish | **open** | #1761 |

---

## 1. `main` does not compile — two independent errors

**What the audit found:** `main` did not build at the time of the audit, and the report names two
independent errors.

**What the base says:** the compile break that was open on `main` on 2026-10-31 is the one the
repository audit of the same day filed as #1717 — the shell bar's override wrote three
properties the holder did not have. It was fixed in `ac5025ff8` ("fix(ui): the shell bar
override publishes into the holder it writes to"), and that commit is *already inside* the
audited build `ae254d973`. The base carries it too: `ScreenActionsHolder` has `title`,
`navigationIcon` and `override`, `ShellOverride` publishes exactly those and invents no
`actions` parameter, `SettingsScreen` passes `overrideShell = true` to a `TopAppBar` that
accepts it, and `TagsScreen` imports `ImmutableList` from one package.

**State:** the known break is fixed and the base carries the fix. The audit's own "two independent
errors" are not named in #1747, so they cannot be reproduced from the audit's text, and no issue in
the ready-for-agent queue reports a compile break. This document does not claim the base compiles
— nothing here was built.

## 2. The status-bar icons follow the system theme, not the app theme

**What the audit found:** the system bars were drawn for the operating system's theme, so they
could disagree with the palette the app had painted.

**What the base says:** the bars now follow the app's own theme. `App.kt` resolves the theme's
polarity and calls `enableEdgeToEdge` with `SystemBarStyle.dark` or `SystemBarStyle.light`
accordingly, keyed by that polarity so the window is touched when the theme changes and not on
every recomposition. The change landed in `62d6419f2` (2026-11-02), after the audited build.

**State:** **fixed**. The device-side proof of the shell's bars and its content padding on a
360 dp phone is still owed to #2165, #2166 and #2168.

## 3. AI Chat opens on a blank screen

**What the audit found:** opening AI Chat from the menu gave a blank screen.

**What the base says:** the route was bound at the audited build and is bound in the base —
`AppDestination.AiChat` has an entry in the shared navigation entries that draws `ChatScreen()`,
and the screen itself draws a top bar, a message list and an input bar. So the blank screen was
not a missing route.

What the code does show is a plausible visible cause of an empty screen: a fresh conversation has
no empty state. The message list draws message items and a loading indicator and nothing else, so
with no messages and no turn running the area above the input bar is empty — which is what a
reader sees first, and it reads as blank.

The one place in the tree that still says the route is not bound is a doc comment in the desktop
chat test, which is #1936's to correct.

**State:** **unowned** — nothing in the tracker carried the visible symptom. #2006 asks for a
desktop flow that reaches the chat screen, and #1902 for a device-verified flow over the feature;
neither is about what the user sees when the conversation is empty. Filed as #2223 from this
document.

## 4. The Upcoming tab has no "Add task" button

**What the audit found:** the agenda's Upcoming tab offered no way to create a task.

**What the base says:** the shell's FAB resolver now offers "Add task" on the Upcoming tab —
`AgendaStartRoute.Upcoming` is handled alongside Inbox, Blocked, Stagnant and Overdue, and both
platforms use that resolver, so the Android shell draws the button too. The audited build's
resolver did not mention Upcoming at all, so the finding was true of the build the audit walked.

**State:** **fixed** for the tab itself. The refinements around it are open: the drill-down lists
have no such button (#1958), every task-listing route should be classified and given one
(#2057), the tab's button should pre-fill tomorrow so the task lands in Upcoming (#2030), the
scenario needs an OpenSpec entry (#2029), and a guard is needed so a tab cannot silently lose
the button again (#1959).

## 5. The agenda greeting is never drawn on Android

**What the audit found:** the agenda published its actions but not its title, so the greeting
never reached the app bar.

**What the base says:** still true. `ShellOverride` — the composable that publishes a screen's
own bar content into the shell — is used in one place only, the calendar's top bar. The agenda
computes a greeting (`AgendaUiState.greeting`) and never publishes it.

**State:** **open** — #1753, with the device-level checks in #1939 and #1962 and the title
precedence in #2011.

## 6. The task editor puts the due date and priority below three empty cards

**What the audit found:** in the task editor the attribute rows — due date, priority, project —
were pushed below three empty "add" cards.

**State:** **open** — #1754, with the split of the extra-sections slot in #1942 and the
dependencies card in #2010. #1940 carries the 360 dp check that the attributes are visible
without scrolling.

## 7. The desktop lists have no scrollbar

**What the audit found:** a long list on the desktop could not be told apart from the end of the
list.

**What the base says:** still true — the tree contains no scrollbar at all.

**State:** **open** — #1755.

## 8. The desktop app never takes the keyboard focus

**What the audit found:** the desktop window did not take the keyboard focus, so typing went
nowhere.

**State:** **open** — the focus rule lands in #2016, screen by screen in #2060, and the Android
carrier in #2092.

## 9. The mouse cursor does not change over clickable rows

**What the audit found:** a clickable row on the desktop carried the default arrow.

**What the base says:** still true — the tree contains no pointer-icon handling.

**State:** **open** — #1757, with the wider surfaces in #1946, the guard in #1947 and #2018, and
the menu bar entries in #2020.

## 10. "Quick search" is the only menu entry without an icon

**What the audit found:** every menu entry carried an icon except the quick search one, which
read like a section heading.

**What the base says:** fixed — the search entry takes the same icon the search action uses in
the shell (`fe808c5f3`, 2026-11-01).

**State:** **fixed**.

## 11. Inbox, Today and Upcoming are nested subsets of each other

**What the audit found:** the three agenda tabs are nested subsets of each other — one of them
contains another — so a tab's name does not tell the reader what it holds.

**State:** **open** — one meaning for the name Inbox in #1950, the work-day preset's coexistence
with the disjoint tabs in #1948, a home for a task dated beyond next week in #1949, the Today
tab's header in #1954, and the tabs' scope subtitles in #2022.

## 12. Smaller presentation polish

**What the audit found:** a backlog of small presentation defects, collected in an earlier pass
the same day.

**State:** **open** — #1761 carries the backlog under that subject. (The audit text names the
backlog #1716; the issue in the ready-for-agent queue with that subject is #1761.)

---

## The audit's overall verdict, and where its three structural complaints live

The audit's closing judgement was that the app is tidy but not *light*. Three things
underlie it, and all three are in the tracker:

- **Every screen carries its own bar inside the shared frame.** On Android the shell owns the
  only bar, and a screen with a header of its own has to publish that header into it. Who owns
  a title, and when the shell clears it, is unsettled: #1937, #1938, #2011 and #2012.
- **Five bottom-bar items lead to thirteen more.** The bar and the menu are not the same set,
  and the documentation has not always said which is which: #1535 (the fifth slot) and #2150 (the
  real layout, in the docs and in the code's own comments).
- **Three of the five show overlapping data.** Inbox, Today and Upcoming overlap, and the
  labels do not promise what the tabs hold: #1950, #1948, #1949, #1954 and #2022.

## Not checked

The audit did not cover these, and this document does not cover them either:

- **The desktop client, visually.** The window did not reach the screen capture under the
  Wayland session, so the desktop findings come from reading the sources, not from looking at
  the window.
- **The fully dark app theme.** Only the light theme was walked.
- **The new-task editor.** The editor's populated states were not seen.
- **The populated states of the lists.** Every list was seen empty.

## What this document deliberately does not do

- It does not fix anything. The findings belong to the issues that carry them, and the one that
  was unowned is filed rather than fixed here.
- It does not repeat a finding that the tracker already carries. Where a finding was fixed after
  the audit walked the build, the commit is named instead of an issue.
- It does not claim that the base compiles or that the app behaves as the audit found it. Every
  state above was read from the base commit `f07faf5f7`; nothing was built, installed or
  photographed.

## Related

- The repository audit of the same day, which covered the code rather than the screens:
  `docs/plans/2026-10-31-audit-and-development-plan.md`.
- The audit itself: issue #1747.