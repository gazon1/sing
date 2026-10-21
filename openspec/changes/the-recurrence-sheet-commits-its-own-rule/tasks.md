# Tasks

## 1. The picker brings its own chrome

- [x] 1.1 `TaskEditorSheetsHost` renders `RecurrencePickerSheet` directly, the way it
  renders the date and time pickers — no wrapper `TaskEditorSheetHost` around it.
- [x] 1.2 The outer host's `testTagConfirm = TestTags.SHEET_CONFIRM` — the parameter
  that rendered no button at all — is gone.
- [x] 1.3 `RecurrencePickerSheet`'s own host passes `testTagConfirm = TestTags.SHEET_CONFIRM`,
  so the control that commits the rule carries the tag.

## 2. The scenario is carried on the desktop tier

- [x] 2.1 `TaskRecurrenceScenarioTest` opens the picker, chooses Daily, commits it, and
  asserts that the editor's row reports the rule.
- [x] 2.2 The test's documentation says what the desktop tier can verify and what the
  Maestro flow still drives, and it no longer repeats the measured-on-a-broken-row claim
  that a sheet is unreachable from a JVM test.

## 3. The flow and the table stop claiming things that were never true

- [x] 3.1 `Maestro/flows/tasks/17-create-daily-recurring.yaml` no longer says that the
  recurrence picker is invisible to a JVM test.
- [x] 3.2 `Maestro/CONVENTIONS.md` says that the scenario is carried on both tiers.

## 4. Verification

- [x] 4.1 `:desktopApp:test --tests '*TaskRecurrenceScenarioTest*'` is green.
- [x] 4.2 `:desktopApp:test` in full is green.
- [x] 4.3 `:shared:detekt :desktopApp:detekt` is green.
- [x] 4.4 `scripts/ci/static-gates.sh` is green.
- [x] 4.5 Sabotage: removing the picker's tag makes the scenario test fail, because the
  button it presses is the one the tag names.
