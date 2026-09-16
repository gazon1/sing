package com.singularity.todo.core.llm

/**
 * Known LLM providers the app can target.
 *
 * The id is the user-facing identifier persisted in settings; the defaultBaseUrl is
 * the endpoint the [LlmProvider] points at when the user has not entered a custom URL.
 * Switching providers in Settings auto-fills the URL only when the current value is
 * blank or matches the previous provider's default — see [OpenAiConfig.resolveBaseUrl].
 */
enum class LlmProvider(val id: String, val defaultBaseUrl: String) {
    OPENAI("openai", "https://api.openai.com/v1"),
    OPENAI_COMPATIBLE("openai-compatible", "https://api.openai.com/v1"),
    ANTHROPIC_COMPATIBLE("anthropic-compatible", "https://api.anthropic.com/v1"),
    OLLAMA("ollama", "http://localhost:11434/v1"),
    CUSTOM("custom", ""),
    ;

    companion object {
        val DEFAULT: LlmProvider = OPENAI

        /** Resolves a stored id back to a provider, falling back to [DEFAULT] for unknown values. */
        fun fromId(id: String?): LlmProvider = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
