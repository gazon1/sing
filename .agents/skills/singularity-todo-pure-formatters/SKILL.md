---
name: singularity-todo-pure-formatters
description: How to extract user-facing string formatting out of Composables and into pure-Kotlin helpers that can be unit-tested without a Compose runtime. Use when a Composable contains a `when` block that maps a sealed domain result to a user-facing string, when icon-name → ImageVector or priority-enum → Color mapping is duplicated across screens, when an AI-result formatter is tested via Robolectric or by rendering a screen, or when `RichTextState.toggleSpanStyle(...)` calls live inline in a toolbar button. Documents the `*Formatters.kt` convention, `internal` visibility, and the trade-off between `internal fun` (pure helper) and `@Composable @ReadOnlyComposable` (helper that reads `MaterialTheme`).
---

# Pure Formatters — Format Outside Composables

Every screen in this project that displays an AI / domain result has the same shape: a `when (result) { is X -> "..."; is Y -> "..."; }` that turns a sealed domain result into a string the user sees. Before extraction, those branches live inside a `Composable`, which makes them un-testable without launching a Compose runtime.

The convention is: **format in pure Kotlin, render in Compose**.

## The pattern

**Step 1 — Extract into `feature/<feature>/<Feature>Formatters.kt`:**

```kotlin
package com.singularity.todo.feature.tasks

internal fun formatAiResult(result: AiActionResult): String = when (result) {
    is AiActionResult.RefineTitle -> "Refined title: ${result.newTitle}"
    is AiActionResult.GenerateDescription -> "Description: ${result.description}"
    is AiActionResult.GenerateChecklist -> "Checklist:\n" + result.steps.joinToString("\n") { "- $it" }
    is AiActionResult.DecomposeTask -> "Sub-tasks:\n" + result.subTasks.joinToString("\n") { "- $it" }
    is AiActionResult.PickTime -> "Suggested time: ${result.suggestedTime}"
    is AiActionResult.Error -> "Error: ${result.message}"
}
```

**Step 2 — Test from `shared/src/commonTest/`:**

```kotlin
class TasksFormattersTest {
    @Test fun `refine title formats with prefix`() {
        assertEquals("Refined title: Buy milk", formatAiResult(AiActionResult.RefineTitle("Buy milk")))
    }
    @Test fun `generate checklist formats as bulleted list`() {
        val r = formatAiResult(AiActionResult.GenerateChecklist(listOf("a", "b")))
        assertEquals("Checklist:\n- a\n- b", r)
    }
    // … one test per branch, plus edge cases
}
```

**Step 3 — Call from the ViewModel, never from the Composable:**

```kotlin
fun refineTask(task: Task) = viewModelScope.launch {
    useCase(task).fold(
        onSuccess = { _events.emit(UiEvent.ShowDialog("AI Result", formatAiResult(it))) },
        onFailure = { _events.emit(UiEvent.ShowError(it.message ?: "Failed")) },
    )
}
```

The Composable then only renders `ResultDialog(title, text, onDismiss)`. It has no idea what an `AiActionResult.RefineTitle` is.

## Why `internal` not `private`

`private` would scope the formatter to the file (which would force you to inline it in the VM). `internal` scopes it to the KMP compilation unit — visible to the test in the same package (`shared/src/commonTest/.../TasksFormattersTest.kt`) without exposing it to consumers in other modules. The test goes in the **same package** (`com.singularity.todo.feature.tasks`), not under a `…/test/` mirror.

## When to extract

Extract any of these from Composable code into a pure formatter:

| Pattern in Composable | Extract to |
|---|---|
| `when (aiResult) { is RefineTitle -> "Refined title: $title"; … }` | `<Feature>Formatters.kt :: formatAiResult()` |
| `when (priority) { Low -> Color(0xFF4CAF50); Medium -> Color(0xFFFF9800); … }` | `core/ui/components/Formatters.kt :: priorityColorByIndex()` |
| `when (tone) { Positive -> Color(0xFF2E7D32); Warning -> Color(0xFFCC7700); … }` | `feature/genui/render/material3/atoms/TextRenderer.kt :: toneColor()` |
| `when (action) { Bold -> toggleSpanStyle(SpanStyle(fontWeight = Bold)); … }` | `feature/notes/components/EditorToolbar.kt :: RichTextState.apply(action)` |
| `when (headingLevel) { 1 -> headlineLarge; 2 -> headlineMedium; … }` | `Material3Catalog/atoms/HeadingRenderer.kt :: headingStyle()` |

## Three flavours of formatter

**1. Pure function, no Compose types** — easiest to test, no setup:

```kotlin
internal fun formatAiResult(result: AiActionResult): String = when (result) { … }
```

Test: `assertEquals(expected, formatAiResult(input))`.

**2. Returns a Compose `Color`** — still pure, but you compare with `Color.toArgb()` not `==`:

```kotlin
internal fun priorityColor(priority: TaskPriority): Color = when (priority) {
    TaskPriority.Low -> Color(0xFF4CAF50)
    TaskPriority.Medium -> Color(0xFFFF9800)
    …
}
```

Test:

```kotlin
@Test fun `Low priority maps to green`() {
    assertEquals(0xFF4CAF50.toInt(), priorityColor(TaskPriority.Low).toArgb())
}
```

Note: `Color.value` is a `ULong` packed with alpha, so `==` on raw `Color` values does not match what you write in code. Always use `toArgb()`.

**3. Reads `MaterialTheme` — must be `@Composable @ReadOnlyComposable`:**

```kotlin
@Composable
@ReadOnlyComposable
internal fun headingStyle(level: Int) = when (level) {
    1 -> MaterialTheme.typography.headlineLarge
    2 -> MaterialTheme.typography.headlineMedium
    …
}
```

Test: hard to do without Compose runtime — usually you test that the **inputs produce stable outputs at the same theme** (snapshot test) or just verify the function exists with the right signature. For most cases, prefer flavour (1) and pass the typography as a parameter:

```kotlin
internal fun headingStyleFor(typography: Typography, level: Int) = when (level) {
    1 -> typography.headlineLarge
    …
}
```

Then test:

```kotlin
@Test fun `level 1 maps to headlineLarge`() {
    val typography = Typography()  // default theme
    assertSame(typography.headlineLarge, headingStyleFor(typography, 1))
}
```

## Anti-patterns

- **`Text(result.toString())` for an AI result** — the user sees Java's `AiActionResult$RefineTitle@1abc…`. Use a formatter.
- **`when (result)` inside a Composable** — Composable should render already-formatted strings, not decide how to format them.
- **`formatXxx` as `private fun`** — invisible to tests. Always `internal fun` (or `internal @Composable` for the rare cases that read theme).
- **A formatter that takes a `Context`** — that means it's not pure and can't be tested from commonTest. Inject strings (e.g. `errorPrefix: String = "Error: "`) and provide them via DI if they need to be localised.
- **A formatter with a side-effect** (writing to log, incrementing a metric) — split it into a pure formatter and a side-effecting wrapper.

## Where to put it

| Formatter scope | Location |
|---|---|
| Used by one feature, knows about that feature's sealed types | `feature/<feature>/<Feature>Formatters.kt` |
| Used by multiple features (e.g. priority → Color) | `core/ui/components/Formatters.kt` |
| Used by a single Composable helper (e.g. text tone) | Co-located with the helper in its package |
| Reads `MaterialTheme` | Must be `@Composable @ReadOnlyComposable`, in the same file as the renderer |

## Worked example: `EditorToolbar`

The toolbar's `apply` table used to live inline in the toolbar composable as a long `when` chain:

```kotlin
// Before: in the Composable
IconButton(onClick = {
    when (toolbarAction) {
        ToolbarAction.Bold -> richTextState.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
        ToolbarAction.Italic -> richTextState.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
        // … 6 more branches
    }
    onHtmlChange()
})
```

After:

```kotlin
// components/EditorToolbar.kt — pure helper, internal
internal fun RichTextState.apply(action: EditorAction): RichTextState = when (action.key) {
    "bold" -> apply { toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold)) }
    "italic" -> apply { toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic)) }
    …
}

internal fun RichTextState.isActive(action: EditorAction): Boolean = when (action.key) {
    "bold" -> currentSpanStyle.fontWeight?.let { it >= FontWeight.Bold } ?: false
    …
}

// Composable is now 12 lines
IconButton(onClick = {
    richTextState.apply(toolbarAction)
    onHtmlChange()
})
```

The `apply` helper is independent of `IconButton` — you could test it directly in `RichTextActionsTest`:

```kotlin
@Test fun `Bold action adds bold span style`() {
    val state = RichTextState()
    state.apply(EditorAction.Bold)
    assertTrue(state.currentSpanStyle.fontWeight?.let { it >= FontWeight.Bold } ?: false)
}
```
