# Test recipe for VM events

## Test recipe for VM events

```kotlin
@Test
fun `saveNow emits NavigateBack on success`() = runTest {
    val vm = createVm(repo = FakeNotesRepository().apply { seed(note) })
    vm.saveNow()
    advanceUntilIdle()
    val event = vm.events.first()
    assertIs<NotesUiEvent.NavigateBack>(event)
}

@Test
fun `scheduleAutosave emits SavedPulse on success`() = runTest {
    val vm = createVm(repo = FakeNotesRepository().apply { seed(note) })
    vm.editTitle("n1", "Updated title")
    // simulate autosave completion
    val pulse = vm.savedPulse.first()
    assertIs<Unit>(pulse)  // SavedPulse is a data object
}
```
