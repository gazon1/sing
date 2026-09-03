---
name: singularity-todo-rich-editor
description: Rich-text (WYSIWYG) editor pattern for Kotlin Multiplatform notes using com.mohamedrejeb.richeditor:richeditor-compose 1.2.0. Use when adding or modifying the notes editor, markdown toolbar, EditorAction value class, ToolbarState DSL builder, or NotesViewModel dual-scope coroutine strategy. Covers RichTextState, SpanStyle, HeadingStyle, and debounced autosave.
---

# Skill: Rich-Text Editor for Notes (WYSIWYG)

## When to Use This Skill

Use when implementing a rich-text (WYSIWYG) editor in a Kotlin Multiplatform project using `com.mohamedrejeb.richeditor:richeditor-compose`. This skill captures the architecture patterns established in the Singularity TODO app's Notes feature.

## Prerequisites

- Kotlin Multiplatform project with JVM/Android targets
- Koin for dependency injection
- Room (or compatible database) for persistence
- `androidx.lifecycle.ViewModel` for the screen's state holder

## Architecture Overview

```
feature/notes/
├── NotesStore.kt          # Interface: list + editor CRUD (testable, mock-free)
├── MarkdownHtmlPort.kt    # Fun interface: markdown ↔ HTML conversion
├── NotesViewModel.kt      # Single VM: list + editor state + debounced autosave
├── EditorAction.kt       # @JvmInline value class for toolbar actions
├── ToolbarState.kt        # DSL builder for toolbar state
└── NoteEditorScreen.kt   # Composable: RichTextEditor + toolbar
```

## Key Patterns

### 1. Single Store Interface (Mock-Free Testing)

One interface covers both list and editor operations. Tests use a `FakeNotesStore` (in-memory map).

```kotlin
interface NotesStore {
    fun watchAll(userId: UserId): Flow<List<Note>>
    fun watch(id: String): Flow<Note?>
    suspend fun create(userId: UserId, id: NoteId, title: String, bodyMarkdown: String): String
    suspend fun update(id: String, title: String, bodyMarkdown: String)
    suspend fun softDelete(id: String)
}

class FakeNotesStore : NotesStore {
    private val notes = mutableMapOf<String, Note>()
    override fun watchAll(userId: UserId): Flow<List<Note>> = flowOf(...)
    // ...
}
```

### 2. MarkdownHtmlPort as Fun Interface

A SAM interface for markdown↔HTML conversion enables pass-through fakes in tests.

```kotlin
fun interface MarkdownHtmlPort {
    fun toHtml(markdown: String): String
    fun toMarkdown(html: String): String
}

class FakeMarkdownHtmlPort : MarkdownHtmlPort {
    override fun toHtml(md: String) = "<p>$md</p>"
    override fun toMarkdown(html: String) = html.trim().removePrefix("<p>").removeSuffix("</p>")
}
```

### 3. EditorAction as @JvmInline Value Class

Toolbar actions are type-safe, zero-overhead value classes.

```kotlin
@JvmInline
value class EditorAction private constructor(val tag: String) {
    companion object {
        val Bold = EditorAction("bold")
        val Italic = EditorAction("italic")
        val H1 = EditorAction("h1")
        val Bullet = EditorAction("bullet")
        // ...
    }
}
```

### 4. ToolbarState with DSL Builder

```kotlin
data class ToolbarState(
    val enabled: Set<EditorAction>,
    val active: Set<EditorAction>
) {
    class Builder {
        private val enabled = mutableSetOf<EditorAction>()
        fun enable(vararg actions: EditorAction) { enabled += actions }
        fun disable(vararg actions: EditorAction) { enabled -= actions }
        fun build() = ToolbarState(enabled.toSet(), activeOf())
    }
}

fun toolbarState(block: ToolbarState.Builder.() -> Unit) = ToolbarState.Builder().apply(block).build()
```

### 5. Single ViewModel with Sealed Editor State

One ViewModel manages both the list and the editor. Sealed interface for editor states enables exhaustive `when`.

```kotlin
sealed interface EditorState {
    data object Empty : EditorState
    data class Editing(val id: String, val title: String, val html: String, val isDirty: Boolean) : EditorState
    data class Saving(val id: String) : EditorState
    data class Error(val id: String, val message: String) : EditorState
}

class NotesViewModel(
    private val store: NotesStore,
    private val htmlPort: MarkdownHtmlPort,
    settingsRepository: SettingsRepository
) : ViewModel() {
    // List state
    val state: StateFlow<NotesUiState> = ...
    // Editor state
    val editorState: StateFlow<EditorState> = ...

    // Debounced autosave
    private var autosaveJob: Job? = null
    private fun scheduleAutosave(id: String) {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(500)
            store.update(id, current.title, htmlPort.toMarkdown(current.html))
        }
    }
}
```

### 6. richeditor-compose API (v1.2.0)

```kotlin
val richState = remember { RichTextState() }

// Formatting
richState.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
richState.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
richState.setHeadingStyle(HeadingStyle.H1)
richState.toggleUnorderedList()

// Query state
richState.currentSpanStyle.fontWeight == FontWeight.Bold  // isBold
richState.currentHeadingStyle == HeadingStyle.H1           // is H1
richState.isUnorderedList                                // in bullet list
```

### 7. Dispatcher Strategy for Tests

The `scope` uses `Dispatchers.Unconfined` via a lazy property, so flows run synchronously in tests without needing virtual time advancement.

```kotlin
open class NotesViewModel(...) : ViewModel() {
    private val scope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }
    // Fire-and-forget (create, delete, autosave) uses viewModelScope
}
```

## Files to Create/Modify

| Action | Files |
|--------|-------|
| New | `NotesStore.kt`, `MarkdownHtmlPort.kt`, `EditorAction.kt`, `ToolbarState.kt`, `NoteEditorScreen.kt` |
| Modify | `NotesViewModel.kt` (extend with EditorState), `AppModule.kt` (wire DI), `Navigation.kt` (add editor route) |
| Tests | `NotesViewModelTest.kt`, `NotesEditorFlowTest.kt`, `MarkdownHtmlPortTest.kt`, `TestFakes.kt` |

## DI Wiring (Koin)

```kotlin
// AppModule.kt
single<MarkdownHtmlPort> { RichEditorMarkdownHtmlPort() }
single<NotesStore> { RoomNotesStore(get(), get()) }
factory { NotesViewModel(get(), get(), get()) }
```

## Limitations

- **Quote/blockquote**: `richeditor-compose` does not support `ParagraphStyle.Quote`. Removed from toolbar.
- **Tables/Images**: HTML→Markdown round-trip is lossy for complex structures.
- **Desktop Compose**: Use `RichTextEditorDefaults` + manual theming testing.
