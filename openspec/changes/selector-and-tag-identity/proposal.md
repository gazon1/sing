# selector-and-tag-identity

Issues: #107, #109, #108 · Backlog entries:
`agenda-editor-no-selector-parameter-configuration`,
`a-testtag-built-from-a-localised-label-changes-with-device-locale`,
`fake-clock-unused-in-desktop-harness`

## What

Give every user-selectable identity in the app a stable source that is not a
translatable string and not the wall clock.

## Why

Two places in this app derive an identity from something that is not an
identity, and both fail silently.

**Selector templates cover three of five families.** `SelectorTemplate.kt` ships
`Fixed`, `ByTags`, `ByProjects`, `ByPriority`, `ByStatus`. There is no template
for `Selector.Regexp` or `Selector.DateRange`, so `selectorOptionsFor` returns
an empty list and those two are reachable only by hand-editing JSON — while the
engine supports them at `AgendaPresets.kt:241` and `SelectorSerializer.kt`. The
original entry claimed all five were reachable; three are.

**Test tags can be derived from displayed text.** `TaskEditorCallbacks.kt:78`
declares `TaskEditorMenuItem(val label: String, onClick, testTag: String? = null)`
with no stable action id, so `TaskContextMenuSheet.kt:96-100` builds
`.testTag(TestTags.taskAction(label))` from the string the user is reading. The CI
device is Russian: a translated label produces a different tag and a Maestro
selector stops matching, with the flow still running.

The overflow menu already has the right shape — `TaskDetailViewScreen.kt:566-584`
tags rows with `TestTags.EditorOverflow.ARCHIVE` / `DELETE` / `RESTORE`
constants. A constant is the identity; a label is only what is displayed. The
context menu just has not been converted.

Distinct from #90, which sweeps the flows that select by localised *text*. That
is the symptom sweep; this is the cause. As long as the tag is computed from the
label, every flow written against it inherits the dependency and #90 can only
treat instances.

**A date assertion is a fact about the clock.** `CalendarFlowTest.kt:38,48,66`
runs `runDesktopAppTest` with no clock and asserts month and week boundaries
derived from `todayInSystemZone()`. The harness supports injection
(`runDesktopAppTest(clock = …)`, used at `AgendaBadgePolicyFlowTest.kt:76`); the
call sites are what is missing. A calendar test is correct today and wrong at a
month boundary.

## How

The shared thread is that all three derive meaning from a string or a moment
instead of from an identifier. The fix in each case is to carry the identifier
explicitly and let the display value be display.

For the selectors there is a real decision, not just a gap: `Regexp` needs a
text field and `DateRange` a from/to picker — two new sheet shapes, neither of
which is `MultiSelectSheet`. If that is not worth it, the honest alternative is
to document both as engine/preset-only and say so in the editor's own copy. A
silent gap is the failure mode worth avoiding; either answer is acceptable, not
knowing is not.

For the tags, the static check is the part that stops it recurring. A tag is an
interface, and one built from a translatable string is not a stable interface.
