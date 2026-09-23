package com.singularity.todo.feature.ai

import com.singularity.todo.core.settings.EphemeralState
import com.singularity.todo.core.settings.SettingsContributor
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsSection
import com.singularity.todo.feature.ai.data.AiSettingsStore
import kotlinx.coroutines.flow.Flow

/**
 * Marker interface for the AI settings contributor.
 * Used by [com.singularity.todo.feature.settings.SettingsViewModel] to resolve the
 * contributor without type erasure.
 */
interface AiContributor : SettingsContributor<SettingsSection.Ai, SettingsIntent.Ai> {
    /** Ephemeral (non-persisted) AI state: test result, model list, fetch status. */
    val ephemeralStateFlow: kotlinx.coroutines.flow.Flow<EphemeralState.Ai>

    /** Writes the API key to SecureStorage. */
    suspend fun updateApiKey(value: String)
}

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
 * [SettingsViewModel] discovers this contributor via `filterIsInstance<AiContributor>()`.
 */
class AiSettingsContributor(private val store: AiSettingsStore) : AiContributor {

    override val section: SettingsSection.Ai = SettingsSection.Ai()

    override fun observe(): Flow<SettingsSection.Ai> = store.observe()

    override suspend fun process(intent: SettingsIntent.Ai) {
        store.process(intent)
    }

    /** Ephemeral (non-persisted) AI state: test result, model list, fetch status. */
    override val ephemeralStateFlow: Flow<EphemeralState.Ai> = store.ephemeralStateFlow

    /**
     * Writes the API key to SecureStorage without going through [process].
     * Called directly by [com.singularity.todo.feature.settings.SettingsViewModel]
     * when the user finishes editing the key field (debounced in the ViewModel layer).
     */
    override suspend fun updateApiKey(value: String) {
        store.updateApiKey(value)
    }
}
