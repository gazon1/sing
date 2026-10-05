# calendar-palette-follows-the-theme — tasks

**Issue:** #197 · **Spec:** `app-theming` (REQ-THEME-001, REQ-THEME-002, REQ-THEME-003)

Read first: ADR `2026-10-05-materialkolor-seed-palette-and-resolved-dark-flag`, and
`.agents/skills/singularity-todo-agenda-section-design` is not relevant here — the
relevant one is the shared-UI-components skill for how a screen-scoped palette is
provided.

---

## 1. Derive the dark calendar palette from the theme

- [ ] Re-derive the dark branch of the calendar palette from the active theme's
      colour roles, mirroring what the light branch already does.
      **Test:** the existing calendar screen tests still pass with no change to their
      expectations, which is the evidence that no *non-colour* behaviour moved.
- [ ] Delete the fixed hex values from the dark branch, so a reader cannot mistake
      them for the source of truth.
      **Test:** grep for the retired literals returns nothing outside the ADR and the
      backlog entry that describe them.

## 2. Replace the raw-seed slots with theme roles

- [ ] Switch the four accent-derived slots (selected task, today badge, current-time
      indicator, accent) to a role the theme provides, in **both** branches.
      **Test:** manual review across all nine accents in light and dark — see task 4.
      No automated test can assert this; there is no screenshot baseline in this
      repository. Record the review in the PR description rather than claiming a
      passing test.
- [ ] Leave the priority hues alone. REQ-THEME-003 is the guard against widening the
      sweep; check that the four priority colours are byte-identical after the change.
      **Test:** `git diff` shows no change to any priority hue.

## 3. Decide whether the palette abstraction survives

- [ ] With both branches derived from the theme, judge whether the sixteen-field
      structure still earns its keep or whether the screen should read theme roles
      directly.
      **Test:** if it is collapsed, the calendar screen compiles and its tests pass
      with the same assertions; if it is kept, the reason is written in the PR.
      **Deliberately last:** collapsing it before the derivation lands would hide
      which of the two caused any visual change.

## 4. Human review — required, not optional

- [ ] Inspect the calendar for all nine accents in light mode. **Yellow and orange
      first:** they are the accents that fail contrast unadjusted, and therefore the
      only ones that prove REQ-THEME-002 is doing anything.
- [ ] Inspect the same nine in dark mode, including the surfaces, which have changed
      for the first time in the app's history.
- [ ] Attach before/after screenshots to the PR. The repository has no baselines, so
      this artifact is the only lasting record of the review.
- [ ] Do not close the issue until this is done. The change is a redesign with no
      automated gate; closing on a green build alone would be closing it unverified.

## Out of scope

- The Tasks feature's own hardcoded palette — #198, separate change, separate review.
- Any contrast-checking tooling. There is no snapshot infrastructure to hang it on.
