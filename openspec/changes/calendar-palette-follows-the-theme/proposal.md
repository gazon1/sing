# calendar-palette-follows-the-theme

**Status:** proposed · **Issue:** #197

## What

The calendar's colour palette SHALL be derived from the app's active theme, and the
slots that represent a selected or highlighted item SHALL use a theme role rather
than the raw accent the user picked.

Fourteen of the palette's sixteen values are already read from the theme in the light
case. The dark case is written out by hand against a theme the app no longer ships,
and the four accent-derived slots use the raw seed colour in both cases. This change
puts both halves on the same footing.

## Why

The seed colour is a vivid, user-chosen hue. It is not a role colour: nothing in the
colour specification promises that it is legible against a light surface. With the
yellow accent (`0xFFFFEB3B`) a selected task, a today badge and the current-time
indicator are all near-invisible on the light background. The generated scheme
already solves this — its primary role is tone-adjusted and contrast-checked — and
the app now has one.

The dark half is hand-authored against tonal values the app replaced. It has
effectively never been reviewed against the dark scheme actually in use: until the
theme-root change, that branch was only ever selected when the *system* was dark,
while the app's own dark mode is a user setting defaulting to off (ADR
`2026-10-05-materialkolor-seed-palette-and-resolved-dark-flag`). The two conditions
had to coincide, so in practice the dark palette shipped unreviewed.

## Scope

**In scope:** deriving the dark palette from the theme the way the light one already
is; replacing the four raw-seed slots with theme roles; and the review that proves
both across every accent.

**Out of scope, deliberately:**

- **The Tasks feature, which has the same defect at larger scale.** #198. It is the
  same principle over thirteen more token values and eleven more files; keeping them
  separate is what lets each be reviewed.
- **Deleting the palette abstraction outright.** If both halves end up derived from
  the theme, a sixteen-field structure may be redundant with the theme itself. That is
  a simplification to be judged *after* the derivation lands and can be seen to
  preserve the design, not a step to take alongside it.
- **A contrast gate.** There is no automated way to check a palette in this
  repository — no screenshot baselines, no snapshot tests. The review is human, and
  pretending otherwise would produce a check that never runs.

## Notes

- The four priority hues on task chips are *not* in scope here and must not be swept
  up with the four accent-derived slots: priority is a semantic scale, not a theme role.
