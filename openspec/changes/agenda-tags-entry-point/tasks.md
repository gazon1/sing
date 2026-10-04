# Tasks — agenda-tags-entry-point

## Phase 0 — decisions before code

- [ ] **Confirm the host surface.** design.md recommends search results as the
      general case, with a single-tag shortcut from the tags surface into the
      same destination. Overrule if the tags surface is the only one with the set
      in hand.
- [ ] **Confirm transient-with-an-offer-to-save**, or replace it. Phase 2 depends
      on this: the empty state and the naming affordance differ.

## Phase 1 — the shared destination

- [ ] **shared/**: one agenda screen reachable from both the new entry point and
      the existing single-tag route, with no duplicated implementation.
      Test: a view-model test covering empty, content, and no-match states.
- [ ] **shared/**: the no-match state names the tags being filtered by.
      Test: view-model test asserting the tag names appear in the empty state —
      an empty agenda that says nothing is the failure this requirement exists to
      prevent.

## Phase 2 — the entry point

- [ ] **shared/** + **androidApp/** + **desktopApp/**: expose the action on a
      filtered result set, carrying the set into the destination.
      Test: a desktop flow test that filters, invokes the action, and asserts the
      resulting agenda's contents match the filter exactly.
- [ ] **shared/**: a single-tag selection reaches the same destination.
      Test: the same flow test with a one-element selection, asserting the same
      screen and the same actions.

## Phase 3 — persistence offer

- [ ] **shared/**: the resulting agenda offers to be kept, and remains usable if
      the offer is declined.
      Test: view-model tests for both the accepted and declined paths.
- [ ] **Maestro/**: a flow that opens a tag-driven agenda, saves it, and finds it
      in the saved list on next launch.

---

**Verification command:**

```bash
./gradlew :shared:jvmTest --tests "*Agenda*"
```

**Note on the config rule** for this capability's spec: it is written in terms of
"a filtered set of items" and "the user" rather than naming tags, search, or
screen classes, because the host surface is exactly what Phase 0 decides. Naming
it now would bake an undecided decision into the requirement.
