# theme-mode-offers-system-light-and-dark — tasks

**Issue:** #210 · **Spec:** `app-theming` (REQ-THEME-008, REQ-THEME-009, REQ-THEME-010, REQ-THEME-011)

Read first: ADR `2026-10-05-materialkolor-seed-palette-and-resolved-dark-flag` — it
records why the mode is resolved in one place and why it is not read from the system
directly.

---

## 1. Introduce the mode as a named value

- [ ] shared/ — Represent the theme mode as a named domain value with the three
      options, replacing the two-state boolean everywhere it is held, passed, and
      displayed.
      **Test:** the settings round-trip test carries all three values; a fourth
      unrecognised stored value falls back to "follow the system" rather than
      throwing.
- [ ] shared/ — Resolve the mode to a boolean once, where the theme is built, reading
      the system's appearance only for the "follow the system" option.
      **Test:** for each of the three modes, against a dark and a light system, the
      resolved boolean is the expected one.

## 2. Migrate the stored preference

- [ ] shared/ — Read the existing two-state preference and translate it: on becomes
      "always dark", off becomes "always light". Do not rewrite the stored value.
      **Test:** a store containing the old preference and no new one yields "always
      dark" / "always light" per the old value; a store with neither yields "follow
      the system".
- [ ] shared/ — The old preference remains readable after the mode is stored, so a
      downgrade finds the last state it understood.
      **Test:** assert the old key is still present after a mode write.

## 3. Offer the choice in settings

- [ ] shared/ — Replace the dark-mode switch with a three-way selection showing the
      mode in effect.
      **Test:** settings screen test asserts each of the three can be selected and
      that the stored value changes.

## 4. Point every consumer at the resolved theme

- [ ] shared/ — No screen consults the system's appearance directly; the calendar
      subtree included, which reads the theme's resolved dark flag.
      **Test:** extend the existing gate that restricts direct reads to the theme
      itself, so it now also rejects direct reads outside the single resolution point.
      Verified by negative control: reintroduce a direct read and confirm the gate
      fails.
- [ ] shared/ — Export and import carry the mode.
      **Test:** export → import round-trip preserves the mode; a backup carrying the
      older two-state value imports under REQ-THEME-010.

## 5. Review

- [ ] shared/ — Review the three modes against all nine accents.
      **Test:** manual review, recorded in the PR description. There is no
      screenshot baseline in this repository, so this cannot be a gate; do not record
      it as a passing test.