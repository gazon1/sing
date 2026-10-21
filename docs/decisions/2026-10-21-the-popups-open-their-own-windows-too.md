---
title: "The popups open their own windows too"
date: 2026-10-21
status: accepted
tags: [architecture, testing, compose]
---

# The popups open their own windows too

## Context

`Maestro/CONVENTIONS.md` tells a writer which tier can reach a surface: a table of the
kinds of surface and the answer for each. The table had three rows about popups, and
**two of them had never been measured**:

| Surface | Desktop `jvmTest` | Maestro (Android) |
|---|---|---|
| `AlertDialog` | **yes** | yes |
| `ModalBottomSheet` | **yes, through the scene's roots** | yes |
| `DropdownMenu` | no | yes |

The sheet's row had been measured twice — once badly, on an editor row that did nothing
at all, which is where the false "a JVM test cannot reach a sheet" claim came from — and
then measured properly, when the editor's sheet state was fixed. The other two rows were
taken on trust.

The `DropdownMenu` row turned out to be **wrong**. The measurement:

| Surface | Roots before → after | The main tree | The popup's own content |
|---|---|---|---|
| `DropdownMenu` | 2 → 3 | unchanged | in the third root, reachable |
| `AlertDialog` | 2 → 3 | unchanged | in the third root, reachable |

A dropdown menu opens its own window on desktop, exactly like a sheet and a dialog: the
scene grows a root, the app's own tree does not change, and the popup's content is in
the new root — where the cross-root search finds it.

So the table's `DropdownMenu` row sent the next person to write a Maestro flow for
something a JVM test can do. That is the cost of an unmeasured row: not a missing test
but a test in the wrong tier, on a device, with another set of selectors.

## Idea

Measure every row of the tier table on the surface the row is about, and pin the
measurement in a test so a Compose upgrade cannot change what a selector sees.

## Decision

**Every popup opens its own window on desktop, and every popup's content is reachable
from the JVM tier through the scene's roots.**

- The table's three popup rows now say the same thing: **yes, through the scene's
  roots**.
- `PopupReachabilityTest` measures the two rows that had never been measured —
  `DropdownMenu` and `AlertDialog` — with the same three claims the sheet's test pins:
  the main tree does not change, the popup's content is not in the main root, and the
  popup's content is in the scene.
- The section's title follows the finding: *Choosing a Tier: A Popup Opens Its Own
  Window*.
- The stale advice is gone. The old text told the reader **not** to add `testTag`s to
  sheet content "expecting a JVM test to use them" — which contradicted the row above
  it, and which the sheet's own measurement had already disproved.

## Rationale

- **A row is a measurement or it is a belief.** The table reads like a fact table, so
  every row in it has to be one. The two rows that were wrong cost nothing until
  somebody needed them, and then they cost a flow in the wrong tier.
- **Measure on the surface the row is about.** The sheet's row was measured on the task
  editor's recurrence row while that row opened a sheet state that nothing rendered. The
  measurement was of a bug wearing the costume of a platform fact. The two new
  measurements are on surfaces that were already working: the editor's overflow menu and
  the saved view's delete confirmation.
- **The three claims are the ones that matter to a writer.** Whether the main tree
  changes (a helper that counts in the main root), whether the content is in the main
  root (a selector that looks only there), and whether the content is in the scene at
  all (a selector that looks across the roots). A test that pins all three fails loudly
  when an upgrade changes the answer, instead of leaving the table quietly wrong.
- **The costs differ per popup, and the table does not pretend otherwise.** A sheet
  animates in, so the assertion has to wait for it; a menu and a dialog appear at once.
  That difference is a sentence in the prose, not a row in the table.

## Consequences

- The `DropdownMenu` row is no longer wrong, and a behaviour inside a dropdown menu can
  be verified from the JVM tier.
- `PopupReachabilityTest` is the second test of its kind. The sheet's claims live in
  `SheetReachabilityTest`, the other two popups' claims in `PopupReachabilityTest`, and
  the table in `Maestro/CONVENTIONS.md` reads both.
- `rootSizes()` joins the desktop test helpers: the number of nodes in each root of the
  scene, which is what tells a popup's own window apart from the app's.
- The tier table now has no unmeasured row. That is a statement about today, not a
  promise: a new kind of surface needs a measurement before it gets a row.

## Links

- `2026-10-21-the-editor-owns-its-sheets-and-its-model` — the measurement that corrected
  the sheet's row.
- `Maestro/CONVENTIONS.md` "Choosing a Tier: A Popup Opens Its Own Window" — the table.
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/PopupReachabilityTest.kt`
  — the measurement.
