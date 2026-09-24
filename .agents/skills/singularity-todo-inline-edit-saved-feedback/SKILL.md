---
name: singularity-todo-inline-edit-saved-feedback
description: Debounced inline-edit pattern for document-style detail screens. Covers MutableStateFlow draft + debounce(300) in ViewModel, silent saves (no Saved events), _lastEditedAt continuous state, formatSavedRelative pure formatter, and the Saved-spam regression found in TaskDetailViewModel.
---

# Inline Edit + Silent Save Feedback

## The Pattern

Document-style detail screens allow users to tap a title or description and edit it inline. Changes must be:
1. **Debounced** — don't save on every keystroke
2. **Silent** — don't spam the user with "Saved" notifications
3. **Feedback-visible** — the user must know their change was saved, subtly

This skill documents the correct implementation.

## The Bug: Saved-Spam

`TaskDetailViewModel.kt:100` had this pattern:
```kotlin
// ❌ WRONG — Saved event emitted on EVERY debounced keystroke
viewModelScope.launch {
    _titleDraft
        .filterNotNull()
        .debounce(300)
        .collect { newTitle ->
            updateTask(current.copy(title = newTitle))
                .onSuccess { _events.emit(TaskDetailUiEvent.Saved("Title updated")) } // SPAM!
        }
}
```

Every letter typed → 300ms debounce → save → `Saved("Title updated")` notification. Typing "Hello" fires 5 notifications.

## The Correct Pattern

### ViewModel side

```kotlin
class ProjectDetailViewModel(
    private val projectRepo: ProjectsRepository,
    private val updateUseCase: UpdateProjectUseCase,
    private val clock: Clock,
) : ViewModel() {

    // 1. Draft state — written by the Composable, read by the collector
    private val _titleDraft = MutableStateFlow<String?>(null)
    val titleDraft: StateFlow<String?> = _titleDraft.asStateFlow()

    private val _descriptionDraft = MutableStateFlow<String?>(null)
    val descriptionDraft: StateFlow<String?> = _descriptionDraft.asStateFlow()

    // 2. Last-edit timestamp — continuous state, formatted by the UI
    private val _lastEditedAt = MutableStateFlow<Instant?>(null)
    val lastEditedAt: StateFlow<Instant?> = _lastEditedAt.asStateFlow()

    init {
        // 3. Debounced collector — SILENT saves
        viewModelScope.launch {
            _titleDraft
                .filterNotNull()
                .debounce(300)
                .collect { newTitle ->
                    val current = state.value.contentOrNull()?.project ?: return@collect
                    silentUpdate(current.copy(name = newTitle))
                }
        }

        viewModelScope.launch {
            _descriptionDraft
                .filterNotNull()
                .debounce(300)
                .collect { newDesc ->
                    val current = state.value.contentOrNull()?.project ?: return@collect
                    silentUpdate(current.copy(description = newDesc))
                }
        }
    }

    private suspend fun silentUpdate(updated: Project) {
        runCatching {
            updateUseCase(updated.id, { it.copy(name = updated.name, description = updated.description) }, clock)
        }.onSuccess {
            _lastEditedAt.value = clock.now() // continuous state update
        }.onFailure { e ->
            _events.emit(ProjectDetailUiEvent.Error(e.message ?: "Update failed"))
        }
    }
}
```

### Composable side

```kotlin
@Composable
fun ProjectHeroSection(
    project: Project,
    titleDraft: String?,
    descriptionDraft: String?,
    lastEditedAt: Instant?,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = clock.now()
    val savedLabel = remember(lastEditedAt, now) {
        formatSavedRelative(now, lastEditedAt)
    }

    Column(modifier = modifier) {
        // Title with inline edit
        BasicTextField(
            value = titleDraft ?: project.name,
            onValueChange = onTitleChange,
            textStyle = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.fillMaxWidth(),
        )

        // Saved indicator (subtle, top-right)
        if (savedLabel.isNotEmpty()) {
            Text(
                text = savedLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End),
            )
        }

        // Description
        BasicTextField(
            value = descriptionDraft ?: (project.description ?: ""),
            onValueChange = onDescriptionChange,
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
```

## The Pure Formatter

```kotlin
// shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/Formatters.kt
// (or in feature-specific Formatters.kt)

private val THREE_SECONDS = 3
private val SIXTY_SECONDS = 60

/**
 * Formats a last-edit timestamp as a relative "Saved X ago" string.
 * Returns empty string if [lastEditedAt] is null.
 *
 * Examples:
 *   null → ""
 *   2s ago → "Saved just now"
 *   45s ago → "Saved 45s ago"
 *   2m ago → "Saved 2m ago"
 *   61m ago → ""  (stale, don't show)
 */
fun formatSavedRelative(now: Instant, lastEditedAt: Instant?): String {
    if (lastEditedAt == null) return ""

    val seconds = Duration.diff(now, lastEditedAt).inWholeSeconds
    return when {
        seconds < THREE_SECONDS -> "Saved just now"
        seconds < SIXTY_SECONDS -> "Saved ${seconds}s ago"
        seconds < 60 * 60 -> {
            val minutes = seconds / 60
            "Saved ${minutes}m ago"
        }
        else -> "" // Don't show for edits older than 1 hour
    }
}
```

This formatter is **pure Kotlin** — testable without Compose:

```kotlin
@Test
fun `formatSavedRelative shows just now for recent edits`() {
    val now = Instant.parse("2024-01-01T12:00:00Z")
    val twoSecondsAgo = now - Duration.seconds(2)
    assertEquals("Saved just now", formatSavedRelative(now, twoSecondsAgo))
}

@Test
fun `formatSavedRelative shows minutes for older edits`() {
    val now = Instant.parse("2024-01-01T12:00:00Z")
    val twoMinutesAgo = now - Duration.minutes(2)
    assertEquals("Saved 2m ago", formatSavedRelative(now, twoMinutesAgo))
}

@Test
fun `formatSavedRelative returns empty for null`() {
    assertEquals("", formatSavedRelative(Instant.now(), null))
}
```

## When to Emit Saved Events

Emit `Saved` events **only** for explicit, non-debounced operations:

```kotlin
// ✅ CORRECT — explicit Save button
fun saveExplicitly() {
    viewModelScope.launch {
        _events.emit(ProjectDetailUiEvent.Saved("Project saved"))
    }
}

// ✅ CORRECT — delete completes
fun deleteProject() {
    viewModelScope.launch {
        deleteUseCase(id).onSuccess {
            _events.emit(ProjectDetailUiEvent.Saved("Project deleted"))
            _events.emit(ProjectDetailUiEvent.NavigateBack)
        }
    }
}

// ❌ WRONG — debounced inline edit
.onSuccess { _events.emit(UiEvent.Saved("Title updated")) } // SPAM!
```

## Why Not Use a "Saving..." Indicator?

TickTick uses a subtle "Saved X ago" pill — no "Saving..." spinner. The rationale:
1. The save is nearly instant (<100ms for local DB)
2. "Saving..." implies the save might fail — it rarely does
3. "Saved X ago" tells the user it **did** save and when

If your save latency is >500ms (network-backed storage), show a brief "Saving..." indicator in the top bar, but switch to "Saved X ago" immediately on success.

## Relationship to Other Skills

| Skill | What it contributes |
|---|---|
| `singularity-todo-document-style-detail` | The 4-section anatomy + ActiveSheet pattern |
| `singularity-todo-ui-event-vs-state` | Continuous `_lastEditedAt` vs one-shot `Saved` event distinction |
| `singularity-todo-pure-formatters` | Pure formatter testability — `formatSavedRelative` is a pure function |
| `singularity-todo-sheet-extraction` | `ActiveSheet` sealed interface, `*SheetsHost` pattern, routing intents for sheet-initiated navigation |
