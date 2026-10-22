---
date: 2026-10-28
status: accepted
deciders: [maintainer]
---

# A flow must not name a pixel, and must assert what its own data put on the screen

## Context

Three flows in the Maestro suite assert things that are not on the screen, and
one family of them fails only on a phone.

- `tasks/14-prefill-from-today`, `tasks/15-prefill-from-inbox` and
  `tasks/checklist/TASK-CHECK-01` wait for a **section header** (`agenda_section_today`,
  `agenda_section_no_date`) on an agenda the runner has just wiped. The header renders
  only when the section has a task (`AgendaEvaluator.kt`: empty sections are dropped),
  so the node cannot exist: the wait is for nothing, and the flow dies there
  (issue #868). The issue read "present but not visible"; it is absent.
- `tasks/13-create-full-task` asserts seven attribute rows of the editor without
  scrolling: on a viewport shorter than the editor, the rows below the fold are not
  on the screen, so the assertion is about the viewport and not about the editor
  (issue #921).
- `settings/03-cycle-tabs`, `smoke/06-session-anonymous` and
  `helpers/settings-account-tab` scroll the settings rail by a **point in pixels**
  (`start: 105, 2000`), which is a claim about the display it was measured on: the CI
  emulator is 1080×2400, and on a 720×1600 phone the start point is off-screen, the
  swipe never happens, and the trailing tabs are never reached (issue #916).

The same family produced a *green* run that said nothing: a flow written for one
screen size is green on that screen and meaningless on every other one, and the
report looks like a statement about the app.

## Idea

Two rules, and a gate for each.

1. A flow may not address a point in pixels. A swipe is a share of the screen
   (`start: "50%, 80%"`) or the element that scrolls — both mean the same gesture on
   every display. Where a flow genuinely cannot say it any other way, it says so in a
   registry, with the reason written down.
2. A flow may only assert what its own data put on the screen. Either it puts a task
   in the section first, or it asserts the affordance it needs next.

## Decision

- `scripts/check-maestro-screen-geometry.py` fails on a pixel in any flow under
  `Maestro/` (helpers included) that the registry
  `config/docs/maestro-pixel-exceptions.txt` does not name. It also fails on a
  registry entry with no reason, and on an entry for a flow that no longer names a
  pixel — a stale exception hides the day the flow is fixed.
- `scripts/check-maestro-keyboard-commands.py` fails on a `hideKeyboard` that has
  nothing to hide, and on a second `hideKeyboard`: a `hideKeyboard` with nothing to
  hide is a BACK keypress, and the editors are dialogs.
- The flows are changed where the change is provable: the section headers are gone
  from the three flows that asserted them on an empty agenda, and the editor's rows
  are scrolled into view before they are asserted.

## Rationale

A rule that only lives in a reviewer's head is a rule that a diff can undo. A gate
turns it into something the repository checks, and the exceptions registry keeps the
gate honest in both directions: a pixel must be explained, and an explanation that no
longer applies must be withdrawn.

The exceptions registry is not a loophole. It is the place where a display-specific
gesture is written down with the reason it is still needed, so the next person sees
the claim instead of inheriting it silently. Today it holds two entries: the
settings rail (Maestro's swipe does not scroll it — measured on an API 36 emulator
at 1080×2400, the tabs did not move, while the same gesture sent through
`adb shell input swipe` moved the rail by 730px) and the date picker sheet (its
action row is only reachable by a point inside the sheet).

## Consequences

- The gates are controlled: each has a sabotage that must make it fail
  (`scripts/tests/test_check_maestro_keyboard_commands.py`,
  `scripts/tests/test_check_maestro_screen_geometry.py`).
- A flow that needs a pixel is a visible claim, not an invisible habit.
- The flows that assert an empty agenda's section header no longer wait for a node
  that cannot exist; the flows that assert a row below the fold now scroll to it.
- The settings rail stays display-specific, and says so. The phone run of those
  flows is still red, and the phone floor is issue #941.

## Links

- Issues #868, #916, #921, #941.
- `docs/decisions/2026-10-28-the-keyboard-was-already-down.md` — the keyboard is
  already down, and BACK is a keypress.
- `Maestro/README.md` — the rules a flow author reads.
