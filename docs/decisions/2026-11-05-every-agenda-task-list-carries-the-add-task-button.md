---
title: "Every agenda task list carries the add-task action"
date: 2026-11-05
tags: [agenda, navigation, fab]
status: accepted
---

# Every agenda task list carries the add-task action

## Context

The shell's floating action button is resolved from the current destination. The agenda
graph could start on a dozen routes, and only some of them carried a button: the Inbox,
Today and Upcoming tabs and the Blocked, Stagnant and Overdue views did, while the
drill-downs into one project, one tag or a set of tags, and a saved view's results did
not. A user standing on a list of tasks had no way to create a task there.

The classification lived in one `when` over the sealed hierarchy of agenda start routes.
The Upcoming tab had once fallen into the branch that answered "no button", and nothing
in the test suite noticed — the missing button was found by hand on a device.

## Idea

- Give the button to the four routes that were missing it, and leave the saved views'
  own screens without one.
- Bind the new task to the project or the tags being viewed, so the button means "add a
  task here".
- Keep the resolver as it is and rely on the compiler's exhaustiveness check alone,
  adding a test only for the routes that were wrong.

## Decision

Every agenda route that renders a list of tasks carries the shell's "Add task" action:
the drill-downs into one project, one tag or a set of tags, and a saved view's results
join the tabs and the health views. The screens that manage saved views — listing
views, editing one and creating one — are not task lists and carry no action.

The new task is not bound to the project or the tags being viewed, and it carries no
due date, exactly as on the Inbox tab. The button says "add a task", not "add a task
here", and a task dated today belongs to the Today tab.

The test suite states, route by route, which agenda routes carry the action and which
do not, and the expectation is read from an exhaustive `when` over the same sealed
hierarchy the resolver uses. A route added later does not compile until it has been
classified in both places.

The saved view's results screen carries the action. Two sibling briefs (#1952, #1958)
asked for the opposite, on the grounds that a saved view selects tasks by a query of
its own and a button there would create a task that satisfies no part of it. This brief
(#2057) counts the results screen as the task list it is — a saved view is a filter
applied to the same agenda, and the person standing on it sees tasks — so it carries
the action. The disagreement is recorded here rather than left in the code: whoever
reads the two briefs in order should find the decision and its reason in one place.

## Rationale

The button's promise is that a list of tasks can be worked on from where it is read.
A saved view's results, a project and a tag are all lists of tasks; the saved views'
management screens are not. Binding the task to the viewed project or tags is a
different feature — the button would have to carry a seed through the editor, the
draft and the content key — and it is a promise the button does not make today, so
the simpler thing is the honest one.

Stating the classification per route in the test, rather than only walking the
bottom bar's tabs, is what turns "classified as no button" into "deliberately no
button". The compiler's exhaustiveness check alone would have caught the missing
button on the Upcoming tab as a build failure at the wrong moment; it says that a
route was classified, not that the classification was right.

## Consequences

- A new agenda start route must be classified in the resolver's `when` **and** in the
  test's per-route table, or the build fails. There is no route that ships unclassified.
- The saved view's results screen offers the action, unlike the saved views' list,
  editor and creator. If the sibling briefs' reading is ever adopted, this entry is
  the thing to supersede, and the resolver and the test table move together.
- A task created from a project or a tag agenda is not bound to it. That is a
  deliberate limit, not a missing link.

## Links

- Issues: #2057, and the sibling briefs #1952 and #1958
- Files: `shared/src/commonMain/kotlin/com/singularity/todo/shell/FabActionResolver.kt`,
  `shared/src/commonTest/kotlin/com/singularity/todo/shell/FabActionResolverTest.kt`
- Tests: `FabActionResolverTest.everyAgendaStartRoute_resolvesToTheClassifiedAction`
- Related: `2026-09-16-android-shell-fab-fix.md`, and the unmerged sibling decision
  on the branch that took the other reading of the saved view's results screen
  (its ADR is not merged here)
