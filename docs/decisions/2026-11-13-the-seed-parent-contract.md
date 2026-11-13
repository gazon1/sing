---
status: accepted
title: "A named parent that cannot be honoured fails the seed"
date: 2026-11-13
tags: ["seed", "testing", "projects", "fixtures"]
---

## Context

The Android menu lists the root projects only (`2026-11-12-android-menu-roots-only.md`), so a
nested fixture has to hang a project below another one, and the deep link that seeds the child
names the parent by id — a link cannot know a generated id.

`SeedWriter.writeProject` resolved that parent with
`spec.parentId?.takeIf { namedParent != null } ?: keeper?.parentId`: a parent that was not there
was dropped, the project was written as a root, and the result carried a `missingParent` field
that said so. The activity logged a warning and carried on.

That is three problems in one line:

- The caller could go on believing the fixture was there. The project it asked for was not
  written; a root was. On Android a root is exactly the row the menu lists, so the flow went on
  to assert on a path it never meant to test, and the real reason surfaced much later — as a
  failed assertion about a row that was never there.
- `?:` made a *mistake* look like *policy*. An existing project that named a parent which was not
  there kept its old parent, while the log said the project would be created as a root: the
  message and the write disagreed, and neither of them was the contract.
- Nothing stopped a project from being its own parent, or from being hung below one of its own
  descendants. Both are cycles, and a cycle in the project hierarchy is not a hierarchy.

## Idea

1. **Keep the soft failure.** `writeProject` reports `missingParent`; the caller decides. Cheap,
   and the caller is exactly the party that must not go on believing the fixture is there.
2. **Write the project as a root and say so.** The fixture exists, the shape of it is not the
   one that was asked for. A flow would fail later, on the assertion that wanted the nesting.
3. **Fail the seed.** A fixture that cannot be written is not a fixture. The seed stops, nothing
   is written, and the reason is on the screen the flow is looking at.

## Decision

**A project that names a parent which cannot be honoured fails the seed. Nothing is written.**

`SeedWriter.writeProject` returns `SeedResult<ProjectId>` and throws `IllegalArgumentException`
when the named parent is not there, is the project itself, or is one of its own descendants. The
`ProjectSeedResult` type and its `missingParent` field are gone: a result that can say "the
fixture is not what you asked for" is a result whose caller has to remember to check, and the
caller here is a deep link that has no way to answer.

`DebugSeedActivity` catches the failure, shows the reason on its own screen, and does **not**
bring `MainActivity` up. The flow waits for the app after every seed, so a seed that failed has
to fail that wait — at the seed — rather than later, on an assertion about a row that was never
written.

The reason is machine-readable, because a reason nobody can read is not a reason:

- the reason is one line in the logcat (`Seed failed: …`), and the runner prints it next to the
  flow failure — the artifact file alone was where it used to end up, and nobody opens an
  artifact file to find out why a flow failed;
- `Maestro/helpers/seed.yaml` reads the reason off the screen when the screen is there, so the
  flow's own failure message names it.

The reason is addressed by its **text**, not by an id. The failure screen is a plain view in the
debug activity, not a composable, so it has no tag in the app's tag registry — and that registry
is where a Maestro flow's ids come from: `MaestroFlowTagsTest` holds every literal id in a
helper to an entry in it. Registering a view that is not a composable would have meant either
wiring debt in `TestTagsWiringTest` or a Compose migration of a debug screen, both larger than
the problem. The text carries a fixed prefix (`SeedFailure.PREFIX`) that every report of a failed
seed shares — the screen, the logcat line, the runner's summary — so one string is what a flow
asserts on, what the runner greps for, and what a human reading the device sees.

The guard compares the named parent against **both** ids the project answers to: the id the seed
gives it, and the id the row already carries. A project that is reseeded under a new id is still
its children' parent under the old one, and a cycle through the old id is still a cycle.

## Rationale

- **A fixture is a promise.** The flow says "there is a project named Child below Parent" and the
  seed either writes that or says it cannot. Writing something else — even something the log
  mentions — turns a typo into a test of the wrong thing.
- **The failure belongs at the seed.** The seed is the first step that knows the fixture could not
  be written. Every step after it would be guessing which of the many things that are missing is
  the one that is missing.
- **A cycle is not a hierarchy.** A parent that is the project itself, or that is below it, would
  make a hierarchy no reader can draw and no menu can walk. The Android menu lists roots, so a
  cycle would also put a project in no list at all.
- **Deletion beats a soft field.** `missingParent` was a field every caller had to remember to
  check. There is exactly one caller, and it cannot answer. The failure is the return value's
  absence, not a value inside it.
- **A root is not a fallback.** On Android the root is the row the menu lists. A project that
  silently became a root would be reached by the very path the fixture was written to avoid.

## Consequences

- `writeProject` throws instead of returning a soft failure; `SeedWriterTest` holds the contract
  in nine cases — an existing parent, a missing parent, a parent that is the project itself, a
  parent that is a descendant, the same two through the row's old id, a re-parent, a kept parent,
  and a cycle.
- A seed that fails leaves the app down: the flow fails at the seed with the reason, and the
  session, the profile and the rest of the data are untouched.
- A parent that is not there is a *failure*, not a warning. A flow that seeds a child has to seed
  the parent first, with the id it gave it.
- The reason reaches the CI log twice over: the runner prints it from the logcat, and the flow
  reads it off the screen when the screen is up. Either one is enough; neither depends on the
  other being there.
- `Maestro/helpers/seed.yaml` documents what `parent=` does when the parent cannot be honoured.

## Links

- Issues: #2864 (implements this decision), #2893 (the review this answers)
- Decisions: `2026-11-12-android-menu-roots-only.md` (why a nested fixture exists at all),
  `2026-10-05-debug-source-set-is-linted.md` (the debug activity's own rules)
- Code the decision names: `core/seed/SeedWriter.kt`, `core/seed/SeedLinkKeys.kt`,
  `core/seed/SeedFailure.kt`, `androidApp/src/debug/.../DebugSeedActivity.kt`,
  `Maestro/helpers/seed.yaml`, `scripts/src/emulator/maestro/_artifacts.py`
- Tests: `shared/src/commonTest/.../core/seed/SeedWriterTest.kt`,
  `scripts/tests/test_device_contracts.py`
