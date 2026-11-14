# Tasks — create-and-edit-are-one-editor

- [x] shared/ Carry the pin and the estimate into the created task: add both to
      the validator's input, map both in the task the validator's input builds,
      and pass both from the draft. Verified by `TaskCreateViewModelTest`:
      `SaveClicked with the pin set creates a pinned task` and
      `SaveClicked with an estimate creates a task with that estimate`. Both
      fail on the previous code, which is what makes them worth their names.
- [x] shared/ Give the creating editor the estimate row and the draft field it
      writes to. Verified on Desktop by the parity test below; the row's absence
      is what the test named.
- [x] shared/ Draw the start-date row on the detail, where it was hidden while
      the creating screen drew it. Verified by `CreateAndEditFieldParityTest`,
      which would draw one list shorter without it.
- [x] shared/ Show the title error on the creating screen only after an edit or
      a refused save, and keep it the only message drawn under the field.
      Verified by `CreateTaskFlowTest`:
      `a_freshly_opened_editor_shows_no_validation_message` and
      `clearing_the_title_shows_the_validation_message_under_the_field`.
- [x] desktopApp/ Hold the parity claim: compare the ordered row list of both
      surfaces, and record the necessarily one-sided rows with their reasons so a
      new one fails the comparison. `CreateAndEditFieldParityTest`.
- [x] shared/ Open the multi-date picker expanded, so its confirm row is on a
      360×800 screen. Half-expanded it was below the edge and absent from the
      accessibility tree, so the sheet could not be committed. Verified on the
      device by `13-create-full-task`, which reached the confirm only after the
      fix; a desktop component test cannot hold it, because the desktop host has
      no half-expanded state.
- [x] androidApp/ Maestro `13-create-full-task.yaml` asserts the estimate row, so
      the device run covers the field the create screen used to lack, and taps
      the multi-date confirm by id instead of the `OK` text it never had.
- [x] docs/ Record the decision and what stays different on purpose:
      `2026-11-14-create-and-edit-are-one-editor`, and register the change in the
      issue registry.
