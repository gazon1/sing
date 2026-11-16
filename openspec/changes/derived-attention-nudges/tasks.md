# Tasks — derived-attention-nudges

## Phase 1 — the projection

- [x] **shared/**: one pure projection from a task list and a project list to an
      ordered list of attention items, in the tasks domain next to the health rules.
      The four signals and their order: overdue, due soon, blocked, project deadline.
      Test: `AttentionNudgesTest` — one test per signal, one per exclusion, one for
      the order, one for the "due soon outranks blocked" rule.

## Phase 2 — the surface

- [x] **shared/**: the Statistics ViewModel derives the list and holds it in its state,
      with the dismissal of a single item held beside it and applied to what the
      screen shows.
      Test: `StatisticsViewModelTest` — the derived list reaches the state, a
      dismissal hides one item and nothing else, and a dismissal does not survive
      leaving the screen.

- [x] **shared/**: the Health tab shows the list above the bucket cards, one row per
      item with its reason, the count of the items it did not show, and a way to set
      one item aside. Selecting a row opens the task or the project it names.
      Test: `StatisticsHealthTabFlowTest` — the list is on the Health tab, and the
      rows carry stable ids.

## Phase 3 — the contract for the later OS delivery

- [x] **ADR `2026-11-16-derived-attention-nudges-in-app-first`**: the trigger, the
      deduplication, the expiry and the invalidation rules for a system notification,
      and why they are written down before any code exists.
      Test: none — a decision, not behaviour.

---

**Verification command:**

```bash
./gw :shared:jvmTest --tests "*AttentionNudgesTest*" --tests "*StatisticsViewModelTest*"
./gw :desktopApp:test --tests "*StatisticsHealthTabFlowTest*"
```

**Not done here, deliberately:** no notification, no scheduler, no stored attention
state, no stored dismissal, no new screen. The OS-delivery contract is in the ADR and
has no code, because #2980 asks for it to be evaluated separately.
