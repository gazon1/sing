# Tasks — the-editor-owns-its-sheets-and-its-model

**status:** accepted

---

## Phase 1 — One sheet state

- [x] The state the caller's rows open a sheet through is the state the sheets are
      rendered from
- [x] The create screen hands its state to the editor
- [x] The editor falls back to its own state only when the caller has none

## Phase 2 — One model

- [x] The sheets are rendered from the model the editor was given
- [x] The sheets are rendered from the callback bundle the editor was given
- [x] The second model and the second callback bundle are gone

## Phase 3 — The API

- [x] The exploded editor body is private: only the scaffold and the previews use it

## Phase 4 — The proof

- [x] `desktopApp/`: tapping an attribute row in the create editor opens its picker
- [x] `desktopApp/`: the picker writes the value back to the row that opened it
- [x] The proof fails without the fix
- [x] The decision is recorded
