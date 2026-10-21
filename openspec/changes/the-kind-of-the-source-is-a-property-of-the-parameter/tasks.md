# Tasks — the-kind-of-the-source-is-a-property-of-the-parameter

**status:** accepted

---

## Phase 1 — The bridge

- [x] The overload that takes the sealed parent and dispatches on it is gone
- [x] The two kinds stay two overloads, one per kind

## Phase 2 — The editor is split

- [x] `TaskEditorContent` takes the title and the description as `OwnedText`
- [x] `TaskCreateEditorContent` takes them as `StaticText`
- [x] The shared body takes the bridged states and never asks which kind it has
- [x] The field components take the state

## Phase 3 — The model

- [x] The model no longer carries the sources: they are the editor's parameters
- [x] The detail screen builds the model and the callback bundle it already had

## Phase 4 — The contract and the documentation follow

- [x] The rewrite table publishes the task editor's rows at the file that bridges the fields
- [x] The inventory table classifies that file, and drops the two field components
- [x] The contract checks that the bridge decides no kind at runtime
- [x] The decision is recorded
