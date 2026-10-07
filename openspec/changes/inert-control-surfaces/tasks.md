# Tasks — inert-control-surfaces

**status:** proposed

---

## Phase 1 — The rule

- [x] `NoEmptyOnClickLambdaPolicy.isHandlerParameter` — `on` + capital, replacing the list
- [x] Exemption: `Result.fold` branch labels, justified in source
- [x] Exemption: empty `onValueChange` beside `readOnly = true` in the same call
- [x] Near-miss tests (`onResult`, `onSucces`, `onSuccessful`) so exemptions cannot widen
- [x] Preview body freed **before** the name check, not after

## Phase 2 — The detector

- [x] `Tile`, `Row`, `Dialog` suffixes
- [x] Preview bodies blanked before counting, covering `@Preview` and the `*Preview*`
      convention
- [x] Co-located callers counted — `TagCard` is called by `TagList` in the same file
- [x] Positive and negative controls for each fix

## Phase 3 — What it found

- [x] `TaskEditor{Priority,Estimate,DueDate}Row` — conditional `clickable`
- [x] `TaskEditorContent.onPriorityClick` — pass `null`, not `?: {}`
- [x] `onCheckToggle` nullable; the checkbox is hidden in create mode
- [x] `FirstRunSection` handlers nullable; a chip with no handler is not rendered
- [x] `AgendaNavGraph.jvm` `onAiAction` — `null`, not a menu of five dead items
- [x] GenUI `DefaultDataContext.onDataChange` nullable
- [x] `PomodoroScreen` preview — `noopClick`, and the baseline line **deleted**
- [x] `BackupScreen` preview — `noopClick`, and the baseline line **deleted**

## Phase 4 — Outside the gates

- [x] K-1: the appearance contributor was bound in two modules; one definition removed
- [x] K-2: `RemoteConfigPort` is consumed by the gate module and the AI service — not dead
- [ ] Follow-up: teach the detector about bound-but-never-injected ViewModels

## Phase 5 — Deliberately not deleted

- [ ] `ReminderTile` — finished but unreachable. Which screen should carry it is a product
      decision; baselined with a live backlog reference
