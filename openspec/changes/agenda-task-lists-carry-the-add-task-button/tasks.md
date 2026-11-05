# Tasks — agenda-task-lists-carry-the-add-task-button

## The resolver

- [ ] shared/ — Give the agenda routes that list tasks the add-task action: the
      drill-down into one project, into one tag, into a set of tags, and a saved
      view's results. The action opens the task editor with no due date, exactly as
      on the Inbox tab.
- [ ] shared/ — Leave the saved views' list, editor and creator without a button,
      and keep the classification exhaustive over the agenda start routes, so a
      route added later does not compile until it is classified.
- [ ] shared/ — Verify with `FabActionResolverTest.everyAgendaStartRoute_resolvesToTheClassifiedAction`.

## The suite

- [ ] shared/ — Walk every agenda start route in the test and assert what each one
      is expected to carry, naming the route in the failure message.
- [ ] shared/ — Read the expectation from an exhaustive walk over the same route
      hierarchy the resolver uses, so a route added later is classified in both
      places or the build fails.
- [ ] shared/ — Verify with `FabActionResolverTest.everyAgendaStartRoute_resolvesToTheClassifiedAction`.

## The record

- [ ] docs/decisions/ — Record the decision, including the disagreement with the
      sibling briefs over the saved view's results screen.
- [ ] openspec/ — Record the requirement in the tasks spec.
