# Tasks — delete-safety-feedback

## Phase 0 — decisions before code

- [ ] **Adopt the classification table in design.md**, or replace it. Every site
      below depends on the answer. The tag-group case is the one that does not
      fit two classes; design.md recommends the recovery offer cover the group
      assignment and the confirmation name it.
- [ ] **Put the table in a skill, not only in the spec.** A rule in a spec is read
      when someone implements the spec; the next delete path is written before
      anyone opens this change.

## Phase 1 — reliability before coverage

- [ ] **shared/**: clear the reversal offer only on success, so a failed reversal
      leaves something to act on. Precondition for everything else in this
      change.
      Test: a state-holder test driving a failing reversal and asserting the
      offer is still addressable afterwards.
- [ ] **shared/** + **androidApp/** + **desktopApp/**: report reversal failure.
      Test: view-model test asserting a failure event is raised, and a
      view-model test asserting **no** success indication is shown. The second
      assertion is the one that would have caught the original defect.
- [ ] **shared/**: unblock the task detail screen's own layout so it can host a
      reversal offer. Currently the affordance cannot be placed there at all.
      Test: a screen test that finds the affordance on that screen.

## Phase 2 — the offer

- [ ] **shared/**: the offer shows remaining time, and the animation and the
      dismissal share one clock.
      Test: a test that advances virtual time and asserts the offer is gone at
      the stated interval — not sooner, and not later. A desync shows up as
      exactly one of those two failures.
- [ ] **androidApp/** + **desktopApp/**: the offer's action is addressable by a
      stable identifier rather than by its translated label.
      Test: a Maestro flow that takes the offer, run on a non-English device.
      *This is the generalisable rule; see issue #90.*

## Phase 3 — coverage

- [ ] **shared/** + screens: apply the classification to the remaining sites —
      notes, tags, saved views, saved searches, attachments, calendar entries.
      Test: one view-model test per site asserting a reversal offer is raised.
- [ ] **shared/** + screens: apply confirmation to the cascading sites, naming
      what is removed with it.
      Test: a view-model test asserting the confirmation names the affected
      count. A confirmation that omits the count fails this test.

## Phase 4 — verification

- [ ] **Maestro/**: one flow per class — one that takes a reversal, one that
      declines a confirmation. Both select by identifier.
- [ ] **Maestro/**: a flow that forces a reversal failure and asserts the
      failure is shown. This is the case no existing check covers and the one
      that motivated the change.

---

**Verification command:**

```bash
./gradlew :shared:jvmTest --tests "*Undo*" --tests "*Delete*"
```

**Order note:** Phase 1 precedes Phase 3 deliberately. Applying reversal offers
to ten sites while the reversal path is still known to fail silently would
multiply the defect by ten.
