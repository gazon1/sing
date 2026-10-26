# Quickstart: Proving the Plans Timeline and Projects Menu

**Feature**: `specs/016-plans-timeline-projects-menu`
**Date**: 2026-10-26

How to find out whether this feature works. The checks are listed in the order they should be run:
the narrow ones first, the whole gate suite last.

---

## Prerequisites

- The checkout is on a branch that carries this feature's changes.
- `./gw` is the repository's Gradle wrapper — it points at a private `GRADLE_USER_HOME` for the
  worktree. Do not call `./gradlew` directly.
- For the device checks: an emulator or a connected device, and the app installed.
- The change record exists: `openspec/changes/plans-timeline-and-projects-menu/` with its proposal,
  specs, design, adr and tasks.

---

## 1. The pure logic

The grouper and the period builder are pure functions — no device, no database, no fakes.

```bash
just tc        # ./gw :shared:commonTest
```

**Expected**: the timeline's tests pass — days in order, one task in one section, the horizons, the
labels, overdue first, completed tasks out, and the empty periods dropped.

## 2. The ViewModels

```bash
just tj        # ./gw :shared:jvmTest
```

**Expected**: the plans ViewModel tests pass — the granularity is read from the profile's settings,
the sections follow the granularity, and a change of granularity re-groups without the screen
losing its state.

## 3. The screens, on the host

```bash
just tah       # ./gw :shared:testAndroidHostTest — Robolectric, no device
```

**Expected**: the screen tests pass — the granularity control names the selection, a section
header names its period, tapping a row opens the task, and the empty state appears when the
horizon is empty.

## 4. The ADR and the change record

```bash
singularity-adr validate
python3 scripts/openspec-import-all.py      # writes .specify/issues/<slug>.yaml
./scripts/refresh-decisions-digest.sh
```

**Expected**: the ADR corpus validates, the registry gains the change, and the digest is
regenerated. A non-zero exit from the validator is a broken contract — fix it, do not proceed.

## 5. The scenarios and the coverage matrix

```bash
PYTHONPATH=scripts/src python3 -m traceability validate
just trace-scenarios        # traceability scenarios --check
just trace-coverage         # regenerates the committed matrix
just trace-validate
```

**Expected**: every declared scenario has its file, no file still carries a `TODO` body, and the
committed coverage matrix matches a fresh build. The ratchet must not see holes grow:

```bash
PYTHONPATH=scripts/src python3 -m sgate coverage_holes_did_not_grow
```

**Expected**: `OK`. The eighteen scenarios declared by this feature were holes when they were
declared; each carrier that lands closes one, and the count only falls.

## 6. The whole gate suite

```bash
just gate                   # check.sh + detekt + the Maestro flows
```

**Expected**: all three steps green. To run the flows without a device attached:

```bash
SKIP_MAESTRO=1 just gate
```

## 7. By hand, on a device

The screens the scenarios describe, and what to look at.

### The timeline

1. Open the app and go to the **Plans** tab.
   - The screen shows sections, not a project list.
   - The first section is **Overdue** when something is overdue; then **Today**.
   - Each header names its day, and the rows under it are the tasks due that day.
2. Change the granularity to **Week**, then to **Month**.
   - The headers become date ranges, then months.
   - The same task stays in the section that contains it.
3. Leave the tab and come back.
   - The granularity you chose is still in effect.
4. Tap a task.
   - The task's own screen opens.
5. Tap the `+` on a section header.
   - The editor opens with that section's date already filled in.
6. Mark a task done.
   - It leaves the section it was in.

### The menu

7. Open the **menu**.
   - There is a **Projects** section, and the projects are a tree: a project with children shows
     an expander, and expanding it shows the children indented underneath.
   - A project without children shows no expander.
   - **All projects** at the end of the section opens the full list, with its search.
8. Tap a project in the tree.
   - That project's screen opens, and the menu closes.
9. Look for **Calendar** in the menu.
   - It is there, and choosing it opens the calendar.
10. Look at the bottom navigation bar.
    - There is no Calendar button in it.

### The desktop build

11. Run the desktop app and open the navigation drawer.
    - It holds the same entries as the Android menu, including Calendar.
    - It holds the same project tree, in the same order, at the same depths.

---

## What "done" means

Every scenario in the matrix has a carrier that runs the production path — the wiring the app
actually uses, not a fake graph behind it. The feature is done when:

- the eighteen carriers run green,
- the hole count in the ratchet has fallen below the floor this change raised,
- the OpenSpec change verifies against the implemented behaviour,
- and the ADR describes what was built, rather than what was planned.
