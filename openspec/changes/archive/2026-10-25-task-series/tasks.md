# task-series — tasks

## 1. Domain

- [x] 1.1 `TaskSeriesId` — a `@JvmInline value class` over a `String`
- [x] 1.2 `Task.seriesId: TaskSeriesId?` — `null` for a task that stands alone
- [x] 1.3 `TaskSharedField` — the fields that describe the work
- [x] 1.4 `TaskSeriesPlanner` — the plan of a series; the ceiling of thirty dates
- [x] 1.5 `UpdateTaskSeriesUseCase` — the read-modify-write of a whole series

## 2. Storage

- [x] 2.1 `tasks.series_id` — nullable, with an index
- [x] 2.2 The auto-migration 44 → 45
- [x] 2.3 `TaskRepository.createSeries` / `updateAll` / `observeSeries`
- [x] 2.4 The backup format carries `seriesId`
- [x] 2.5 The sync allowlist carries `seriesId`, on the client and in the server seed

## 3. Creating

- [x] 3.1 `TaskDraft.extraDueDates`
- [x] 3.2 `TaskCreateIntent.SetDueDates`
- [x] 3.3 `CreateTaskFromDraftUseCase` goes through the planner
- [x] 3.4 The checklist and the attachments are copied onto every occurrence

## 4. The picker

- [x] 4.1 `MultiDatePickerSheet` in `core/ui`
- [x] 4.2 The month grid moves to `core/ui`, the calendar's `monthGridDates` delegates
- [x] 4.3 The create editor opens it from the date row

## 5. Editing

- [x] 5.1 `sharedFieldOf` — the one place that maps an intent to a field
- [x] 5.2 The entity slot and the draft slot write shared fields to the whole series
- [x] 5.3 The series card on the task detail
- [x] 5.4 "Применить ко всем" for the time of day and the pin
- [x] 5.5 "Удалить серию" archives every occurrence

## 6. The other entry points

- [x] 6.1 `TaskDraftKeys` — the one place the draft keys are built
- [x] 6.2 The route carries a complete draft key, not a section id
- [x] 6.3 The calendar's date-selection mode
- [x] 6.4 The agenda's top-bar button

## 7. Tests

- [x] 7.1 The planner: one date, several dates, the ceiling, the window's offset
- [x] 7.2 The repository: a series is written in one transaction
- [x] 7.3 The migration: 44 → 45
- [x] 7.4 The create flow: a draft with several dates becomes a series
