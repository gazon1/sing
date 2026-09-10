package com.singularity.todo.feature.ai.data

import com.singularity.todo.core.llm.AiTestResult
import com.singularity.todo.core.llm.LlmProvider
import com.singularity.todo.core.llm.OpenAiConfig
import com.singularity.todo.core.llm.SettingsReader
import com.singularity.todo.core.llm.TextGenPort
import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.core.settings.SettingsSection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

/**
 * Reads and writes AI provider settings.
 *
 * Secrets (API key) go through [SecureStoragePort]; non-secret settings use
 * [SettingsRepository]. Test/fetch results are held in ephemeral [MutableStateFlow]
 * and exposed as part of the [SettingsSection.Ai] section.
 *
 * ## Architecture note
 * Each AI intent is handled directly — no debouncing of keystrokes here.
 * The [com.singularity.todo.feature.ai.AiSettingsContributor] routes intents here.
 * Debouncing (if needed for the API key field) stays in the presentation layer.
 */
class AiSettingsStore(
    private val secureStorage: SecureStoragePort,
    private val settings: SettingsRepository,
    private val textGen: TextGenPort,
) {
    // ─── Ephemeral UI state ─────────────────────────────────────────────────────

    private val _testResult = MutableStateFlow<AiTestResult>(AiTestResult.Idle)
    private val _models = MutableStateFlow<List<String>>(emptyList())
    private val _isFetchingModels = MutableStateFlow(false)
    private val _fetchModelsError = MutableStateFlow<String?>(null)

    /** Readonly access for SettingsViewModel synchronous reads. */
    val testResultStateFlow = _testResult.asStateFlow()
    val modelsStateFlow = _models.asStateFlow()
    val isFetchingModelsStateFlow = _isFetchingModels.asStateFlow()
    val fetchModelsErrorStateFlow = _fetchModelsError.asStateFlow()

    /**
     * Full AI settings section — combines persisted settings with live test/fetch state.
     */
    fun observe(): Flow<SettingsSection.Ai> = combine(
        settings.aiProvider,
        settings.aiBaseUrl,
        settings.aiModel,
        settings.aiSystemPrompt,
        _testResult,
        _models,
        _isFetchingModels,
        _fetchModelsError,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val provider = LlmProvider.fromId(values[0] as String)
        val baseUrl = values[1] as String
        val model = values[2] as String
        val systemPrompt = values[3] as String
        val testResult = values[4] as AiTestResult
        val models = values[5] as List<String>
        val isFetching = values[6] as Boolean
        val fetchError = values[7] as String?
        SettingsSection.Ai(
            provider = provider,
            baseUrl = baseUrl,
            model = model,
            systemPrompt = systemPrompt,
            testResult = testResult,
            models = models,
            isFetchingModels = isFetching,
            fetchModelsError = fetchError,
        )
    }

    suspend fun apply(intent: SettingsIntent.Ai) {
        when (intent) {
            is SettingsIntent.Ai.UpdateProvider -> settings.setAiProvider(intent.value.id)
            is SettingsIntent.Ai.UpdateBaseUrl -> settings.setAiBaseUrl(intent.value)
            is SettingsIntent.Ai.UpdateModel -> settings.setAiModel(intent.value)
            is SettingsIntent.Ai.UpdateSystemPrompt -> settings.setAiSystemPrompt(intent.value)
            is SettingsIntent.Ai.UpdateApiKey -> updateApiKey(intent.value)
            is SettingsIntent.Ai.TestConnection -> testConnection()
            is SettingsIntent.Ai.FetchModels -> fetchModels()
        }
    }

    suspend fun updateApiKey(value: String) {
        if (value.isNotBlank()) {
            secureStorage.write(OpenAiConfig.KEY_OPENAI, value)
        } else {
            secureStorage.delete(OpenAiConfig.KEY_OPENAI)
        }
    }

    private suspend fun testConnection() {
        _testResult.value = AiTestResult.Testing
        val start = System.currentTimeMillis()
        val reader = object : SettingsReader {
            override val aiProvider: Flow<String> = settings.aiProvider
            override val aiBaseUrl: Flow<String> = settings.aiBaseUrl
            override val aiModel: Flow<String> = settings.aiModel
        }
        val cfg = OpenAiConfig.resolve(secureStorage, reader)
        if (!cfg.apiKey.isConfigured) {
            _testResult.value = AiTestResult.Error("API key not configured")
            return
        }
        val result = textGen.generate(
            prompt = "ping",
            systemPrompt = "You are a connectivity probe. Reply with the single word: pong.",
            model = cfg.defaultModelId,
        )
        val latency = System.currentTimeMillis() - start
        _testResult.value = result.fold(
            onSuccess = { AiTestResult.Ok(latency) },
            onFailure = { e -> AiTestResult.Error(e.message ?: "Unknown error") },
        )
    }

    private suspend fun fetchModels() {
        _isFetchingModels.value = true
        _fetchModelsError.value = null
        val reader = object : SettingsReader {
            override val aiProvider: Flow<String> = settings.aiProvider
            override val aiBaseUrl: Flow<String> = settings.aiBaseUrl
            override val aiModel: Flow<String> = settings.aiModel
        }
        val cfg = OpenAiConfig.resolve(secureStorage, reader)
        if (!cfg.apiKey.isConfigured) {
            _isFetchingModels.value = false
            _fetchModelsError.value = "API key not configured"
            return
        }
        val result = textGen.listModels(cfg.baseUrl, cfg.apiKey.value)
        _isFetchingModels.value = false
        result.fold(
            onSuccess = { _models.value = it },
            onFailure = { _fetchModelsError.value = it.message },
        )
    }
}
