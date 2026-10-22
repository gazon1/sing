# Tasks — every-close-refuses-in-its-own-write

## Phase 1 — the recurring close refuses in its write

- [x] **shared/**: completing one occurrence of a recurring task is refused by
      the write itself while a prerequisite is open, and the roll-forward writes
      nothing beside it.
      Test: a use-case test asserting the refusal, the untouched series and the
      unchanged due date.

## Phase 2 — the user is asked, and the answer decides

- [x] **shared/**: the recurring close asks the user when the write refuses, and
      the confirmed path closes the occurrence and moves the series.
      Test: a use-case test asserting the confirmed path, and a slot test
      asserting the question is asked and nothing is written.

## Phase 3 — the bulk close asks the same question

- [x] **shared/**: a selection containing a blocked task closes nothing, names
      the tasks in the way and keeps the selection.
      Test: a ViewModel test asserting the question, the untouched tasks and the
      selection; and a test asserting the confirmed path closes everything.

## Phase 4 — verification

- [x] **shared/** + **desktopApp/**: run the full gate set.
      Test: all four green.
