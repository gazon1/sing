package com.singularity.todo.feature.ai.data

import com.singularity.todo.core.llm.AiTestResult
import com.singularity.todo.core.llm.LlmProvider
import com.singularity.todo.core.llm.OpenAiConfig
import com.singularity.todo.core.llm.SettingsReader
import com.singularity.todo.core.llm.TextGenPort
import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.core.settings.EphemeralState
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.core.settings.SettingsSection
import com.singularity.todo.core.ui.textfields.FieldOwnedText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/**
 * Reads and writes AI provider settings.
 *
 * Secrets (API key) go through [SecureStoragePort]; non-secret settings use
 * [SettingsRepository]. Test/fetch results are held in ephemeral [MutableStateFlow]
 * and combined into [SettingsSection.Ai] via [ephemeralStateFlow].
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

    // ─── The write the store last made ──────────────────────────────────────────
    //
    // The store writes the field's own text to the disk, and the disk hands the value
    // back. The value that comes back is the one the store wrote — and it is still worth
    // saying *which* write it was: the field's own text coming back is an echo, while a
    // value from outside the field is a seed. The store remembers the write it made, so
    // the value it hands to the field keeps the kind of that write.
    //
    // A value that does not match the last write came from somewhere else — another
    // instance writing the same disk, an import — and is therefore a seed.

    private var baseUrlWrite: FieldOwnedText? = null
    private var modelWrite: FieldOwnedText? = null
    private var systemPromptWrite: FieldOwnedText? = null

    /** Combines the four ephemeral flows into a single state object. */
    val ephemeralStateFlow: Flow<EphemeralState.Ai> = combine(
        _testResult,
        _models,
        _isFetchingModels,
        _fetchModelsError,
    ) { testResult, models, isFetching, fetchError ->
        EphemeralState.Ai(
            testResult = testResult,
            models = models,
            isFetchingModels = isFetching,
            fetchModelsError = fetchError,
        )
    }

    /**
     * Full AI settings section — combines persisted settings with live ephemeral state.
     */
    fun observe(): Flow<SettingsSection.Ai> = combine(
        settings.aiProvider,
        settings.aiBaseUrl,
        settings.aiModel,
        settings.aiSystemPrompt,
        ephemeralStateFlow,
    ) { provider, baseUrl, model, systemPrompt, aiEphem ->
        SettingsSection.Ai(
            provider = LlmProvider.fromId(provider),
            baseUrl = baseUrl.asWritten(baseUrlWrite),
            model = model.asWritten(modelWrite),
            systemPrompt = systemPrompt.asWritten(systemPromptWrite),
            testResult = aiEphem.testResult,
            models = aiEphem.models,
            isFetchingModels = aiEphem.isFetchingModels,
            fetchModelsError = aiEphem.fetchModelsError,
        )
    }

    /**
     * The value the store holds, with the kind of the write that produced it.
     *
     * A value that matches the store's last write *is* that write, and keeps its kind: the
     * field's own text coming back is an echo, a value from outside the field is a seed.
     * A value that does not match came from somewhere else and is a seed.
     */
    private fun String.asWritten(write: FieldOwnedText?): FieldOwnedText =
        write?.takeIf { it.text == this } ?: FieldOwnedText.empty().seeded(this)

    suspend fun process(intent: SettingsIntent.Ai) {
        when (intent) {
            is SettingsIntent.Ai.UpdateProvider -> settings.setAiProvider(intent.value.id)

            is SettingsIntent.Ai.UpdateBaseUrl -> {
                baseUrlWrite = intent.value
                settings.setAiBaseUrl(intent.value.text)
            }

            is SettingsIntent.Ai.UpdateModel -> {
                modelWrite = intent.value
                settings.setAiModel(intent.value.text)
            }

            is SettingsIntent.Ai.UpdateSystemPrompt -> {
                systemPromptWrite = intent.value
                settings.setAiSystemPrompt(intent.value.text)
            }

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
