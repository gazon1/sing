---
name: singularity-todo-rich-editor
description: Rich-text (WYSIWYG) editor pattern for Kotlin Multiplatform notes using com.mohamedrejeb.richeditor:richeditor-compose 1.2.0. Use when adding or modifying the notes editor, markdown toolbar, EditorAction sealed interface, EditorToolbar, sticky bottom toolbar pattern, or Markdown shortcut detection. Covers RichTextState, SpanStyle, HeadingStyle, debounced autosave, EditorSession hoisting, InternalLinkPickerSheet, wikilink round-trip, and the dual-scope coroutine strategy for NotesViewModel.
---

# Skill: Rich-Text Editor for Notes (WYSIWYG)

## When to Use This Skill

Use when implementing or modifying the rich-text editor in `feature/notes/`. This skill covers:
- Toolbar design (sticky bottom via `Scaffold.bottomBar`, overflow menu)
- `EditorAction` sealed interface and `RichTextState.apply()`/`isActive()` extensions
- `EditorSession` — the hoisting pattern for sharing `RichTextState` between toolbar and body
- Internal link picker (Obsidian-style `[[Note Title]]` / `[[Task Title]]`)
- External URL link dialog (`LinkUrlDialog`)
- Backlinks panel (`BacklinksSheet`)
- Wiki-link round-trip in `RichEditorMarkdownHtmlPort`
- Markdown shortcut auto-detection (Phase 5)
- Debounced autosave with `AutosaveScheduler`

## Architecture Overview

```
feature/notes/
├── NoteEditorScreen.kt          # Screen composable, EditorSession, EditorTitleAndBody
├── NoteEditorScreen.kt          # LinkUrlDialog, BacklinksSheet, MetaChipsRow
├── EditorAction.kt              # sealed interface for toolbar actions
├── Ids.kt                      # NoteId, Note, NoteColor
├── NotesViewModel.kt            # Single VM: list + editor state + debounced autosave
├── NotesUiEvent.kt              # Per-feature sealed UiEvent
├── ComposingMarkdownHtmlPort.kt # toHtml/toMarkdown + wikilink round-trip
├── MarkdownHtmlPort.kt          # Fun interface: toHtml / toMarkdown
└── components/
    └── EditorToolbar.kt        # Sticky bottom toolbar with overflow menu

core/ui/components/
└── InternalLinkPickerSheet.kt  # Bottom sheet with Notes/Tasks tabs + search
```

## EditorSession Hoisting Pattern

`RichTextState` has no stable equality (uses internal `MutableState`), so it **must** be created via `remember` and stored at the screen level.

```kotlin
// NoteEditorScreen.kt

/** Holds all mutable editor state for one note-editing session. */
private class EditorSession(
    val richTextState: RichTextState,
    var titleFieldValue: String,
    private var lastDispatchedHtml: String,
    private var firstLoadSkipped: Boolean,
    private val onBodyChange: (id: String, html: String) -> Unit,
    private val id: String,
) {
    fun dispatchHtml() {
        val html = richTextState.toHtml()
        if (html != lastDispatchedHtml) {
            lastDispatchedHtml = html
            onBodyChange(id, html)
        }
    }
}

@Composable
private fun rememberEditorSession(
    state: EditorState.Editing,
    onBodyChange: (id: String, html: String) -> Unit,
): EditorSession {
    // Fresh RichTextState per note — key prevents stale state when switching notes
    val richTextState = remember(state.id) {
        RichTextState().also { it.setHtml(state.html) }
    }
    val session = remember(state.id) { EditorSession(...) }

    // Dispatch HTML on every text mutation (not just toolbar clicks)
    LaunchedEffect(state.id, richTextState) {
        if (!session.isFirstLoadSkipped) { session.notifyFirstLoadSkipped(); return@LaunchedEffect }
        session.dispatchHtml()
    }
    return session
}
```

**Why this pattern:**
- `RichTextState` identity has no `equals()` — `remember` keyed on `state.id` guarantees a fresh state per note
- `EditorToolbar` (in `Scaffold.bottomBar`) and `EditorTitleAndBody` (in content area) share the same session
- `LaunchedEffect` over `richTextState.annotatedString` dispatches HTML on every keystroke (not just toolbar clicks)

## EditorAction Sealed Interface

```kotlin
sealed interface EditorAction {
    data object Bold : EditorAction
    data object Italic : EditorAction
    data object Underline : EditorAction
    data object Strike : EditorAction
    data object Code : EditorAction
    data object H1 : EditorAction
    data object H2 : EditorAction
    data object H3 : EditorAction
    data object Bullet : EditorAction
    data object Ordered : EditorAction
    data object Quote : EditorAction       // ⚠️ no blockquote in library — no-op
    data object AlignLeft : EditorAction
    data object AlignCenter : EditorAction
    data object AlignRight : EditorAction
    data object ExternalLink : EditorAction  // opens LinkUrlDialog
    data object InternalLink : EditorAction // opens InternalLinkPickerSheet

    companion object {
        val primary = listOf(Bold, Italic, Underline, Strike, H1, Bullet, Ordered)
        val overflow = listOf(H2, H3, Code, Quote, AlignLeft, AlignCenter, AlignRight, ExternalLink, InternalLink)
    }
}
```

## RichTextState.apply() and isActive() Extensions

Located in `EditorToolbar.kt` — pure helpers, no Compose dependency, unit-testable.

```kotlin
// components/EditorToolbar.kt

internal fun RichTextState.apply(action: EditorAction): RichTextState = when (action) {
    EditorAction.Bold        -> apply { toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold)) }
    EditorAction.Italic     -> apply { toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic)) }
    EditorAction.Underline  -> apply { toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline)) }
    EditorAction.Strike     -> apply { toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) }
    EditorAction.Code       -> apply { toggleCodeSpan() }
    EditorAction.H1         -> apply { setHeadingStyle(HeadingStyle.H1) }
    EditorAction.H2         -> apply { setHeadingStyle(HeadingStyle.H2) }
    EditorAction.H3         -> apply { setHeadingStyle(HeadingStyle.H3) }
    EditorAction.Bullet     -> apply { toggleUnorderedList() }
    EditorAction.Ordered    -> apply { toggleOrderedList() }
    EditorAction.Quote      -> this   // ⚠️ no blockquote in richeditor-compose 1.2.0 — no-op
    EditorAction.ExternalLink, EditorAction.InternalLink -> this  // handled via dialog
    EditorAction.AlignLeft   -> apply { toggleParagraphStyle(ParagraphStyle(textAlign = TextAlign.Start)) }
    EditorAction.AlignCenter -> apply { toggleParagraphStyle(ParagraphStyle(textAlign = TextAlign.Center)) }
    EditorAction.AlignRight  -> apply { toggleParagraphStyle(ParagraphStyle(textAlign = TextAlign.End)) }
}

internal fun RichTextState.isActive(action: EditorAction): Boolean = when (action) {
    EditorAction.Bold        -> currentSpanStyle.fontWeight?.let { it >= FontWeight.Bold } ?: false
    EditorAction.Italic       -> currentSpanStyle.fontStyle == FontStyle.Italic
    EditorAction.Underline   -> currentSpanStyle.textDecoration?.contains(TextDecoration.Underline) ?: false
    EditorAction.Strike       -> currentSpanStyle.textDecoration?.contains(TextDecoration.LineThrough) ?: false
    EditorAction.Code         -> isCodeSpan
    EditorAction.H1           -> currentHeadingStyle == HeadingStyle.H1
    EditorAction.H2           -> currentHeadingStyle == HeadingStyle.H2
    EditorAction.H3           -> currentHeadingStyle == HeadingStyle.H3
    EditorAction.Bullet       -> isUnorderedList
    EditorAction.Ordered      -> isOrderedList
    EditorAction.Quote        -> false  // no blockquote state
    EditorAction.ExternalLink  -> isLink
    EditorAction.InternalLink  -> false
    EditorAction.AlignLeft    -> currentParagraphStyle.textAlign == TextAlign.Start || currentParagraphStyle.textAlign == TextAlign.Left
    EditorAction.AlignCenter   -> currentParagraphStyle.textAlign == TextAlign.Center
    EditorAction.AlignRight   -> currentParagraphStyle.textAlign == TextAlign.End
}
```

## Sticky Bottom Toolbar

Placed in `Scaffold.bottomBar` in `NoteEditorScreenContent`:

```kotlin
// NoteEditorScreen.kt
bottomBar = {
    session?.let { editorSession ->
        Column {
            MetaChipsRow(html = editorState.html)
            EditorToolbar(
                richTextState = editorSession.richTextState,
                onHtmlChange = { editorSession.dispatchHtml() },
                onAiClick = onAiClick,
                onLinkClick = { linkDialogVisible = true },           // external URL
                onInternalLinkClick = { internalLinkPickerVisible = true }, // wikilinks
            )
        }
    }
}
```

**Toolbar composable signature:**
```kotlin
@Composable
fun EditorToolbar(
    richTextState: RichTextState,
    onHtmlChange: () -> Unit,
    onAiClick: () -> Unit,
    onLinkClick: () -> Unit,
    onInternalLinkClick: () -> Unit,
    modifier: Modifier = Modifier,
)
```

**Overflow menu** (`DropdownMenu` via `MoreVert` IconButton) contains: H2, H3, Code, AlignLeft, AlignCenter, AlignRight, ExternalLink, InternalLink.

**Undo/Redo** — `richTextState.history.undo()` / `richTextState.history.redo()` wired to dedicated toolbar buttons (no keyboard shortcuts — `RichTextEditor` lacks `onKeyEvent`).

## Internal Link Picker (Obsidian-style Wikilinks)

Triggered by the **InternalLink** overflow button. Opens `InternalLinkPickerSheet`.

**Link insertion format** (stored as `href` attribute):
```
note://{noteId}   → displayed as [[Note Title]]
task://{taskId}   → displayed as [[Task Title]]
```

```kotlin
// In NoteEditorScreenContent:
InternalLinkPickerSheet(
    onNoteSelected = { noteId, title ->
        session?.richTextState?.addLinkToSelection(url = "note://$noteId")
        session?.dispatchHtml()
    },
    onTaskSelected = { taskId, title ->
        session?.richTextState?.addLinkToSelection(url = "task://$taskId")
        session?.dispatchHtml()
    },
    onDismiss = { internalLinkPickerVisible = false },
)
```

**`InternalLinkPickerSheet`** (`core/ui/components/InternalLinkPickerSheet.kt`):
- Material3 `ModalBottomSheet` via `TaskEditorSheetHost`
- `TabRow` with Notes / Tasks tabs
- `OutlinedTextField` with debounced 250ms search
- `LazyColumn` of `ListItem` results
- Queries via `InternalLinkRepository`

**`InternalLinkRepository`** (`feature/search/InternalLinkRepository.kt`):
```kotlin
interface InternalLinkRepository {
    suspend fun searchNotes(userId: UserId, query: String): List<Note>
    suspend fun searchTasks(query: String): List<Task>
    suspend fun getBacklinkNotes(noteId: String): List<Note>
}
```

**DI registration** in `NotesDiModule.kt`:
```kotlin
single<InternalLinkRepository> { InternalLinkRepositoryImpl(get(), get()) }
```

## Wiki-link Round-trip

In `ComposingMarkdownHtmlPort.kt`:

**toHtml** — `[[Title]]` → `<a href="note://URL-encoded-title">Title</a>`:
```kotlin
text.startsWith("[[", i) -> {
    val end = text.indexOf("]]", i + 2)
    if (end != -1) {
        val title = text.substring(i + 2, end)
        val encoded = URLEncoder.encode(title, "UTF-8")
        append("<a href=\"note://").append(encoded).append("\">").append(title).append("</a>")
        i = end + 2
    }
}
```

**toMarkdown** — reverse:
```kotlin
val noteLinkRegex = Regex("""<a href="note://([^"]+)">([^<]+)</a>""")
noteLinkRegex.replace(text) { m ->
    val title = URLDecoder.decode(m.groupValues[1], "UTF-8")
    "[[${m.groupValues[2]}]]"  // preserves display text, not encoded title
}
```

## Backlinks Panel

Opens via the **undo icon** (↩) in the top app bar. Shows notes that link TO the current note.

```kotlin
BacklinksSheet(
    noteId = editorState.id,
    onNoteSelected = { noteId ->
        backlinksSheetVisible = false
        onNavigateToNote(noteId)
    },
    onDismiss = { backlinksSheetVisible = false },
)
```

**Query** (`NoteDao.getBacklinkNotes`):
```sql
SELECT * FROM notes WHERE deleted_at IS NULL AND outgoing_links LIKE '%note://' || :noteId || '%'
```

**Schema**: `NoteEntity.outgoing_links` stores a JSON array of `["note://id1", "task://id2"]`. Updated via `NoteDao.setOutgoingLinks` when the note is saved.

## richeditor-compose API (v1.2.0)

```kotlin
val richState = remember { RichTextState() }

// Formatting
richState.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
richState.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
richState.setHeadingStyle(HeadingStyle.H1)
richState.toggleUnorderedList()
richState.toggleOrderedList()
richState.toggleCodeSpan()
richState.toggleParagraphStyle(ParagraphStyle(textAlign = TextAlign.Center))
richState.addLinkToSelection(url = "https://example.com")  // selected text becomes link

// Query state
richState.currentSpanStyle.fontWeight >= FontWeight.Bold  // isBold
richState.isCodeSpan
richState.isUnorderedList
richState.isOrderedList
richState.isLink
richState.currentParagraphStyle.textAlign == TextAlign.Center
richState.history.undo()
richState.history.redo()

// Serialization
richState.toHtml()
richState.setHtml(html)

// ⚠️ No blockquote support (no toggleBlockquote, no isBlockquote)
// ⚠️ No onKeyEvent — keyboard shortcuts not available on RichTextEditor
```

## Link Tap Navigation

`RichTextEditor` has no `onLinkClick`. The `BasicRichText` (read-only) handles link taps internally via `detectTapGestures` + `getLinkByOffset` (internal API).

For editable editor: tap handling is planned but not yet wired. The link URLs (`note://id`, `task://id`) are stored correctly; navigation requires wrapping `RichTextEditor` with a `pointerInput` overlay (future work).

## Limitations

- **Quote/blockquote**: `richeditor-compose 1.2.0` has no `toggleBlockquote()` or `isBlockquote` — `EditorAction.Quote` is a no-op
- **Tables/Images**: HTML↔Markdown round-trip is lossy
- **Keyboard shortcuts**: `RichTextEditor` lacks `onKeyEvent` — undo/redo buttons are used instead
- **Link tap navigation**: not yet wired (requires `pointerInput` overlay on `RichTextEditor`)
- **Backlinks for tasks**: only notes supported; `outgoing_links` JSON column tracks both `note://` and `task://` URLs

## Files Reference

| File | Purpose |
|---|---|
| `NoteEditorScreen.kt` | Screen, EditorSession, EditorTitleAndBody, LinkUrlDialog, BacklinksSheet |
| `EditorAction.kt` | Sealed interface (Bold, Italic, H1-H3, Bullet, Ordered, Quote, Align*, ExternalLink, InternalLink) |
| `EditorToolbar.kt` | Sticky bottom toolbar; pure `apply()`/`isActive()` extensions |
| `InternalLinkPickerSheet.kt` | Notes/Tasks tab picker bottom sheet |
| `InternalLinkRepository.kt` | Interface |
| `InternalLinkRepositoryImpl.kt` | DAO-based implementation |
| `ComposingMarkdownHtmlPort.kt` | toHtml/toMarkdown + wikilink round-trip |
| `Ids.kt` | NoteId, Note, NoteColor value classes |
| `NotesDiModule.kt` | DI: `single<InternalLinkRepository> { InternalLinkRepositoryImpl(...) }` |

## MarkdownHtmlPort for CreateNoteTool (CLI/MCP)

When creating a note via `CreateNoteTool` (from the MCP server or CLI), the input is plain markdown. It must be converted to HTML before storage in `NoteEntity.bodyHtml`.

The `MarkdownHtmlPort` interface handles this:

```kotlin
// shared/src/commonMain/.../feature/notes/MarkdownHtmlPort.kt
interface MarkdownHtmlPort {
    suspend fun toHtml(markdown: String): String
    suspend fun toMarkdown(html: String): String
}
```

**Implementation** in `ComposingMarkdownHtmlPort.kt` handles wikilinks and formatting:

```kotlin
class ComposingMarkdownHtmlPort : MarkdownHtmlPort {
    override suspend fun toHtml(markdown: String): String {
        // [[Title]] → <a href="note://URL-encoded-title">Title</a>
        // **bold**, *italic*, etc. → HTML equivalents
        return markdownToHtml(markdown)
    }
}
```

**CreateNoteTool usage:**

```kotlin
class CreateNoteTool(
    private val notesRepo: NotesRepository,
    private val markdownHtmlPort: MarkdownHtmlPort,
    private val currentUser: CurrentUser,
) : SimpleTool<CreateNoteInput>(...) {

    override suspend fun execute(args: CreateNoteInput): String {
        // Convert markdown → HTML (wikilinks, bold, italic, etc.)
        val html = markdownHtmlPort.toHtml(args.bodyMarkdown)

        val noteId = NoteId.fromString(UUID.randomUUID().toString())
        notesRepo.createWithContent(
            userId = currentUser.userId,
            id = noteId,
            title = args.title,
            bodyMarkdown = args.bodyMarkdown,
            bodyHtml = html,
        ).getOrThrow()

        return CreateNoteOutput(noteId = noteId.value).toJson()
    }
}
```

**Wikilink round-trip:**
- `[[Note Title]]` → stored as `<a href="note://Note%20Title">Note Title</a>` in bodyHtml
- `toMarkdown()` reverses: regex extracts `href` and reconstructs `[[display text]]`
- Both CLI and UI use the same `toHtml()` — wikilinks look identical everywhere

**DI registration** (same as existing):
```kotlin
// NotesDiModule.kt or AiToolsDiModule.kt
factory<MarkdownHtmlPort> { ComposingMarkdownHtmlPort() }
```

## Limitations

- **Quote/blockquote**: `richeditor-compose 1.2.0` has no `toggleBlockquote()` or `isBlockquote` — `EditorAction.Quote` is a no-op
- **Tables/Images**: HTML↔Markdown round-trip is lossy
- **Keyboard shortcuts**: `RichTextEditor` lacks `onKeyEvent` — undo/redo buttons are used instead
- **Link tap navigation**: not yet wired (requires `pointerInput` overlay on `RichTextEditor`)
- **Backlinks for tasks**: only notes supported; `outgoing_links` JSON column tracks both `note://` and `task://` URLs

## Files Reference

| File | Purpose |
|---|---|
| `NoteEditorScreen.kt` | Screen, EditorSession, EditorTitleAndBody, LinkUrlDialog, BacklinksSheet |
| `EditorAction.kt` | Sealed interface (Bold, Italic, H1-H3, Bullet, Ordered, Quote, Align*, ExternalLink, InternalLink) |
| `EditorToolbar.kt` | Sticky bottom toolbar; pure `apply()`/`isActive()` extensions |
| `InternalLinkPickerSheet.kt` | Notes/Tasks tab picker bottom sheet |
| `InternalLinkRepository.kt` | Interface |
| `InternalLinkRepositoryImpl.kt` | DAO-based implementation |
| `ComposingMarkdownHtmlPort.kt` | toHtml/toMarkdown + wikilink round-trip |
| `MarkdownHtmlPort.kt` | Fun interface: toHtml / toMarkdown |
| `Ids.kt` | NoteId, Note, NoteColor value classes |
| `NotesDiModule.kt` | DI: `single<InternalLinkRepository> { InternalLinkRepositoryImpl(...) }` |

**Deleted (dead code):**
- `ToolbarState.kt` — DSL builder, never referenced
- `EditorBody.kt` — replaced by `EditorTitleAndBody` in `NoteEditorScreen.kt`
