---
name: singularity-todo-rich-editor
description: Rich-text (WYSIWYG) editor pattern for Kotlin Multiplatform notes using com.mohamedrejeb.richeditor:richeditor-compose 1.2.0. Use when adding or modifying the notes editor, markdown toolbar, EditorAction sealed interface, EditorToolbar, sticky bottom toolbar pattern, EditorSession hoisting, wikilink insertion, wikilink extraction from HTML, or markdown-to-HTML serialization via RichTextState.
---

# Skill: Rich-Text Editor for Notes (WYSIWYG)

## When to Use This Skill

Use when implementing or modifying the rich-text editor in `feature/notes/`. This skill covers:
- Toolbar design (sticky bottom via `Scaffold.bottomBar`, overflow menu)
- `EditorAction` sealed interface and `RichTextState.apply()`/`isActive()` extensions
- `EditorSession` — the hoisting pattern for sharing `RichTextState` between toolbar and body
- Internal link picker (Obsidian-style `[[Note Title]]` / `[[Task Title]]`)
- External URL link dialog (`LinkUrlDialog`)
- Wikilink insertion via `addLinkToSelection`
- Wikilink extraction via `extractOutgoingLinks` (HTML regex)
- `RichTextState.toMarkdown()` / `setHtml()` for markdown round-trip (built-in, no port needed)
- Debounced autosave with `AutosaveScheduler`

## Architecture Overview

```
feature/notes/
├── NoteEditorScreen.kt          # Screen composable, EditorSession, EditorTitleAndBody
├── NoteEditor.kt                # NoteEditor VM: editor state, autosave, persist()
├── NotePreview.kt               # NotePreview VM: read-only view
├── NotePreviewScreen.kt         # Read-only screen: RichText + backlinks + BottomAppBar
├── NotesListViewModel.kt         # List VM: filter, sort, multi-select, quick-add
├── NotesScreen.kt               # List screen with QuickAddRow + NotesEmptyState
├── EditorSession.kt            # Top-level class: RichTextState + title + dispatch
├── EditorAction.kt             # sealed interface for toolbar actions
├── Ids.kt                      # NoteId, Note, NoteColor, LinkResult, LinkKind
├── LinkResult.kt               # Generic link picker result (LinkRef sealed interface)
├── OutgoingLinksExtractor.kt   # extractOutgoingLinks(html): List<LinkRef> via HTML regex
├── NoteFormatters.kt           # extractPreviewText, formatNoteAiResult
├── NotesUiEvent.kt             # Per-feature sealed UiEvent
├── NotesDiModule.kt            # DI: 3 VMs + 2 repositories
└── components/
    ├── EditorToolbar.kt        # Sticky bottom toolbar with overflow menu
    └── InternalLinkPickerSheet.kt  # Generic merged Notes+Tasks picker

core/ui/components/
└── (no notes-specific components here — see rule below)
```

## Rule: `core/` Must Not Import Feature Types

`core/ui/components/` is shared infrastructure. It must **never** import from `feature/notes/` or `feature/tasks/`. If a component needs feature-specific data, pass a generic adapter (e.g. `LinkResult`) from the feature layer.

## EditorSession Hoisting Pattern

`RichTextState` has no stable equality (uses internal `MutableState`), so it **must** be created via `remember` and stored at the screen level.

```kotlin
// EditorSession.kt (top-level class)

class EditorSession(
    val richTextState: RichTextState,
    var titleFieldValue: String,
    private var lastDispatchedHtml: String,
    private var firstLoadSkipped: Boolean,
    private val onBodyChange: (id: String, html: String) -> Unit,
    private val id: String,
) {
    private val insertedLinks = mutableListOf<Pair<IntRange, String>>()

    fun recordLink(url: String) {
        val sel = richTextState.selection
        insertedLinks.add(sel.min..<sel.max to url)
    }

    fun dispatchHtml() {
        val html = richTextState.toHtml()
        if (html != lastDispatchedHtml) {
            lastDispatchedHtml = html
            onBodyChange(id, html)
        }
    }

    fun notifyFirstLoadSkipped() { firstLoadSkipped = true }
    val isFirstLoadSkipped: Boolean get() = firstLoadSkipped
}

@Composable
fun rememberEditorSession(
    state: EditorState.Editing,
    onBodyChange: (id: String, html: String) -> Unit,
): EditorSession {
    val richTextState = remember(state.id) {
        RichTextState().also { it.setHtml(state.html) }
    }
    val session = remember(state.id) {
        EditorSession(richTextState, state.title, state.html, false, onBodyChange, state.id)
    }
    LaunchedEffect(state.title) { session.titleFieldValue = state.title }
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
- `LaunchedEffect` over `richTextState` dispatches HTML on every keystroke

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
```

## Internal Link Picker — Generic Merged (PR #3)

**`LinkResult`** (`feature/notes/LinkResult.kt`):
```kotlin
data class LinkResult(val id: String, val title: String, val kind: LinkKind)
enum class LinkKind { Note, Task }
```

**Generic sheet** (`feature/notes/components/InternalLinkPickerSheet.kt`):
```kotlin
@Composable
fun InternalLinkPickerSheet(
    queryFlow: MutableStateFlow<String>,
    onSearch: suspend (String) -> List<LinkResult>,  // caller merges Notes + Tasks
    onSelected: (LinkResult) -> Unit,
    onDismiss: () -> Unit,
)
```

**NoteEditorScreen wiring:**
```kotlin
val linkQueryFlow = remember { MutableStateFlow("") }
InternalLinkPickerSheet(
    queryFlow = linkQueryFlow,
    onSearch = { q ->
        val notes = linkRepo.searchNotes(currentUser.scopedUserId.value, q)
            .map { LinkResult(it.id.value, it.title, LinkKind.Note) }
        val tasks = linkRepo.searchTasks(q)
            .map { LinkResult(it.id.value, it.title, LinkKind.Task) }
        notes + tasks  // merged
    },
    onSelected = { result ->
        val url = when (result.kind) {
            LinkKind.Note -> "note://${result.id}"
            LinkKind.Task -> "task://${result.id}"
        }
        session?.richTextState?.addLinkToSelection(url = url)
        session?.recordLink(url)
        session?.dispatchHtml()
        linkQueryFlow.value = ""
    },
    onDismiss = { internalLinkPickerVisible = false; linkQueryFlow.value = "" },
)
```

## Wikilink Extraction from HTML (PR #2)

`RichTextState` paragraph tree (`RichParagraph.children`) is `internal`. Walking it from outside the library is impossible. Instead, parse the HTML output.

**`OutgoingLinksExtractor.kt`** (pure function):
```kotlin
internal fun extractOutgoingLinks(html: String): List<LinkRef> {
    val seen = mutableSetOf<LinkRef>()
    val regex = Regex("""<a\s[^>]*href="(note://[^"]+)""")
    for (match in regex.findAll(html)) {
        val url = match.groupValues[1]
        when {
            url.startsWith("note://") -> seen.add(LinkRef.Note(...))
            url.startsWith("task://") -> seen.add(LinkRef.Task(...))
        }
    }
    return seen.toList()
}

sealed interface LinkRef {
    data class Note(val noteId: String) : LinkRef
    data class Task(val taskId: String) : LinkRef
}
```

**Wired in `NoteEditor.persist()`** (called on every save):
```kotlin
private suspend fun persist(html: String, title: String, id: String, navigateBack: Boolean) {
    repo.updateContent(...).getOrThrow()
    val outgoingLinks = extractOutgoingLinks(html).map { link ->
        when (link) {
            is LinkRef.Note -> "note://${link.noteId}"
            is LinkRef.Task -> "task://${link.taskId}"
        }
    }
    repo.setOutgoingLinks(NoteId.fromString(id), outgoingLinks).getOrThrow()
    // ...
}
```

## Markdown Round-trip (Built-in, No Port)

`RichTextState` from `richeditor-compose:1.2.0` has built-in markdown support:

```kotlin
// Serialize (editor → markdown for CLI/MCP tools)
val markdown = RichTextState().apply { setHtml(html) }.toMarkdown()

// Deserialize (legacy note: markdown → HTML for editor)
val html = RichTextState().apply { setMarkdown(markdown) }.toHtml()
```

**`MarkdownHtmlPort` interface and `ComposingMarkdownHtmlPort` are deleted** — the library handles this natively.

For `CreateNoteTool` (MCP/CLI): use `RichTextState().apply { setMarkdown(args.bodyMarkdown) }.toHtml()` inline.

## NotePreview — Read-only View (PR #1)

Uses `RichText(state)` composable (NOT `RichTextEditor`) for read-only display.

```kotlin
NotePreviewScreen.kt:
- Scaffold + TopAppBar: back, backlinks button, overflow
- Hero: large title, relative timestamp, wordCount chip
- Body: RichText(state = richTextState)  // read-only
- BottomAppBar: Edit | Backlinks(count) | Delete
- BacklinksSheet: ModalBottomSheet with linking notes
```

`NotePreview` VM observes via `repo.watchNote()`, fetches backlinks via `linkRepo.getBacklinkNotes()`.

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
richState.addLinkToSelection(url = "https://example.com")  // inserts link span

// Query state
richState.currentSpanStyle.fontWeight >= FontWeight.Bold  // isBold
richState.isCodeSpan
richState.isUnorderedList
richState.isOrderedList
richState.isLink
richState.history.undo()
richState.history.redo()

// Serialization — BUILT-IN (no port needed)
richState.toMarkdown()    // HTML → Markdown
richState.setMarkdown(md) // Markdown → internal state
richState.toHtml()        // internal state → HTML
richState.setHtml(html)   // HTML → internal state
```

**`RichSpanStyle.Link`** — serializes as `<a href="url">`:
```kotlin
// Link URL schemes used:
note://{noteId}
task://{taskId}
```

## Link Tap Navigation

`RichText` (read-only, used in NotePreview) handles link taps internally via `detectTapGestures` + `getLinkByOffset` (internal API).

For the editable `RichTextEditor`: `addLinkToSelection(url)` inserts the link span correctly. Tapping links in the editor for navigation is future work.

## Limitations

- **Quote/blockquote**: `richeditor-compose 1.2.0` has no `toggleBlockquote()` or `isBlockquote` — `EditorAction.Quote` is a no-op
- **Tables/Images**: HTML↔Markdown round-trip is lossy
- **Keyboard shortcuts**: `RichTextEditor` lacks `onKeyEvent` — undo/redo buttons are used instead
- **RichParagraph/RichSpan tree**: `internal` — wikilink extraction uses HTML regex

## Files Reference

| File | Purpose |
|---|---|
| `NoteEditorScreen.kt` | Screen, EditorSession, EditorTitleAndBody, LinkUrlDialog |
| `NoteEditor.kt` | NoteEditor VM: editorState, autosave, persist() |
| `NotePreviewScreen.kt` | Read-only view: RichText + BottomAppBar + BacklinksSheet |
| `NotePreview.kt` | NotePreview VM: observes note + backlinks |
| `NotesScreen.kt` | List screen: QuickAddRow + NotesEmptyState + multi-select toolbar |
| `NotesListViewModel.kt` | List VM: filter, sort, multi-select, quick-add, createNoteWithTitle |
| `EditorSession.kt` | Top-level class: RichTextState + title + dispatch |
| `EditorAction.kt` | Sealed interface (Bold, Italic, H1-H3, Bullet, Ordered, Quote, Align*, ExternalLink, InternalLink) |
| `EditorToolbar.kt` | Sticky bottom toolbar; pure `apply()`/`isActive()` extensions |
| `InternalLinkPickerSheet.kt` | Generic merged Notes+Tasks picker |
| `LinkResult.kt` | `LinkResult` data class + `LinkKind` enum |
| `OutgoingLinksExtractor.kt` | `extractOutgoingLinks(html)` + `LinkRef` sealed interface |
| `NoteFormatters.kt` | `extractPreviewText`, `formatNoteAiResult` |
| `Ids.kt` | NoteId, Note, NoteColor |
| `NotesDiModule.kt` | DI: 3 VMs + NotesRepository + InternalLinkRepository |
