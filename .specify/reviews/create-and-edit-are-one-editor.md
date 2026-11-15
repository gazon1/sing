---
change: create-and-edit-are-one-editor
verdict: pass
date: 2026-11-15
pr: 2950
reviewed: 3b23015a86d1ee670b4a5f5e9dd4912e0e159fa3
documents: sha256:9ab738dfc2e287db7c7e46980bebca06eb296a6005b951fd17bff38c907e7131
---

## What was reviewed

The change `create-and-edit-are-one-editor` — its contract documents, as the digest above records them, against the implementation in `3b23015a8`.

Verified against the code rather than the change's own summary:

- **The write path carries the two fields.** `TaskDomain.createInput` takes `estimateMinutes` and `isPinned`, and `TaskDomain.buildTask` maps both into the `Task`. The claim that the rows used to accept input and drop it is what the diff shows: those two mappings are the whole write-path change.
- **The validator withholds the first-frame error in one place.** `DraftMviViewModel` passes `includeValidationError = false` on its initial state and recomputes `validate()` on every edit and on a refused save. `TaskCreateScreen` now reads `state.error` instead of calling `TaskTitleValidation.error` itself, so "after interaction" has the owner the spec asks for and a persistence failure cannot arm the title line.
- **The multi-date picker opens expanded.** `MultiDatePickerSheet` passes `rememberAppSheetState(SheetValue.Expanded)`; the half-expanded row the spec names is no longer reachable on a 360×800 screen.
- **The parity claim has an expected list, not a mirror.** `CreateAndEditFieldParityTest` compares both surfaces against `SHARED_ROWS` and against each other, with the one-sided rows named and reasoned.
- **The field call sites match.** `TaskCreateScreen` passes the estimate bundle and the draft estimate; the detail passes the start-date row through the existing schedule intents.

The contract is coherent with the recorded decision (`docs/decisions/2026-11-14-create-and-edit-are-one-editor`) and leaves the two save lifecycles separate, as REQ-PARITY-4 requires.

## Residual risk

The device-only assertions in `13-create-full-task` and the Android `CreateTaskFlowTest` cases named in `tasks.md` were not re-run in this review; the desktop parity test and the shared unit tests cover the contract, and the picker's expanded state is a constant rather than a behaviour.
