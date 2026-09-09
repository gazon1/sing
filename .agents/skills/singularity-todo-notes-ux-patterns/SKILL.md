---
name: singularity-todo-notes-ux-patterns
description: Complete collection of Notes-specific UX patterns for the Singularity Todo KMP app. Covers Saved-pill animation, sticky bottom toolbar, hero block with meta chips, folder navigation, wikilink rendering, backlinks panel, and search with snippet highlighting. Use when implementing or modifying any Notes screen component. Built on top of singularity-todo-task-detail-ux (TickTick reference) and singularity-todo-swipe-actions.
---

# Notes UX Patterns — Complete Reference

This skill is the **single source of truth** for all Notes-specific UX patterns. It complements `singularity-todo-task-detail-ux` (which covers the Task document-style detail) and `singularity-todo-swipe-actions`.

## Overview of Patterns

| Pattern | PR | Reference |
|---|---|---|
| Saved-pill animation | PR #1 | TickTick |
| Sticky bottom toolbar | PR #1 | TickTick / Bear |
| Meta chips (time, words, chars) | PR #1 | TickTick |
| Swipe-to-pin / swipe-to-delete | PR #1 | Apple Notes |
| Pinned section (sticky header) | PR #1 | Apple Notes |
| Multi-select + bottom action bar | PR #1 | Apple Notes / iOS Mail |
| Filter chip row | PR #1 | TickTick |
| Quick-add row | PR #4 | TickTick |
| Empty state CTA | PR #5 | Apple Notes |
| Wikilinks `[[note://id]]` | PR #2 | Notion / Obsidian |
| Backlinks panel | PR #1 | Obsidian |
| NotePreview (read-only view) | PR #1 | Apple Notes / Notion |
| Generic InternalLinkPickerSheet | PR #3 | Obsidian |

## 1. Saved-Pill Animation (Phase 1)

**Reference:** TickTick — a small "Saved" pill appears top-right of the editor after autosave, fades after 1.2s.

**VM side — `SavedPulse` event:**
```kotlin
// NotesUiEvent.kt
sealed interface NotesUiEvent {
    data object NavigateBack : NotesUiEvent
    data class SaveFailed(val message: String) : NotesUiEvent
    data class AiResult(val text: String) : NotesUiEvent
    data object SavedPulse : NotesUiEvent  // ← one-shot signal
}

// NotesViewModel — emit after successful save:
private val _savedPulse = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
val savedPulse: SharedFlow<Unit> = _savedPulse.asSharedFlow()

private fun scheduleAutosave(id: String) {
    autosaveJob?.cancel()
    autosaveJob = scope.launch(Dispatchers.Unconfined) {
        autosaveScheduler.awaitTick()
        try {
            repo.updateContent(...).getOrThrow()
            _editorState.value = current.copy(isDirty = false)
            _savedPulse.emit(Unit)  // triggers UI animation
        } catch (e: Exception) {
            _events.emit(NotesUiEvent.SaveFailed(e.message ?: "Save failed"))
        }
    }
}
```

**Screen side — Animatable fade:**
```kotlin
@Composable
fun NoteEditorScreen(...) {
    val savedAlpha = remember { Animatable(0f) }

    LaunchedEffect(state.id) {
        vm.savedPulse.collect {
            savedAlpha.snapTo(1f)
            delay(1200)  // hold for 1.2s
            savedAlpha.animateTo(0f, animationSpec = tween(300))
        }
    }

    // Render in TopAppBar actions:
    // IconButton(onClick = onSaveNow) { Icon(Check, "Save") }
    // if (savedAlpha.value > 0.01f) {
    //     Text("Saved", modifier = Modifier.graphicsLayer { alpha = savedAlpha.value }, ...)
    // }
}
```

**Pure formatter for relative time:**
```kotlin
// core/ui/components/Formatters.kt
internal fun formatSavedRelative(now: Instant, savedAt: Instant): String {
    val diffMs = now.toEpochMilliseconds() - savedAt.toEpochMilliseconds()
    return when {
        diffMs < 60_000 -> "Saved just now"
        diffMs < 3600_000 -> "Saved ${diffMs / 60_000}m ago"
        else -> "Saved ${diffMs / 3600_000}h ago"
    }
}
```

## 2. Sticky Bottom Toolbar (Phase 1)

**Reference:** Bear (top), TickTick (bottom). Phase 1 uses bottom.

**Implementation:** See `singularity-todo-rich-editor` skill — "Sticky Bottom Toolbar" section.

**Toolbar button layout:**
```
[ B ] [ I ] [ U ] [ • ] [ </> ] [ ⋯ ]           [ ✨ ]
 primary row                          overflow  AI
```

**Overflow menu items:** H2, H3, Quote, Link, ~~Strikethrough~~ (Strike goes to primary?).

## 3. Hero Block with Meta Chips (Phase 1)

**Reference:** TickTick task detail — `<title> + chips row` above content.

**In NoteEditorScreen:**
```
┌──────────────────────────────────────────────┐
│ ← Note                            ✓ Saved   │  ← TopAppBar
├──────────────────────────────────────────────┤
│ Meeting Notes                    ✏️           │  ← Title (editable)
│ Updated 3 min ago · 142 words · 847 chars  │  ← Meta chips row
├──────────────────────────────────────────────┤
│ [B] [I] [U] [•] [</>] [⋯]           [✨]  │  ← Sticky bottom toolbar
├──────────────────────────────────────────────┤
│                                              │
│  Rich text body...                          │  ← RichTextEditor
│                                              │
└──────────────────────────────────────────────┘
```

**Meta chips implementation:**
```kotlin
@Composable
private fun MetaChipsRow(
    note: Note,
    wordCount: Int,
    charCount: Int,
    modifier: Modifier = Modifier,
) {
    val now = Clock.System.now()
    val relativeTime = formatRelativeShort(now, note.updatedAt)

    Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AssistChip(
            onClick = { },
            label = { Text(relativeTime, style = MaterialTheme.typography.labelSmall) },
            leadingIcon = {
                Icon(
                    Icons.Default.Schedule,
                    null,
                    modifier = Modifier.size(14.dp),
                )
            },
        )
        AssistChip(
            onClick = { },
            label = { Text("$wordCount words", style = MaterialTheme.typography.labelSmall) },
        )
        AssistChip(
            onClick = { },
            label = { Text("$charCount chars", style = MaterialTheme.typography.labelSmall) },
        )
    }
}
```

**Pure helpers:**
```kotlin
// feature/notes/NoteFormatters.kt
internal fun formatRelativeShort(now: Instant, then: Instant): String {
    val diffMs = now.toEpochMilliseconds() - then.toEpochMilliseconds()
    return when {
        diffMs < 60_000 -> "Just now"
        diffMs < 3600_000 -> "${diffMs / 60_000}m ago"
        diffMs < 86400_000 -> "${diffMs / 3600_000}h ago"
        else -> "${diffMs / 86400_000}d ago"
    }
}

internal fun wordCount(html: String): Int =
    html.replace(Regex("<[^>]*>"), "")  // strip HTML
        .split(Regex("\\s+"))
        .count { it.isNotBlank() }

internal fun charCount(html: String): Int =
    html.replace(Regex("<[^>]*>"), "").length
```

## 4. Folder Navigation (Phase 3)

**Reference:** Apple Notes folder hierarchy, Bear nested tags.

**Navigation model:**
- `NotesScreen` renders root-level notes + folders (where `parentNoteId = null`)
- Tapping a folder card pushes `NotesScreen(folderId = folderNote.id)` onto the nav back stack
- Back stack handles naturally via per-tab navigation (ADR `2026-09-05-android-bottom-nav`)

**Folder card:**
```kotlin
@Composable
fun FolderCard(
    folder: Note,
    childCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(folder.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium)
                Text("$childCount items", style = MaterialTheme.typography.bodySmall)
            }
            Icon(
                Icons.AutoMirrored.Filled.ChevronRight,
                contentDescription = "Open",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

**FAB chooser (ModalBottomSheet) — Phase 3:**
```kotlin
// In fabActionFor on Notes destination:
ModalBottomSheet(onDismissRequest = { /* hide */ }) {
    ListItem(
        headlineContent = { Text("New Note") },
        leadingContent = { Icon(Icons.Default.Article, null) },
        modifier = Modifier.clickable { /* create note */ }
    )
    ListItem(
        headlineContent = { Text("New Folder") },
        leadingContent = { Icon(Icons.Default.CreateNewFolder, null) },
        modifier = Modifier.clickable { /* create folder */ }
    )
}
```

## 5. Tags Row in Editor (Phase 3)

**Reference:** Bear — tags as inline chips at the bottom of the note.

```kotlin
@Composable
private fun NoteTagRow(
    tags: List<Tag>,
    onRemoveTag: (TagId) -> Unit,
    onAddTag: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tags.forEach { tag ->
            InputChip(
                selected = false,
                onClick = { onRemoveTag(tag.id) },
                label = { Text(tag.name) },
                trailingIcon = {
                    Icon(Icons.Default.Close, "Remove tag", modifier = Modifier.size(14.dp))
                },
            )
        }
        // "+ Add tag"
        AssistChip(
            onClick = onAddTag,
            label = { Text("+ Add tag") },
            leadingIcon = {
                Icon(Icons.Default.Label, null, modifier = Modifier.size(14.dp))
            },
        )
    }
}
```

## 6. Wikilinks `[[note://id]]` (PR #2)

**Reference:** Obsidian `[[wikilink]]`, Notion `@mention`.

**URL scheme:**
```
note://{noteId}   → displayed as [[Note Title]] in editor
task://{taskId}   → displayed as [[Task Title]] in editor
```

**Storage:** Links are stored as `<a href="note://...">` in `RichTextState` HTML. The richeditor library serializes `RichSpanStyle.Link` automatically.

**Extraction:** `OutgoingLinksExtractor.extractOutgoingLinks(html)` parses the HTML with a regex to find all `note://` and `task://` hrefs, deduplicates, and returns `List<LinkRef>`. Persisted to `NoteEntity.outgoing_links` column on every save.

**Insertion:** `RichTextState.addLinkToSelection(url)` inserts a link span at the current cursor/selection.

**InternalLinkPickerSheet:** Generic merged sheet. Caller (NoteEditorScreen) provides `onSearch` that merges `searchNotes` + `searchTasks` results into `List<LinkResult>`.

See `singularity-todo-rich-editor` skill for full implementation details.

## 7. Backlinks Panel (PR #1)

**Reference:** Obsidian — shows all notes that link to the current note.

**Architecture:**
- `NotePreview` VM observes the note via `repo.watchNote(id)` and fetches backlinks via `linkRepo.getBacklinkNotes(noteId)`
- `NotePreviewScreen` shows `RichText(state)` (read-only) and a `BacklinksSheet` ModalBottomSheet
- `BacklinksSheet` shows `ListItem` per linking note with extracted preview snippet

**DAO query:**
```sql
SELECT * FROM notes
WHERE deleted_at IS NULL
AND outgoing_links LIKE '%note://' || :noteId || '%'
LIMIT 20
```

**Preview snippet:** `extractPreviewText(linkingNote.bodyMarkdown, 80)` strips markdown for clean display.

## 8. NotePreview — Read-Only View (PR #1)

**Reference:** Apple Notes, Notion — open a note to read, tap Edit to modify.

**Separation:** `NoteView` (read-only) ≠ `NoteEditor` (edit). Two separate routes and ViewModels.

**Screen layout:**
```
┌──────────────────────────────────────────────┐
│ ← Back    Backlinks(3)    ⋮ (Edit/Delete)  │  ← TopAppBar
├──────────────────────────────────────────────┤
│ Meeting Notes                     Updated 3m  │  ← Hero: title + timestamp
│                                  142 words   │
├──────────────────────────────────────────────┤
│                                              │
│  Rich text body...                          │  ← RichText(state) read-only
│  [[Link to another note]]                   │
│                                              │
├──────────────────────────────────────────────┤
│ [ Edit ]    [ Backlinks (3) ]   [ Delete ] │  ← BottomAppBar
└──────────────────────────────────────────────┘
```

**BottomAppBar actions:**
- FilledTonalButton "Edit" → navigates to `NoteEditor(noteId)`
- OutlinedButton "Backlinks(count)" → opens `BacklinksSheet`
- OutlinedButton "Delete" → AlertDialog confirmation → soft delete → navigate back

**Key difference from editor:** Uses `RichText(state)` composable (read-only) instead of `RichTextEditor`. No cursor, no keyboard, no autosave. Clean reading experience.

## 8. Search with Snippet Highlight (Phase 5)

**Reference:** Obsidian search results, Apple Notes spotlight.

```kotlin
@Composable
fun NoteSearchResult(
    note: Note,
    query: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlightedTitle = buildAnnotatedString {
        val start = note.title.indexOf(query, ignoreCase = true)
        if (start >= 0) {
            append(note.title.substring(0, start))
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold, background = MaterialTheme.colorScheme.tertiaryContainer))
            append(note.title.substring(start, start + query.length))
            pop()
            append(note.title.substring(start + query.length))
        } else {
            append(note.title)
        }
    }

    // Body snippet with context
    val snippet = note.bodyMarkdown
        ?.let { body -> extractSnippet(body, query, contextChars = 50) }
        ?: ""

    ListItem(
        modifier = modifier.clickable(onClick = onClick),
        headlineContent = { Text(highlightedTitle, maxLines = 1) },
        supportingContent = {
            Text(
                snippet,
                maxLines = 2,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            Icon(Icons.Default.Article, null, modifier = Modifier.size(20.dp))
        }
    )
}

private fun extractSnippet(body: String, query: String, contextChars: Int): String {
    val idx = body.indexOf(query, ignoreCase = true)
    if (idx < 0) return body.take(100)
    val start = (idx - contextChars).coerceAtLeast(0)
    val end = (idx + query.length + contextChars).coerceAtMost(body.length)
    return "…${body.substring(start, end)}…"
}
```
