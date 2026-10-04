# LLM usage tracking — design intent for the unimplemented parts

**None of the types in this file exist in the codebase.** Verified 2026-10-04.

These three sections were written as a design for AI cost tracking that was never
built. They were moved out of `SKILL.md` on 2026-10-04 for two reasons: the doc
budget put `SKILL.md` over 500 lines, and — more importantly — an agent that
loaded the main skill was reading ~200 lines of plausible-looking Kotlin for types
that do not exist. It would then write against them.

**What does exist** is described in `SKILL.md`: the `UsageRecorder.kt` data classes
(`ToolUsageEvent`, `DailyUsage`, `ToolUsage`, `ModelUsage`), the concrete
recorder `RoomUsageRecorder`, `LlmUsageEntity` with
`LlmUsageDao`, and `AiUsageScreen`. There is no port interface, no pricing table,
and nothing reads Koog's `metaInfo`.

If you are implementing any of this, start here, but verify each symbol against the
code before you rely on it — the surrounding text is 2026-era design, not a
description of the tree. Note that `ToolUsage.totalCostUsdMicros` and
`ModelUsage.totalCostUsdMicros` are already declared `Long?` and already
persisted; a pricing table has a home to surface in, it just does not exist yet.

---

## UsageRecorder Port

```kotlin
// shared/src/commonMain/.../core/observability/UsageRecorder.kt
package com.singularity.todo.core.observability

import kotlinx.datetime.Instant

data class ToolUsageEvent(
    val toolName: String,           // e.g., "decompose_and_create"
    val modelId: String,           // e.g., "gpt-4o-mini"
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    val costUsdMicros: Long?,       // null if model not in pricing table
    val durationMs: Long,
    val profileId: String,           // from CurrentUser.profileId
    val error: String?,             // null on success
    val timestamp: Instant = Instant.now(),
)

interface UsageRecorder {
    suspend fun record(event: ToolUsageEvent)
    fun observeRecent(limit: Int = 100): Flow<List<ToolUsageEvent>>
    fun observeByDay(days: Int = 30): Flow<List<DailyUsage>>
    fun observeByTool(): Flow<List<ToolUsage>>
    fun observeByModel(): Flow<List<ModelUsage>>
    fun observeByProfile(): Flow<List<ProfileUsage>>
    suspend fun prune(olderThanDays: Int = 90)
}

data class DailyUsage(
    val date: String,              // "2026-09-07"
    val totalTokens: Int,
    val totalCostUsdMicros: Long,
    val requestCount: Int,
)

data class ToolUsage(
    val toolName: String,
    val totalTokens: Int,
    val totalCostUsdMicros: Long,
    val callCount: Int,
    val avgTokensPerCall: Int,
)

data class ModelUsage(
    val modelId: String,
    val totalTokens: Int,
    val totalCostUsdMicros: Long,
    val callCount: Int,
)

data class ProfileUsage(
    val profileId: String,
    val totalTokens: Int,
    val totalCostUsdMicros: Long,
    val callCount: Int,
)
```


> **NOT IMPLEMENTED (verified 2026-10-04).** There is no `ModelPricing` type in the
> codebase. Do not write against it. This section is kept as design intent for
> whoever builds it — verify every other symbol in it against the code too.

## ModelPricing — Token Cost Table

```kotlin
// shared/src/commonMain/.../core/observability/UsageRecorder.kt (costUsdMicros field)
package com.singularity.todo.feature.ai

/**
 * Pricing in USD per 1,000,000 tokens (USD * 10^-6 per token).
 * Source: OpenAI / Anthropic / Ollama pricing pages, 2026-09-07.
 * Update this when prices change.
 */
data class ModelPricing(
    val inputPerMillionUsd: Double,
    val outputPerMillionUsd: Double,
) {
    fun priceTokens(inputTokens: Int, outputTokens: Int): Long {
        val inputCost = inputTokens * inputPerMillionUsd / 1_000_000
        val outputCost = outputTokens * outputPerMillionUsd / 1_000_000
        return ((inputCost + outputCost) * 1_000_000).toLong() // micros
    }
}

internal object ModelPricing {
    /** "pricing last updated: 2026-09-07" */
    val TABLE: Map<String, ModelPricing> = buildMap {
        // OpenAI
        put("gpt-4o-mini", ModelPricing(0.15, 0.60))
        put("gpt-4o", ModelPricing(2.50, 10.00))
        put("gpt-4.1", ModelPricing(2.00, 8.00))
        put("gpt-4.1-mini", ModelPricing(0.50, 2.00))
        put("gpt-4.1-nano", ModelPricing(0.10, 0.40))
        // Anthropic (via OpenAI-compatible endpoint)
        put("claude-sonnet-4-20250514", ModelPricing(3.00, 15.00))
        put("claude-3-5-sonnet-20241022", ModelPricing(3.00, 15.00))
        put("claude-3-5-haiku-20241022", ModelPricing(0.80, 4.00))
        // Ollama (local — free)
        put("llama3", ModelPricing(0.0, 0.0))
        put("mistral", ModelPricing(0.0, 0.0))
        put("qwen2.5", ModelPricing(0.0, 0.0))
    }

    fun priceOrNull(modelId: String, inputTokens: Int, outputTokens: Int): Long? {
        return TABLE[modelId]?.priceTokens(inputTokens, outputTokens)
    }
}
```

> **NOT IMPLEMENTED (verified 2026-10-04).** There is no `UsageExtractor` type in the
> codebase. Do not write against it. This section is kept as design intent for
> whoever builds it — verify every other symbol in it against the code too.


## UsageExtractor — Reading Koog metaInfo

Koog 1.1.1 exposes usage via `Message.Assistant.metaInfo`:

```kotlin
// shared/src/commonMain/.../core/observability/RoomUsageRecorder.kt
package com.singularity.todo.feature.ai.usage

import ai.koog.prompt.executor.model.ResponseMetaInfo
import com.singularity.todo.feature.ai.ModelPricing
import com.singularity.todo.core.observability.ToolUsageEvent
import kotlinx.datetime.Clock

/**
 * Extracts token usage from Koog's ResponseMetaInfo.
 * Handles provider differences (OpenAI / Anthropic / Ollama).
 */
object UsageExtractor {

    fun extract(
        metaInfo: ResponseMetaInfo?,
        toolName: String,
        modelId: String,
        durationMs: Long,
        error: String?,
        profileId: String,
    ): ToolUsageEvent {
        val inputTokens = metaInfo?.inputTokensCount ?: 0
        val outputTokens = metaInfo?.outputTokensCount ?: 0
        val totalTokens = metaInfo?.totalTokensCount ?: (inputTokens + outputTokens)

        val costMicros = ModelPricing.priceOrNull(modelId, inputTokens, outputTokens)

        return ToolUsageEvent(
            toolName = toolName,
            modelId = modelId,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = totalTokens,
            costUsdMicros = costMicros,
            durationMs = durationMs,
            profileId = profileId,
            error = error,
        )
    }

    /**
     * For data tools (no LLM call): zero tokens.
     */
    fun forDataTool(toolName: String, profileId: String): ToolUsageEvent {
        return ToolUsageEvent(
            toolName = toolName,
            modelId = "n/a",
            inputTokens = 0,
            outputTokens = 0,
            totalTokens = 0,
            costUsdMicros = null,
            durationMs = 0,
            profileId = profileId,
            error = null,
        )
    }
}
```

