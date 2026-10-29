# The refresh is one run

Issue: — · Related: `docs/decisions/2026-10-25-the-refresh-is-one-run.md`

## What

One gesture on every screen that shows the user's data: pull the content down,
and the application brings the screen in line with what has already happened.
Completed work moves to the archive, the device synchronises with the server,
and the screen says what happened.

The two halves are one operation and not two actions the user has to
remember. Moving completed work into the archive writes those tasks to the
outgoing queue, so a refresh that pushed before it archived would leave the
server holding the un-archived copy — and the next download would put every
task the user had just cleared away back into the list. The archive step runs
first for that reason, and the requirement is written down so that a later
reordering cannot quietly bring the resurrection back.

The refresh is also the first way a user can ask for a synchronisation at all.
Today the device synchronises on a schedule and when the network returns, and
the only other place the user can go is the archive screen, for archiving
alone.

## The change in behaviour

- Every screen that shows the user's stored data offers the gesture: the
  bottom-bar tabs, the menu destinations, and the detail screens reached from
  them. The screen shows an indication that the refresh is running, and it
  stays usable while it runs.
- The refresh moves every completed task of the active profile into the
  archive — not only the ones on screen — and then synchronises. The screen
  reports how many tasks moved and whether the synchronisation succeeded.
- A refresh that cannot reach the server still moves the completed work, and
  says that it did and that the synchronisation failed. A refresh on a device
  with no account still moves the completed work, and says that there was
  nothing to synchronise with.
- The report belongs to the screen that asked for it. Other screens do not
  repeat it.
- A second gesture while a refresh is running joins the one that is running
  instead of starting another, and nothing is sent to the server twice.
- The refresh finishes even if the user leaves the screen, and the report is
  waiting when they come back.

## What is not in this change

- The scheduled synchronisation and its interval are untouched. The refresh is
  added to the ways a synchronisation can start; it does not replace any of
  them. The rules that govern how concurrent requests are collapsed are
  already written down for synchronisation requests, and a refresh is one
  more request — the refresh change adds no second set of those rules.
- The rules for resolving a conflict are untouched. A refresh applies what the
  server sends by the same rules the scheduled synchronisation applies it by.
- Notes, projects and tags are not moved to the archive. Only tasks are, and
  only tasks have an archive.
- The explicit "archive all completed" action on the archive screen stays, and
  keeps doing what it does. The refresh is added beside it.
- There is no switch that turns the gesture off, and no button in the top bar
  that does what the gesture does.
- Nothing about how the archive stores, restores or permanently deletes a
  task changes.

## Why now

Because the user has two ways to say "this is out of date" and neither of them
works. The list is stale and the only remedy is to wait for the timer or to
leave the application. The completed work is in the way and the only remedy is
to know that the archive screen exists and to walk to it.

Both are the same sentence — *bring this screen in line with what has already
happened* — and the application has no way to hear it. The synchronisation
core already named the gesture as one of its triggers, which means the seam
was planned and the button was never built.

The change is also the first one that touches the shared interface layer with
something that is not a view model of a feature: a gesture that every screen
uses, and a run that has to be owned by one place rather than by the screen
that happened to start it.
