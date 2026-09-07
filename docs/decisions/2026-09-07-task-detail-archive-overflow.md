# Archive in Overflow menu + Picker sheet chrome

## Context

После рефакторинга TaskDetailScreen (document-style) остались три недоделанные задачи:
1. FAB исчез на desktop (PermanentShell не имел Scaffold)
2. Archive action отсутствовал на TaskDetailScreen
3. ProjectPickerSheet и TagPickerSheet имели raw ModalBottomSheet без chrome (drag-handle, × close, центрированный заголовок)

## Decision

### 1. FAB desktop: PermanentShell wrap в Scaffold

`PermanentShell` (desktop permanent drawer) использовал голый `Box` для контента. `ModalShell` уже имел `Scaffold`. FAB в chrome-level AndroidShell не рендерился на desktop-версии.

**Решение:** обернуть `content(Modifier)` в `Scaffold` с `floatingActionButton` для `PermanentShell`.

### 2. Archive action

`TaskRepository.softDelete(id)` уже использует `archived_at` column — то же что и ArchiveScreen. Single-task archive = reuse `softDelete()`.

**Решение:**
- `TaskDetailUiEvent.ConfirmArchive` → `ActiveSheet.ConfirmArchive`
- `viewModel.confirmArchive()` + `viewModel.archiveTask(current)` (вызывает `taskRepo.softDelete()`)
- Overflow menu (⋮) в TopAppBar → "Archive" item → `ConfirmArchiveDialog`
- Dialog copy: "Archive task?", "This will move it to the Archive and remove it from your active lists."

### 3. Picker sheets chrome

`TaskEditorSheetHost` — канонический wrapper с drag-handle, × close, центрированным title, опциональной ✓ confirm. Priority/Reminder sheets уже его используют.

**Решение:** обернуть `ProjectPickerSheet` и `TagPickerSheet` в `TaskEditorSheetHost`. API (сигнатуры) не меняется — обратно совместимо.

## Consequences

- FAB работает на desktop для всех табов (Tasks, Projects, Notes)
- Archive доступен с любого TaskDetailScreen через ⋮ menu
- Picker sheets визуально согласованы с остальными sheets (drag-handle, chrome)
