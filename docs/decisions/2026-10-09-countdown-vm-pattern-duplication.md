---
title: "Countdown State Machine Pattern — Duplication Across VMs"
date: 2026-10-09
status: accepted
---

# ADR: Countdown State Machine Pattern — Duplication Across VMs

## Context

During #80 (countdown progress for undo snackbar) and #104 (tags soft-delete + undo), the same countdown pattern was implemented in two ViewModels:

- `AgendaViewModel` — undo countdown for task delete
- `TagsViewModel` — undo countdown for tag delete

Both implement an identical 5-second `UNDO_WINDOW_MS` with:

1. A `MutableStateFlow<Float?>` emitting progress `1f → 0f` every 100ms
2. A timer coroutine that cancels on successful undo or window expiry
3. Clearing the progress StateFlow on both outcomes

```kotlin
// AgendaViewModel
private val _countdownProgress = MutableStateFlow<Float?>(null)
val countdownProgress = _countdownProgress.asStateFlow()
private var pendingDeleteJob: Job? = null

pendingDeleteJob = scope.launch {
    _countdownProgress.value = 1f
    val totalTicks = (UNDO_WINDOW_MS / 100).toInt()
    var tick = 0
    while (tick < totalTicks) {
        delay(100)
        tick++
        _countdownProgress.value = 1f - (tick.toFloat() / totalTicks)
    }
    if (undoSlot.load() == generation) {
        _pendingDelete.value = null
        _countdownProgress.value = null
    }
}
```

The same 15-line timer block appears verbatim in `TagsViewModel`.

## Decision

**Extract into a reusable `CountdownStateMachine`** — a thin utility class that owns the timer job and countdown StateFlow, accepting callbacks for expiration.

```kotlin
class CountdownStateMachine(
    private val scope: CoroutineScope,
    private val windowMs: Long = 5_000L,
    private val onExpired: () -> Unit,
) {
    private val _progress = MutableStateFlow<Float?>(null)
    val progress: StateFlow<Float?> = _progress.asStateFlow()

    private var job: Job? = null

    fun start() { ... }
    fun cancel() { job?.cancel(); _progress.value = null }
    fun isActive(): Boolean = job?.isActive == true
}
```

## Rationale

- 15 lines of near-identical code in 2 VMs now
- Next undo feature (e.g. Projects, Notes) will copy-paste again
- The timer loop (`tick++` per `delay(100)`) is the correct pattern for `runTest` virtual time
- Hardcoding `System.currentTimeMillis()` was the original bug — a shared utility would prevent it

## Consequences

- All VMs with undo snackbars (`AgendaViewModel`, `TagsViewModel`, future `NotesViewModel`, `ProjectsViewModel`) use the shared utility
- Single place to fix if the tick-counting pattern needs adjustment
- Reduces test surface: one class to verify, not N copies
