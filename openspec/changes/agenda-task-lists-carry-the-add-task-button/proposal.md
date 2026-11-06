# agenda-task-lists-carry-the-add-task-button

Issues: #2057 · Backlog entries: none

## What

Every agenda screen that shows a list of tasks offers the shell's floating add-task
button: the drill-downs into one project, into one tag, into a set of tags, and a
saved view's results join the tabs and the health views, which already had it. The
saved views' own screens — the list of views, the editor of a view and the screen
that creates one — stay without a button, because they do not list tasks.

The task the button creates carries no due date, exactly as on the Inbox tab. It is
not bound to the project or the tags being viewed.

The suite states, route by route, which agenda screens carry the button and which do
not, so an agenda screen added later cannot ship without a decision about it.

## Why

A user standing on a list of tasks had no way to create a task there. The button's
promise is that a list of tasks can be worked on from where it is read, and a
project, a tag and a saved view's results are lists of tasks like any other.

The classification used to live in one branch of the resolver that answered "no
button" for every route nobody had classified. The Upcoming tab fell into it, and
the missing button was found by hand on a device — nothing in the suite noticed.
Stating the expectation per route turns "nobody classified it" into "somebody
classified it", which is the difference the Upcoming tab needed.

## How

- The resolver classifies each agenda start route: the ones that list tasks carry
  the action, the saved-view management screens carry none. The classification is
  exhaustive over the sealed hierarchy of agenda routes, so a new route has to be
  classified before it can be built.
- The test walks every agenda start route and asserts what the route is expected to
  carry, naming the route in the failure. Its expectation is read from an exhaustive
  walk over the same hierarchy, so a route added later does not compile until it has
  been classified in both places.

## Out of scope

- Binding the created task to the project or the tags being viewed. The button says
  "add a task", not "add a task here".
- The saved views' list, editor and creator, which keep no button.
- The plans tab, which already creates a task due today.
- A Maestro flow that checks the button on the bottom-bar tabs.
