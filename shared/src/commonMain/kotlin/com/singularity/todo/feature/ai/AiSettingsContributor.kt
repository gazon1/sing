package com.singularity.todo.feature.ai

import com.singularity.todo.core.settings.SettingsContributor
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsSection
import com.singularity.todo.feature.ai.data.AiSettingsStore
import kotlinx.coroutines.flow.Flow

/**
 * Contributes the AI Provider settings section to the unified settings UI.
 *
 * Registration: `single<SettingsContributor> { AiSettingsContributor(get()) }`
 * in [com.singularity.todo.feature.ai.di.aiSettingsModule].
 *
 * Handles all AI settings intents including:
 * - Provider, base URL, model, system prompt (persisted via [AiSettingsStore])
 * - Test connection / fetch models (executed by [AiSettingsStore])
 * - API key writes go directly to [com.singularity.todo.core.security.SecureStoragePort]
 *
 * [SettingsViewModel] discovers this contributor via `getAll<SettingsContributor>()`
 * and merges its [observe] stream into the unified settings state.
 */
class AiSettingsContributor(private val store: AiSettingsStore) :
    SettingsContributor<SettingsSection.Ai, SettingsIntent.Ai> {

    override val section: SettingsSection.Ai = SettingsSection.Ai()

    override fun observe(): Flow<SettingsSection.Ai> = store.observe()

    override suspend fun process(intent: SettingsIntent.Ai) {
        store.process(intent)
    }

    /** Exposes the ephemeral test/fetch state as a StateFlow for synchronous reads. */
    val testResultStateFlow = store.testResultStateFlow
    val modelsStateFlow = store.modelsStateFlow
    val isFetchingModelsStateFlow = store.isFetchingModelsStateFlow
    val fetchModelsErrorStateFlow = store.fetchModelsErrorStateFlow

    /**
     * Writes the API key to SecureStorage without going through [process].
     * Called directly by [com.singularity.todo.feature.settings.SettingsViewModel]
     * when the user finishes editing the key field (debounced in the ViewModel layer).
     */
    suspend fun updateApiKey(value: String) {
        store.updateApiKey(value)
    }
}
