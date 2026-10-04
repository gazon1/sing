---
title: "Make the rules, do not make the sweeps"
date: 2026-10-04
status: accepted
tags: [testing, maestro, ci, tooling, process]
---

# Make the rules, do not make the sweeps

## Context

The previous pass ran the `smoke` suite on a device for the first time and
found six real defects in it. **None of the six was found by a gate.** Each was
found by a human running `grep`, or by reading a captured UI hierarchy and
working backwards from the failure message.

| Defect | Found by |
|---|---|
| 33 window-owning surfaces, no `mapTestTagsAsResourceIds` | grep |
| `testTag` built from a localised label | grep |
| `- longPress:` (the command is `longPressOn`) | grep |
| `- clearState:` (an argument of `launchApp`) | grep |
| `task_item_today_task` vs `task_item_todaytask` | device hierarchy |
| `?task=X&profile=Y`, where `profile=Y` is ignored | device hierarchy |

Every one of those is statically checkable. The reason they survived is that the
checks which existed each answered a *different* question: does this compile, is
this ViewModel tested, is this tag declared. None asked whether the selector
would resolve.

## Idea

- **A.** Fix the six, and leave it. The next occurrence costs the same session.
- **B.** Write six bespoke checks.
- **C.** Write as few checks as possible, each shaped so that it cannot cry wolf.

## Decision

**C.** Three rules, in two files, and every one of them verified to have teeth.

`UiAutomationSelectorTest` (new):

1. **A tagged control inside a window-owning surface, where the window does not
   apply `mapTestTagsAsResourceIds`.** Found `TaskContextMenuSheet`.
2. **A `testTag` built from a `.label`.** Found `MenuBottomSheet` — a latent
   case, reported rather than allowlisted, because the identical shape had been
   a live bug hours earlier.

`MaestroFlowTagsTest` (extended):

3. **Every top-level `- command:` is a real Maestro command.** Found
   `clearState` in `backup/01-round-trip.yaml`, in the `smoke` set, in a flow
   that asserts an empty inbox it had just failed to produce.

### Why the first rule matches braces, and why that mattered

The obvious version — "this file opens a window and contains a `testTag`" — has
**five false positives** on the current tree, because most files tag their top
bar and open a dialog somewhere else entirely. A gate that cries wolf is worse
than no gate: it teaches people to ignore it, and the one real violation gets
ignored with it.

So the rule matches the window's own body. Two details each produced a false
positive before the rule was correct:

- **Comments are stripped first.** `MenuBottomSheet`'s KDoc names the composable,
  and a raw regex matched the prose.
- **The trailing content lambda is included.** For
  `ModalBottomSheet(onDismissRequest = …) { … }` the body lives *after* the
  call's closing paren, so matching parens alone reports an empty body — and
  misses the real case.

With both handled: 44 call sites scanned, zero violations, zero false positives.
Removing the exposure from `TaskContextMenuSheet` turns it red, which is the
only evidence that the rule is about the defect rather than about the file.

### Why `.title` and `.text` are not flagged, only `.label`

The obvious version of rule 2 flags six call sites and five of them are correct:
`testTag(TestTags.taskItem(task.title))` is right, because a flow creates the
task and therefore knows its title. Only `label` is reliably display text by
convention in this codebase.

This is the one rule in the file that rests on a judgement about naming rather
than on syntax, and the KDoc says so. The alternative — flagging all six and
allowlisting five — would have been more uniform and less honest about why the
other five are fine.

### Why the command rule matches only top-level list items

An earlier version matched every YAML key and reported 1200 violations, every one
of them an argument (`id:`, `visible:`, `timeout:`). A command is a key on a
top-level list item. The distinction is one regex anchor.

### The rule's first fix was wrong, and the device run said so

Rule 2 fired on `MenuBottomSheet.kt:74`, `testTag(TestTags.menuItem(item.label))`,
and the obvious fix was to give `MenuItem` a stable `testId` derived from its
`AppDestination` class name. That is exactly what the rule asks for, and it
broke the app menu.

The captured hierarchy from the next `smoke` run is the receipt: the drawer
rendered `menu_settings`, `menu_profileswitcher`, `menu_search` — and the flows
that select the menu by id were looking for `menu_profile_sync`,
`menu_quick_search`. `slug("Profile & sync")` had become
`slug("AppDestination.Settings")`. Six flows' worth of selectors, renamed by a
rule that was correct about the shape and wrong about this instance.

So the fix is reverted and the one line is an allowlist entry, with the reason
written down: the labels come from `MenuSections`, hardcoded English in the same
file, so they are not a translation surface today. The localisation risk is
theoretical; the breakage of "fixing" it was immediate and measured.

The generalisable part is worth more than the rule. **A rule that fires on a
real finding is still allowed to be wrong about the fix**, and the only thing
that caught it was running the flows — the same thing that found the six
original defects. Static analysis proposed the change; only the device could
refuse it. That is the argument for keeping the Maestro gate even once the
static rules exist, and for not treating a green rule as a finished fix.

## Rationale

The economics are the whole argument. Finding defect #1 cost one `grep` and
about twenty minutes. It will be found again in a year by a different person, in
a different file, and cost the same again. A rule that costs thirty lines of
test code and runs in `:shared:jvmTest` on every build changes that permanently.

The deeper reason is that **a check encodes a decision, and repeating the
decision is what we were paying for.** "Should this surface expose its tags?" was
answered by hand six times, three times correctly and three times not at all.
The rule does not get the answer right more often than I did — it gets it right
*every* time, including the times nobody looks.

`KNOWN_COMMANDS` is deliberately the wrong direction to be wrong in: a real
Maestro command that nobody has used yet is missing from the set, so using it
fails the build and asks the question. The reverse — a misspelling that happens
to collide with a real command — is not possible, because misspellings are not
commands.

## Consequences

- Three new failures are possible in `:shared:jvmTest`, all of them the defects
  described above. Two of them fired on their first run.
- The `.label` rule is a naming convention, so a future model that uses
  `label` for a *stable* id will trip it. That is the intended direction: the
  fix is a name, and the KDoc says what the name should be. It also means a
  firing rule needs its finding checked before it is "fixed" — see the
  `MenuBottomSheet` story above, where the check was correct and the fix was not.
- `MaestroFlowTagsTest` now needs the full documented Maestro command set, which
  is a maintenance obligation on a file that previously only knew the ids the
  project uses. A command added to a flow without being added here fails
  visibly, which is the intended cost.
- **The underlying claim is falsifiable and should be tested over time.** If
  these three rules sit for a year and never fire again, they were either right
  about the defect class or the class was already fixed. The way to tell is a
  future defect of this shape escaping them; that has not happened yet, and one
  year is not long enough to conclude anything.

## Links

- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/UiAutomationSelectorTest.kt` (new)
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/MaestroFlowTagsTest.kt`
- `docs/decisions/2026-10-04-testtag-visibility-helper.md` — the class of bug
- `docs/decisions/2026-10-04-maestro-flow-isolation.md` — the harness fix that
  made the first run possible
- `docs/decisions/deferred-backlog.md` — `a-flow-can-be-unrunnable-and-every-check-still-pass`,
  `overflow-menu-rows-were-tagged-with-a-nobody-reads-scheme`,
  `a-testtag-built-from-a-localised-label-changes-with-device-locale`
