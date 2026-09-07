---
name: singularity-todo-llm-usage-tracking
description: LLM token usage tracking pattern for Singularity Todo KMP. Use when capturing, recording, and visualizing AI token consumption. Covers UsageRecorder port, LlmUsageEntity + Room migration v7→v8, ModelPricing table, UsageExtractor from Koog metaInfo, AI Usage debug screen, and privacy-first local-only design. Integrate with MCP server tools and ChatViewModel.
---

# LLM Token Usage Tracking

## Why This Skill Exists

AI tools consume tokens (and money). Without tracking, you have no idea which tools, models, or prompts are expensive. This skill provides a complete local-only observability layer:

1. **Capture** — hook into `promptExecutor.execute()` and extract usage from Koog's `metaInfo`
2. **Record** — persist to `LlmUsageEntity` in Room
3. **Price** — convert tokens to USD via `ModelPricing`
4. **Visualize** — `Settings → AI Usage` debug screen

Privacy-first: all data stays in Room on-device. No Langfuse, no external services by default.

## When to Use This Skill

- Adding `UsageRecorder` to a new AI tool or `KoogAgentService`.
- Creating the `LlmUsageEntity` table and DAO.
- Building the AI Usage debug screen.
- Debugging why a specific tool call costs too much.
- Understanding which model is most expensive.
- Per-profile token budgets (future work).

Skip for: read-only tools that never call the LLM, or one-off experiments.

## Architecture

```
KoogAgentService / LlmTool.execute()
        │
        ▼
UsageExtractor.extract(metaInfo) ──► UsageRecorder.record(ToolUsageEvent)
        │                                    │
        ▼                                    ▼
ToolUsageEvent                         LlmUsageEntity (Room)
(inputTokens, outputTokens,             (profileId, toolName, modelId,
 totalTokens, durationMs,                inputTokens, outputTokens,
 modelId, error)                         totalTokens, costUsdMicros,
                                        durationMs, createdAt, error)
                                              │
                                              ▼
                                    LlmUsageDao queries
                                              │
                                              ▼
                                    AI Usage Screen (Compose)
                                    (sparkline, per-tool, per-model)
```

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

## Room Entity and DAO

```kotlin
// shared/src/commonMain/.../core/database/Entities.kt
@Entity(
    tableName = "llm_usage",
    indices = [
        Index(value = ["created_at"]),
        Index(value = ["profile_id"]),
        Index(value = ["tool_name"]),
        Index(value = ["model_id"]),
    ]
)
data class LlmUsageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("profile_id") val profileId: String,
    @ColumnInfo("tool_name") val toolName: String,
    @ColumnInfo("model_id") val modelId: String,
    @ColumnInfo("input_tokens") val inputTokens: Int,
    @ColumnInfo("output_tokens") val outputTokens: Int,
    @ColumnInfo("total_tokens") val totalTokens: Int,
    @ColumnInfo("cost_usd_micros") val costUsdMicros: Long?,   // null if unknown
    @ColumnInfo("duration_ms") val durationMs: Long,
    @ColumnInfo("created_at") val createdAt: Long,             // epoch millis
    @ColumnInfo("error") val error: String?,
)
```

```kotlin
// shared/src/commonMain/.../core/database/Daos.kt
interface LlmUsageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: LlmUsageEntity)

    @Query("SELECT * FROM llm_usage WHERE profile_id = :profileId ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(profileId: String, limit: Int): Flow<List<LlmUsageEntity>>

    @Query("""
        SELECT date(created_at / 1000, 'unixepoch') as date,
               SUM(total_tokens) as totalTokens,
               SUM(cost_usd_micros) as totalCost,
               COUNT(*) as callCount
        FROM llm_usage
        WHERE profile_id = :profileId
          AND created_at >= :sinceEpochMs
        GROUP BY date(created_at / 1000, 'unixepoch')
        ORDER BY date DESC
    """)
    fun observeByDay(profileId: String, sinceEpochMs: Long): Flow<List<DailyUsageRow>>

    @Query("""
        SELECT tool_name, SUM(total_tokens), SUM(cost_usd_micros), COUNT(*)
        FROM llm_usage
        WHERE profile_id = :profileId
        GROUP BY tool_name
        ORDER BY SUM(total_tokens) DESC
    """)
    fun observeByTool(profileId: String): Flow<List<ToolUsageRow>>

    @Query("DELETE FROM llm_usage WHERE created_at < :cutoffEpochMs")
    suspend fun pruneOlderThan(cutoffEpochMs: Long)
}
```

**Migration v7→v8:** Additive — adds `llm_usage` table only. No schema changes to existing tables. Use `fallbackToDestructiveMigration` for dev, explicit `addMigrations()` for prod.

## ModelPricing — Token Cost Table

```kotlin
// shared/src/commonMain/.../feature/ai/ModelPricing.kt
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

## UsageExtractor — Reading Koog metaInfo

Koog 1.1.1 exposes usage via `Message.Assistant.metaInfo`:

```kotlin
// shared/src/commonMain/.../feature/ai/usage/UsageExtractor.kt
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

## Integrating into KoogAgentService

```kotlin
// In KoogAgentService.generate() — after agent.run():
private val clock = Clock.System

override suspend fun generate(prompt: String, systemPrompt: String?, model: String?): Result<String> {
    val start = clock.now()
    return runCatching {
        val apiKey = requireApiKey()
            ?: return@runCatching "(AI unavailable: API key not configured.)"

        val effectiveSystemPrompt = systemPrompt ?: settings.aiSystemPrompt.first().ifBlank { Prompts.chatSystem }
        val effectiveModel = model ?: settings.aiModel.first().ifBlank { OpenAiConfig.DEFAULT_MODEL }
        val resolvedModel = resolveModel(effectiveModel)

        val agent = createAgent(effectiveSystemPrompt, effectiveModel)
        try {
            val response = agent.run(prompt)
            // Extract usage from response.metaInfo
            val metaInfo = (response as? Message.Assistant)?.metaInfo
            val durationMs = (clock.now() - start).inWholeMilliseconds

            usageRecorder.record(UsageExtractor.extract(
                metaInfo = metaInfo,
                toolName = "chat.generate",
                modelId = effectiveModel,
                durationMs = durationMs,
                error = null,
                profileId = currentUser.profileId,
            ))
            response.toString()
        } finally {
            agent.close()
        }
    }
}
```

## AI Usage Screen

New destination: `AppDestination.AiUsage`

```kotlin
// shared/src/commonMain/.../feature/usage/AiUsageScreen.kt
@Composable
fun AiUsageScreen(viewModel: AiUsageViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Header card
        UsageHeaderCard(
            todayTokens = state.todayTokens,
            todayCost = state.todayCost,
            sevenDayAvg = state.sevenDayAvg,
        )

        // Sparkline (last 30 days)
        SparklineChart(data = state.dailyUsage, modifier = Modifier.height(80.dp))

        // Per-tool breakdown
        LazyColumn {
            items(state.byTool) { tool ->
                ToolUsageRow(tool)
            }
        }

        // Per-model breakdown
        LazyColumn {
            items(state.byModel) { model ->
                ModelUsageRow(model)
            }
        }

        // Actions
        Row {
            TextButton(onClick = { viewModel.exportCsv() }) { Text("Export CSV") }
            TextButton(onClick = { viewModel.clearHistory() }) { Text("Clear") }
        }
    }
}
```

**Sparkline** implementation using Compose `Canvas`:

```kotlin
@Composable
fun SparklineChart(data: List<DailyUsage>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        if (data.isEmpty()) return@Canvas
        val maxVal = data.maxOfOrNull { it.totalTokens } ?: 1
        val stepX = size.width / (data.size - 1).coerceAtLeast(1)
        val points = data.mapIndexed { i, day ->
            val x = i * stepX
            val y = size.height * (1 - day.totalTokens.toFloat() / maxVal)
            androidx.compose.ui.geometry.Offset(x, y)
        }
        drawLine(
            color = Color(0xFF4FC3F7),
            start = points.first(),
            end = points.last(),
            strokeWidth = 2f,
        )
    }
}
```

## Pruning

Old usage records should be pruned automatically to prevent DB bloat:

```kotlin
// In UsageRecorderImpl:
private val pruneDays = 90

suspend fun record(event: ToolUsageEvent) {
    dao.insert(event.toEntity())
    // Prune once per day (guard with timestamp check in DataStore)
    if (shouldPruneToday()) {
        dao.pruneOlderThan(clock.now().toEpochMilliseconds() - pruneDays * 86400 * 1000)
    }
}
```

## Common Mistakes

```kotlin
// ❌ WRONG — ignoring metaInfo from Koog
val result = agent.run(prompt)
// never reads metaInfo

// ✅ CORRECT — extract usage from response
val response = agent.run(prompt)
val metaInfo = (response as? Message.Assistant)?.metaInfo

// ❌ WRONG — storing cost as Double (floating point errors)
val cost = inputTokens * pricePerMillion / 1_000_000  // Double!

// ✅ CORRECT — store as micros Long (integer math)
val costMicros = (inputTokens.toLong() * pricePerMillion * 1_000_000 / 1_000_000).toLong()

// ❌ WRONG — no pruning, DB grows forever
dao.insert(entity)

// ✅ CORRECT — prune old records
if (shouldPruneToday()) dao.pruneOlderThan(cutoff)

// ❌ WRONG — hardcoding prices in tool
val price = if (model == "gpt-4o-mini") 0.15 else ...

// ✅ CORRECT — pricing table lookup
val priceMicros = ModelPricing.priceOrNull(model, input, output)
```

## Files Reference

| File | Purpose |
|---|---|
| `shared/src/commonMain/.../core/observability/UsageRecorder.kt` | Port + data classes |
| `shared/src/commonMain/.../core/observability/RoomUsageRecorder.kt` | Room implementation |
| `shared/src/commonMain/.../core/database/Entities.kt` | `LlmUsageEntity` |
| `shared/src/commonMain/.../core/database/Daos.kt` | `LlmUsageDao` |
| `shared/src/commonMain/.../feature/ai/ModelPricing.kt` | Pricing table |
| `shared/src/commonMain/.../feature/ai/usage/UsageExtractor.kt` | Koog metaInfo extraction |
| `shared/src/commonMain/.../feature/usage/AiUsageScreen.kt` | Compose screen |
| `shared/src/commonMain/.../feature/usage/AiUsageViewModel.kt` | ViewModel with state |

## Related Skills

- `singularity-todo-mcp-server` — MCP tools call `UsageRecorder.record()`.
- `singularity-todo-cli-tool-surface` — write tools record usage after execution.
- `singularity-todo-multi-profile` — `profileId` in every `ToolUsageEvent` enables per-profile breakdown.
- `singularity-todo-pure-formatters` — `SparklineChart` uses `Canvas.drawLine` (pure Compose, no external deps).
- `singularity-todo-shared-ui-components` — `UsageHeaderCard` reuses `SettingsSection` pattern.
- `singularity-todo-room-migration` — v7→v8 migration for `llm_usage` table.
- ADR `2026-09-07-multi-profile-and-usage-tracking` — rationale for local-only storage.
