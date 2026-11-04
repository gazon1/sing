# Tasks — desktop-nav-goBack-blank-screen

**Status:** proposed · **Blocks:** 3 desktop flow suites · **Priority:** highest
in this batch

## Do not attempt

- [x] ~~Debug modifier on the navigation display~~ — **already rejected.** When the
      tree is empty there is nothing to draw into. Recorded in #45; do not
      re-derive it.

## Diagnosis first

- [x] **Blank-tree diagnostic** — when the shell has no entry to render it records the
      active route, the previous tab and every back-stack route key (an empty stack drawn
      as `[]`). It lives in `Nav3State.toDecoratedEntries`, immediately before the
      unresolvable-entry fallback reseeds the stacks — the one place that still sees the
      empty stacks while the tree has nothing to draw into (REQ-DN-004).
- [ ] **Capture on a failing run** before changing any behaviour.

## Hypothesis to test with the capture

- [ ] **Stale persisted back-stack entry** — the shell persists its back stack, so
      a route restored from saved state whose entry provider no longer resolves
      would produce exactly this: navigation "completes", the screen is gone, and
      every assertion afterwards fails at the test rather than the shell. Testable
      by running the flow once in a fresh session and once against restored state.

## Fix and verify

- [ ] Fix the cause the capture identifies — not the symptom.
- [ ] `SavedAgendaCreateFlowTest` (C-01…C-03) green
- [ ] `SavedAgendaEditFlowTest` (D-02, E-09) green
- [ ] `CreateTaskFlowTest.a_saved_task_without_a_due_date_appears_under_inbox_no_date`
      green
- [ ] Unresolvable-entry fallback present, so the whole class of causes is covered

## Explicitly not doing

- Android navigation (Maestro's territory)
- Redefining the saved-state format — if a stale entry is the cause, the fix is to
  tolerate or migrate it
