---
title: "NoteEditor body load — store HTML directly, fix RichTextState init"
date: 2026-09-07
tags: [notes, room, rich-editor, di-graph]
status: accepted
---

## Context

При открытии существующей заметки в NoteEditor загружался только title, body был пустым. Пользователь мог набрать текст, но после переоткрытия — снова видел пустой body.

Две independent причины:
1. `EditorBody.kt:47` создавал `RichTextState()` пустым — `state.html` из `EditorState.Editing` никогда не передавался в RichTextState.
2. `NoteDao.updateContent` писал только `body_markdown`, колонка `body_html` всегда оставалась `null` (хотя существовала в схеме с v1).

Дополнительно: round-trip HTML→Markdown→HTML терял форматирование (списки, заголовки, ссылки) из-за неполного regex в `RichEditorMarkdownHtmlPort`.

## Idea

Три варианта:
1. **Только EditorBody fix** — инициализировать RichTextState из state.html, писать по-старому в markdown. Сохраняет round-trip потери.
2. **EditorBody + bodyHtml в БД** (выбран) — инициализировать RichTextState из state.html, писать HTML напрямую в колонку `body_html`. Lossless, no schema migration needed.
3. **Полный рефакторинг RichTextState** — выделить отдельный порт `HtmlStoragePort`, изолировать сериализацию. Слишком сложно для текущего use-case.

## Decision

1. `EditorBody` при инициализации вызывает `RichTextState().also { it.setHtml(state.html) }` — RichTextState сразу заполняется HTML'ом из EditorState.
2. `LaunchedEffect` пропускает первый emission через `firstLoadSkipped` guard — защита от false-positive autosave, когда `setHtml` сам триггерит `toHtml()` change.
3. `NoteDao.updateContent` теперь пишет `body_html = :html` напрямую, обе колонки обновляются атомарно.
4. `NotesRepository.createWithContent` и `updateContent` принимают `bodyHtml: String` и пишут его в entity/BD.
5. `NotesViewModel.openEditor` приоритетно читает `note.bodyHtml`, fallback на `bodyMarkdown → HTML` для legacy заметок.
6. `saveNow` / `scheduleAutosave` передают HTML напрямую: `repo.updateContent(..., markdown, html)`.

## Rationale

- `body_html` колонка уже существовала в схеме с v1 — никакой migration не нужно.
- Backup DTOs уже имели `bodyHtml` — экспорт/импорт бэкапов уже корректен.
- Запись HTML напрямую убирает round-trip потери форматирования.
- `firstLoadSkipped` необходим, иначе `setHtml` вызывает `LaunchedEffect` → `toHtml()` → `onBodyChange` → `isDirty = true` сразу при открытии заметки, до любого пользовательского ввода.

## Consequences

- `NoteDao.updateContent` сигнатура изменилась: добавлен параметр `html: String`.
- `NotesRepository.createWithContent` и `updateContent` сигнатуры изменились: добавлен параметр `bodyHtml: String`.
- `FakeNotesRepository` и `FakeNoteDao` обновлены同步.
- Все существующие тесты проходят — никаких изменений в тестовых вызовах не потребовалось (jvmTest зелёный).
- При первом открытии старой заметки (без `bodyHtml`) — форматирование может отличаться от исходного (round-trip через markdown). Это accepted trade-off для legacy data.

## Links

- Commit: `fix(notes): EditorBody init with setHtml, store bodyHtml directly in DB`
- Files: `EditorBody.kt`, `Daos.kt`, `NotesRepository.kt`, `NotesViewModel.kt`, `FakeRepositories.kt`, `FakeAppDatabase.kt`
- Tests: `:shared:jvmTest` green
