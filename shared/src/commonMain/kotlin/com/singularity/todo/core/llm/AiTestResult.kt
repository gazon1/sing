package com.singularity.todo.core.llm

/**
 * Result of the "Test connection" probe from the AI Provider settings screen.
 * Lives in `core/llm/` so both `SettingsSection.Ai` (core/settings) and the
 * AI contributor can reference it without cross-feature coupling.
 */
sealed interface AiTestResult {
    data object Idle : AiTestResult
    data object Testing : AiTestResult
    data class Ok(val latencyMs: Long) : AiTestResult
    data class Error(val message: String) : AiTestResult
}
