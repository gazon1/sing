# Tasks — a-closed-project-is-reachable

## Phase 1 — a list of the closed projects

- [x] **shared/**: the closed projects are read and shown, most recently closed
      first, below the open ones.
      Test: a ViewModel test asserting that a closed project leaves the project
      list and is listed among the closed ones.

## Phase 2 — the way back

- [x] **shared/**: a closed project can be opened again, and it returns to the
      project list.
      Test: a ViewModel test asserting the project is back in the list and is no
      longer a closed one.

## Phase 3 — the list is shown even when the project list is empty

- [x] **shared/**: a screen with no open project still shows the closed ones.
      Test: the state carries the closed projects on every variant, so the empty
      state cannot hide them.

## Phase 4 — verification

- [x] **shared/** + **desktopApp/**: run the full gate set.
      Test: all four green.
