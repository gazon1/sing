package com.singularity.todo.test.fakes

import com.singularity.todo.core.llm.TextGenPort
import kotlinx.coroutines.flow.Flow

/** What the fake says when nothing was configured — a user-visible string, not a test value. */
private const val DEFAULT_SUCCESS =
    "(Placeholder AI response — configure API key in Settings > AI Provider to enable real AI.)"

/**
 * In-memory fake of [TextGenPort].
 *
 * Default behaviour returns a placeholder "AI unavailable" message — used by tests
 * standing in for a configured provider. Tests can override [success],
 * [failureMessage], or pass `trackGenerateCalls` = `true` to capture invocations.
 *
 * ## Why this file and not `feature/ai/KoogAgentService.kt`
 *
 * It used to live in the same file as the production implementation, and only tests
 * ever constructed it: `AiToolsModule.android.kt` and `AiToolsModule.jvm.kt` both bind
 * `KoogAgentService`. `find-unwired-surfaces.py` reports exactly that shape — 5 test
 * references, no production caller — and says a double belongs in `test/fakes/`, where
 * the other 13 live.
 *
 * Its own KDoc used to claim it was "used on platforms where the AI layer is not
 * wired". Nothing bound it, which is why the KDoc moved here with it rather than
 * being corrected in place: the claim was the reason it sat next to production code,
 * and it was never true.
 */
class FakeTextGen(
    private val success: String = DEFAULT_SUCCESS,
    private val failureMessage: String? = null,
    private val trackGenerateCalls: Boolean = false,
) : TextGenPort {
    private val _generateCalls = mutableListOf<Triple<String, String?, String?>>()

    override suspend fun generate(prompt: String, systemPrompt: String?, model: String?): Result<String> {
        if (trackGenerateCalls) _generateCalls += Triple(prompt, systemPrompt, model)
        return failureMessage?.let { Result.failure(RuntimeException(it)) } ?: Result.success(success)
    }

    override fun streamChat(message: String): Flow<String> = kotlinx.coroutines.flow.flowOf(success)

    override suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> = Result.success(emptyList())
}
