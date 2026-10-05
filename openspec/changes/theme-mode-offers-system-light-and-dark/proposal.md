# theme-mode-offers-system-light-and-dark

**Status:** proposed · **Issue:** #210

## What

The user SHALL be able to choose between three theme modes — follow the system, force
light, force dark — instead of the current two-state switch, and the chosen mode SHALL
be the only input to the palette the app paints.

## Why

The switch is a boolean, so "follow the system" is not expressible. That absence is
not cosmetic: it is the root cause of the calendar defect this branch has been fixing.
The calendar palette branched on the *system* setting while the app's own dark mode was
a stored boolean defaulting to off, so the two had to coincide before the dark palette
was ever reachable. A tri-state mode removes the possibility of the disagreement rather
than documenting it — there is one value, and everything derives from it.

It is also what users expect. A user who wants a light app on a dark phone — for
bright sunlight, for a projector, for reading at night — currently has no way to ask
for that, and the two-state switch forces them to turn dark mode *off* and accept
whatever the system says.

## Migration

The stored setting is a boolean under the `appearance/dark_theme` key. It becomes a
named mode under `appearance/theme_mode`. Reading the old key is the migration: a
stored `true` becomes "dark" and a stored `false` becomes "light" — never "system".
Defaulting a pre-existing user to "system" would silently change their app the first
time it updated, and a user who turned dark mode on deliberately would find the app
light again at sunset. The old value is read once and left in place, so a downgrade
still finds the last state it understood.

## Out of scope

- **Per-surface mode.** One mode for the whole app. A screen that re-derived its own
  answer is the defect, not the feature.
- **Automatic switching by time of day.** A fourth mode, and one that needs a clock —
  the branch that just fixed a floor that moved under an unrelated merge is the wrong
  place to introduce one.
- **High contrast.** Unrelated to the mode axis.
- **Applying a mode before settings load.** The palette resolves from the stored value;
  the first frame renders the default. Rendering a *different* palette once the stored
  value arrives would be a visible flash, which is a separate decision.