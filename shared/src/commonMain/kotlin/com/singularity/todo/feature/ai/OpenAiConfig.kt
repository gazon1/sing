package com.singularity.todo.feature.ai

import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.core.settings.SettingsRepository
import kotlinx.coroutines.flow.firstOrNull
import com.singularity.todo.core.settings.SettingsRepository.Companion.DEFAULT_AI_MODEL

/** A non-blank OpenAI API key. */
@JvmInline
value class ApiKey(val value: String) {
    val isConfigured: Boolean get() = value.isNotBlank()
    companion object { val EMPTY = ApiKey("") }
}

/**
 * Resolved configuration for talking to an OpenAI-compatible LLM endpoint.
 *
 * Built by [OpenAiConfig.resolve] from secure storage (API key) and settings
 * (provider, base URL, model). Pure data — no I/O — so it can be cached or
 * tested without touching the network.
 */
data class OpenAiConfig(
    val provider: LlmProvider,
    val apiKey: ApiKey,
    val baseUrl: String,
    val defaultModelId: String,
) {
    companion object {
        /** Key used to read/write the OpenAI API key in [SecureStoragePort]. */
        const val KEY_OPENAI = "ai_key_openai"

        /** Model selected when settings has no preference. Aliased to
         *  [SettingsRepository.DEFAULT_AI_MODEL] so there's a single source of truth. */
        const val DEFAULT_MODEL: String = DEFAULT_AI_MODEL

        /** System prompt shipped with the app. User-editable via Settings. */
        const val DEFAULT_SYSTEM_PROMPT =
            "You are a helpful productivity assistant. Be concise and actionable."

        /**
         * Builds an [OpenAiConfig] by reading the API key from secure storage
         * and provider/URL/model from settings. Pure-read; no side effects.
         *
         * Provider switching is reflected here: if the stored URL matches the
         * default of *any* provider, we replace it with the current provider's
         * default. This keeps [resolve] consistent with the UI's auto-fill
         * behaviour (see `AiProviderSettingsScreen`) so that `Test connection`
         * and `streamChat` hit the URL the user actually intends.
         */
        suspend fun resolve(
            secureStorage: SecureStoragePort,
            settings: SettingsRepository,
        ): OpenAiConfig {
            val provider = LlmProvider.fromId(settings.aiProvider.firstOrNull())
            val storedUrl = settings.aiBaseUrl.firstOrNull().orEmpty()
            val baseUrl = resolveBaseUrl(storedUrl, provider)
            val modelId = settings.aiModel.firstOrNull().orEmpty()
                .ifBlank { DEFAULT_MODEL }
            val apiKey = ApiKey(secureStorage.read(KEY_OPENAI).orEmpty())
            return OpenAiConfig(
                provider = provider,
                apiKey = apiKey,
                baseUrl = baseUrl,
                defaultModelId = modelId,
            )
        }

        /**
         * Returns the URL to use for [provider]. If [storedUrl] is blank, or
         * matches the default of *any* known provider, return the current
         * provider's default. Otherwise respect [storedUrl] verbatim.
         */
        internal fun resolveBaseUrl(storedUrl: String, provider: LlmProvider): String {
            if (storedUrl.isBlank()) return provider.defaultBaseUrl
            val matchesAnyDefault = LlmProvider.entries.any { it.defaultBaseUrl == storedUrl }
            return if (matchesAnyDefault) provider.defaultBaseUrl else storedUrl
        }
    }
}