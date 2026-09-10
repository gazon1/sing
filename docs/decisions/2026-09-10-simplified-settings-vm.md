# Simplified SettingsViewModel — no reactive collection

## Context

Settings screen had a complex reactive pattern: `combine` of 20+ flows from `SettingsRepository` + `stateIn(WhileSubscribed(5000))`. This caused test failures (requires real subscribers or 5000ms virtual time) and made the code hard to reason about.

## Decision

`SettingsViewModel` uses a simple synchronous pattern:

```kotlin
class SettingsViewModel(
    private val contributors: Set<SettingsContributor<*, *>>,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(buildState())
    val state: StateFlow<SettingsUiState> get() = _state

    init { scope.launch { _state.value = buildState() } }

    fun processIntent(intent: SettingsIntent) {
        scope.launch {
            when (intent) {
                is SettingsIntent.Appearance.UpdateDarkTheme -> {
                    settings.setDarkTheme(intent.value)
                    updateState { it.copy(darkTheme = intent.value) }
                }
                // ... other intents
                is SettingsIntent.Ai.TestConnection -> {
                    (aiContributor as? SettingsContributor<...>)?.apply(intent)
                    reloadAiSection()  // reads ephemeral StateFlows from contributor
                }
            }
        }
    }
}
```

**Key rules:**
- State is built once at init from `.value` of `MutableStateFlow` fields in `FakeSettingsRepository` / `DataStoreSettingsRepository`
- Each `processIntent` writes to repository, then updates `_state` synchronously via `updateState { it.copy(...) }`
- No `combine`, no `stateIn`, no reactive collection
- AI ephemeral state (testResult, models, isFetching) comes from `AiSettingsContributor` which exposes `StateFlow` exports (`testResultStateFlow`, `modelsStateFlow`, etc.)
- `SettingsUiState.Content` keeps flat fields for backward compat with sub-screens

## Rationale

- **Testability**: tests call `vm.processIntent(...)` then `vm.state.value` directly — no subscriber needed
- **Simplicity**: no hidden async — developer can read top-to-bottom and understand the flow
- **Predictable restart**: settings take effect after app restart (explicit design goal from user)

## Consequences

- Settings UI is NOT reactive to external changes (other VMs writing to `SettingsRepository`). Acceptable because the settings screen is typically visited once, changed, and closed.
- `AiSettingsContributor` remains as the sole `SettingsContributor` implementation — used only for AI test/fetch ephemeral state.
- `appearanceModule()` was removed (no `AppearanceContributor` needed — `SettingsViewModel` handles appearance intents directly).

## Links

- `feature/settings/SettingsViewModel.kt` — implementation
- `feature/settings/SettingsUiState.kt` — state shape
