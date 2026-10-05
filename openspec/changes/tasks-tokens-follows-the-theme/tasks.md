# tasks-tokens-follows-the-theme — tasks

**Issue:** #198 · **Spec:** `app-theming` (REQ-THEME-004, REQ-THEME-005, REQ-THEME-006)

Read first: ADR `2026-10-05-materialkolor-seed-palette-and-resolved-dark-flag`; skills
`singularity-todo-shared-ui-components` (how a screen-scoped palette is provided) and
`singularity-todo-document-style-detail` (the editor surfaces this change rewrites).

---

## 1. Convert the task token collections

- [ ] Turn the task-creation token collection into values derived from the active
      theme, returned as a token set and resolved once at the screen root.
      **Test:** the task detail and editor tests pass with unchanged expectations.
- [ ] Do the same for the task-list token collection.
      **Test:** the task list tests pass with unchanged expectations.
- [ ] Update all eleven consuming files to read the resolved set instead of the fixed
      collection. `git grep` for the old collection names returns nothing.
      **Test:** the module compiles and the full tagged suite is green.
- [ ] Fix the KDoc on the task-list collection, which currently promises a theme-change
      guarantee the code does not provide.
      **Test:** no test — but the comment and the code must agree, and a reviewer
      should catch the difference.

## 2. Route accent-derived colours through the theme

- [ ] Move the active filter, selected state, links and focus treatment onto the
      theme's accent role, in both the list and the detail surface.
      **Test:** manual review, see task 4. No automated assertion is possible.
- [ ] Confirm the hardcoded blue that stood in for the accent is gone.
      **Test:** `git grep` for that literal returns nothing outside this change's
      documentation.

## 3. Hold the semantic line

- [ ] Leave the four priority hues, the overdue colour and the completed-state colour
      as fixed values. REQ-THEME-006 is the guard.
      **Test:** `git diff` shows these values byte-identical. This is the check most
      likely to be skipped and most expensive to get wrong later.

## 4. Human review — blocking

- [ ] Inspect the task list and task detail in **light** mode first. This is the
      largest visible delta in the app: these surfaces have always been dark, so
      light mode is not a tweak here, it is the first time anyone has seen them.
- [ ] Then inspect all nine accents in both modes. **Yellow and orange first.**
- [ ] Check the editor's interactive states specifically: active chips, the date and
      estimate rows, focus rings. Those are the accent-derived elements most likely
      to be missed in a surface-wide glance.
- [ ] Attach before/after screenshots to the PR. With no baselines in the repository,
      this is the only durable record that the review happened.
- [ ] Do not close the issue on a green build. Nothing in CI can see a contrast
      regression on these surfaces.

## 5. After this lands, not before

- [ ] Add a rule banning fixed colour literals in feature presentation code, with the
      theme token files as the only allowed location. It will fail on any residue
      this change missed, which is the point.
      **Test:** the rule runs in the tagged suite; deliberately introduced literals
      fail it (positive control, per
      `openspec/changes/detekt-rule-has-positive-control`).
- [ ] Fix the false claim in the calendar entry of the deferred backlog if #197 has
      not already been done — the two are the same class and should be tracked as
      such.

## Out of scope

- The calendar palette (#197).
- Priority, overdue and completed-state colours.
- Any snapshot or contrast tooling — there is no infrastructure to host it.
