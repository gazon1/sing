---
title: "NoteBody canonical HTML — single source of truth for note body"
date: 2026-10-02
tags: [tech-debt, notes, domain, encapsulation]
status: accepted
supersedes: [2026-09-07-rich-text-encoding]
---

# NoteBody canonical HTML — single source of truth for note body

## Context

`Note` (domain model) carries both `bodyMarkdown: String?` and `bodyHtml: String?`.
`NoteEditor` writes `bodyHtml` on every autosave and sets `bodyMarkdown = null`.
`NotesRepositoryImpl.updateContent` computes `wordCount` from `bodyMarkdown`, which is
always null in production — therefore word count is always 0, char count is always 0,
and backlinks (`outgoing_links` column, queried by `getBacklinkNotes`) are never populated.

The architecture intent (per ADR 2026-09-07) was: rich-editor stores HTML; markdown is
derived on demand for AI and export. The implementation broke this invariant at the
first keystroke after creation.

## Decision

### 1. `NoteBody` as single canonical representation

```kotlin
// feature/notes/domain/model/NoteBody.kt — чистый, без зависимостей
@Serializable @JvmInline
value class NoteBody(val html: String) {
    val isEmpty: Boolean get() = html.isBlank()
    val plainText: String get() = html.stripTags().decodeHtmlEntities()
    val wordCount: Int get() = plainText.split(Regex("\\s+")).count { it.isNotBlank() }
    val charCount: Int get() = html.length
    val outgoingLinks: List<String> get() = extractTaskAndNoteLinks(html)
    companion object { val Empty = NoteBody("") }
}
```

Projections (`plainText`, `wordCount`, `charCount`, `outgoingLinks`) are computed eagerly
on write and stored as columns. `markdown` is computed lazily on read via `toMarkdown()`.

`OutgoingLinksExtractor` переезжает в `feature/notes/domain/logic/` как чистые `internal`
функции — `extractOutgoingLinks` продолжает использоваться `TaskOutgoingLinks.kt`.

### 2. `NoteBodyMarkdown.kt` — ЕДИНСТВЕННЫЙ файл с UI-импортом

```kotlin
// feature/notes/domain/model/NoteBodyMarkdown.kt
// UI-импорт: com.mohamedrejeb.richeditor (allowlist, долг документирован ниже)
fun NoteBody.Companion.fromMarkdown(markdown: String): NoteBody = NoteBody(
    RichTextState().apply { setMarkdown(markdown) }.toHtml()
)
fun NoteBody.toMarkdown(): String = RichTextState().apply { setHtml(this@toMarkdown.html) }.toMarkdown()
```

Это **единственный** файл в `domain/` с импортом richeditor. Причина: Rule of Three
запрещает абстракцию с одной реализацией; port+impl+DI+binding+тесты DI стоят дорого;
скилл `singularity-todo-rich-editor` явно удалил аналогичный `MarkdownHtmlPort`. Долг:
Konsist-allowlist, объяснение в этом ADR.

### 3. `Note` остаётся в `Ids.kt`, `bodyMarkdown`/`bodyHtml` заменяются на `body: NoteBody`

```kotlin
// Ids.kt — Note остаётся рядом с NoteId (не в domain/model/)
@Serializable
data class Note(
    val id: NoteId,
    val userId: UserId,
    val title: String = "",
    val body: NoteBody = NoteBody.Empty,   // ← заменяет bodyMarkdown + bodyHtml
    // ... остальные поля без изменений
)
```

`bodyMarkdown` и `bodyHtml` убираются из `Note`. Миграция схемы не нужна:
колонки остаются, `NoteEntity` не меняется, value class на колонку Room не ставится
— `@ColumnInfo` на уровне DAO.

### 4. Репозиторий: `saveContent` вместо pass-through upsert

```kotlin
suspend fun saveContent(id: NoteId, title: String, body: NoteBody): Result<Note>
suspend fun createWithContent(id: NoteId, title: String, body: NoteBody): Result<Note>
suspend fun createForTask(taskId: TaskId, title: String, body: NoteBody = NoteBody.Empty): Result<Note>
```

- `setOutgoingLinks` убирается из порта: ссылки выводятся при записи.
- Порядок: guard → upsert → **производные (счётчики, outgoingLinks)** → enqueue
  (по KDoc `GenericUserScopedRepository`).
- `updateContentForUser` расширяется `outgoingLinksJson` — одна атомарная UPDATE.
- **Create vs update решает репозиторий**: user-scoped UPDATE; `rows==0` → insert;
  PK conflict → `Result.failure`. Флаг `isNew` не нужен.
- `task://` токен **объединяется** (union, не LWW) с выведенными из тела:
  один user-scoped read существующей строки.

### 5. Мапперы: `NoteEntity.toNote` на `NoteBody`

```kotlin
// NotesMappers.kt
body = bodyHtml?.let(::NoteBody)
    ?: bodyMarkdown?.let { NoteBodyMarkdown.fromMarkdown(it) }
    ?: NoteBody.Empty
```

При чтении: `bodyHtml` есть → канон; есть только `bodyMarkdown` (старые строки) →
конвертация; оба null → `Empty`.

### 6. `NoteDto` (бэкап): оба поля заполняются из `NoteBody`

```kotlin
// BackupDtos.kt
fun Note.toDto() = NoteDto(
    // ...
    bodyMarkdown = body.toMarkdown(),   // projection, not storage
    bodyHtml = body.html,               // canonical
)
fun NoteDto.toNote() = Note(
    body = bodyHtml?.let(::NoteBody)
        ?: bodyMarkdown?.let { NoteBodyMarkdown.fromMarkdown(it) }
        ?: NoteBody.Empty,
    // ...
)
```

## Why not a port?

A port (`NoteBodyCodec`) with a single implementation would conflict with two
independent constraints: `singularity-todo-rich-editor` explicitly removed `MarkdownHtmlPort`
saying "the library handles this natively"; and `kotlin-idioms` / Rule of Three forbids
abstractions with single implementations. The members-on-type approach violates the
letter of "no UI imports in domain" but costs one file vs a port+impl+DI+binding+two
DI tests, and the violation is documented with a Konsist allowlist entry.

## What breaks (and what to do about it)

| Breaking change | Mitigation |
|---|---|
| `Note.bodyMarkdown` / `bodyHtml` removed | Callers updated to `note.body.toMarkdown()` / `note.body.html` |
| `NoteEditor` no longer calls `repo.upsert` | Uses `repo.saveContent` which calls upsert internally with derived columns |
| `EditorState.Editing(html)` → `EditorState.Editing(body: NoteBody)` | EditorSession converts; `NoteContentMapper` deleted |
| `body_markdown` never written again | Old rows with markdown are migrated on first read; after first edit the row has HTML only |
| `NoteDto` loses 9 fields (not written to backup) | This was already happening silently; reflective test catches it going forward |

## Open questions

- **Legacy migration**: rows with `body_html = NULL` and `body_markdown != NULL` are
  converted once via `fromMarkdown`. After conversion `body_markdown` is stale and never
  read again. This is correct but should be documented in a schema comment.
- **`find-unwired-surfaces.py` false confidence**: script has no dead-domain-class detector.
  The 7+ dead domain classes found by reference scan are invisible to it. Status: deferred
  to `docs/decisions/deferred-backlog.md` — do not rely on this script for coverage.
- **Sync format change**: old client sends `{bodyHtml: …}`, new client reads `body` (empty);
  `ignoreUnknownKeys = true` silently treats it as empty. This is a known risk, documented
  in the risk register, not a blocker.

## PR-1 implementation findings (2026-10-02)

### Bugs fixed in PR-1 (not deferred)

| Bug | File | Fix |
|-----|------|-----|
| `NoteEditor.createNoteForTask` called wrong overload (returned `Note` not `NoteId`) | `NoteEditor.kt:170-174` | Used new API returning `Note`, eliminated `newNoteId` variable |
| `uid.value` (String) passed where `UserId` expected | `NotesRepositoryImpl.kt:206` | Changed to `uid` (the `UserId` object) |
| `NoteContentMapper` KDoc referenced deleted `setOutgoingLinks` | `NoteContentMapper.kt:23` | Updated KDoc to reference `NotesRepositoryImpl.saveContent` |
| `createNoteForTask` called with `bodyMarkdown=null, bodyHtml=null` | `NoteEditor.kt:170` | Changed to `repo.createForTask(taskId, title = "")` |
| Template tests checked `bodyMarkdown` (now always null) | `DailyNoteFactoryTest.kt`, `TemplatePickerTest.kt` | Changed assertions to `bodyHtml` (canonical) |
| `updateContentForUser` in test missing `outgoingLinksJson` param | `AppDatabaseFactoryJvmTest.kt:173` | Added `"[]"` parameter |
| `NotesRepositorySyncTest` tested dead `setOutgoingLinks` | `NotesRepositorySyncTest.kt:164-171` | Replaced with `saveContent` test verifying link derivation |
| `NoteBodyMarkdown` extension shadowed companion method | `NotesMappers.kt:30` | `NoteBodyMarkdown.fromMarkdown` → `NoteBody.fromMarkdown` |
| Extension function `String.stripTags()`/`String.decodeHtmlEntities()` resolution failure inside value class properties | `NoteBody.kt:45-52` | Converted to regular private top-level functions with explicit `html: String` parameter |
| Test `bodyHtml` assertion used wrong expected value | `NoteBodyTest.kt` | Fixed `assertEquals(15, ...)` → `assertEquals(12, ...)` (`<p>hello</p>` = 12 chars) |
| `NoteEntity` uses `outgoingLinks` (not `outgoingLinksJson`) for column | `Entities.kt:53,124` | Tests updated to use `outgoingLinks = "[]"` |
| Duplicate `OutgoingLinksExtractor.kt` in two locations | `feature/notes/OutgoingLinksExtractor.kt` | Deleted old file; `domain/logic/` version is canonical |
| Old 4-arg `createWithContent` still returned `NoteId` | `FakeRepositories.kt:1562` | Overload preserved for existing test compatibility |
| `NotePreviewScreen.kt` used `extractPreviewText` (dead) | `NoteFormatters.kt` | Deleted function; call sites updated to `body.stripPreview()` |
| `NoteEditor.kt` dead `cachedNote`/`editingAsNote` | `NoteEditor.kt` | Deleted field and function (never read) |
| `NoteBodyMarkdown.kt` had unclosed KDoc comment | `NoteBodyMarkdown.kt` | Rewrote KDoc to avoid nested `/* */` inside `/** */` |
| `desktopApp:test` blocked by Gradle 9 optional inputs | `desktopApp/build.gradle.kts` | Added `.optional(true)` + `systemProperty` forwarding for null values |
| Konsist guard blind spot: fully-qualified richeditor imports | `NoteBody.kt` | Created `NoteBodyMarkdown.kt` as sole allowlisted file; removed fully-qualified uses from `NoteBody.kt` |
| `stripPreview` inconsistent with `plainText` | `NoteBody.kt` | Moved to `NoteBody.stripPreview()` using `plainText` internally |

### What was actually implemented

The PR was done as a single batch (PR-1/2/3 combined). Key deviations from the plan above:

- **`Note` stayed in `Ids.kt`** — not moved to `domain/model/`. The ADR described a move but it was not necessary; `NoteBody` is in `domain/model/` which is sufficient.
- **`NoteBodyMarkdown.kt` was CREATED, not deleted** — it is the sole allowlisted file for richeditor imports, as the architecture section intended. The ADR's PR-1 notes incorrectly said it was deleted.
- **`NoteEditor` autosave bug (erasing flags) was deferred** — the worktree still has `NoteEditor` using legacy fields and `upsert`. Full fix awaits a separate PR.
- **`EditorSession` AI-reseed bug was not addressed.**
- **`NoteContentMapper.kt` WAS deleted** — as described in PR-3.
- **`NoteEditorState` WAS deleted** — as described in PR-3 (11 dead tests removed).
- **`NoteContentMapperTest` (7 tests) WAS deleted** — as described in PR-3.

### Still deferred

- `EditorSession` AI-reseed bug (external writes not reflected in editor state)
- `TaskDto.dependsOn` never assigned in `toDto()` — backup silent data loss
- `NoteDto` loses 9 columns on every export
- `DebugSeedActivity.kt:104` calls `taskRepo.upsert` instead of `taskRepo.create`
- `Daos.kt:566` JOIN without `t.user_id = p.user_id` predicate
- `BackupDtoCoverageTest` (reflective: every entity constructor parameter must appear in DTO)
- Two parsers for `outgoing_links` — see `deferred-backlog.md:two-parsers-for-outgoing-links-potential-divergence`
- `NotePreview VM .first()` anti-pattern — see `deferred-backlog.md:note-preview-vm-first-architectural-smell`

### Fixed after PR-1

- **`NoteEditor` autosave erasing flags** — `saveContent` уже используется (autosave lambda, строка 69). Баг пофишен.
