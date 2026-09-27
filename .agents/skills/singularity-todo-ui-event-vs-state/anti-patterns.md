# Anti-patterns

## Anti-patterns

**Do NOT use `mutableStateOf` for domain-derived strings:**
```kotlin
// ❌ WRONG — mirrors VM state in Composable
@Composable
fun TasksScreen(...) {
    var aiResultText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        vm.aiResult.collectLatest { result ->
            aiResultText = when (result) {
                is AiActionResult.RefineTitle -> "Refined: ${result.newTitle}"
                // ...
            }
        }
    }
}

// ✅ CORRECT — format in VM, emit via event, render in ResultDialog
// ViewModel:
_events.emit(NotesUiEvent.AiResult(formatAiResult(result)))

// Screen:
CollectEvents(vm.events) { event ->
    if (event is NotesUiEvent.AiResult) dialogText = event.text
}
ResultDialog("AI Result", dialogText) { dialogText = null }
```

**Do NOT use Pulse events for replayable state:**
```kotlin
// ❌ WRONG — savedPulse should not replay; use StateFlow for replayable saves
val lastSaveTime: StateFlow<Instant?> = MutableStateFlow(null)

// ✅ CORRECT — savedPulse is one-shot, extraBufferCapacity = 1
val savedPulse: SharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 1)
```
