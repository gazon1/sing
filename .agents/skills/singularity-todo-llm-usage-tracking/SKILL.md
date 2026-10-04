---
name: singularity-todo-llm-usage-tracking
description: LLM token usage tracking in Singularity Todo KMP. Use when capturing, recording, or visualizing AI token consumption. Covers the concrete RoomUsageRecorder, LlmUsageEntity + LlmUsageDao, and the AiUsageScreen. Read the "What exists vs what does not" section first — this skill previously documented a UsageRecorder port, a ModelPricing cost table and a UsageExtractor that were never built. Integrate with MCP server tools and ChatViewModel.
---

# LLM Token Usage Tracking

## What exists vs what does not

**Check this table before following anything below.** This skill described an
architecture that was partially built, and the gaps were not marked — an agent
following it would write against types that do not exist. Verified 2026-10-04:

| Documented here | Reality |
|---|---|
| `UsageRecorder` port (interface) | **Does not exist.** There is no port. `core/observability/UsageRecorder.kt` holds only the data classes `ToolUsageEvent`, `DailyUsage`, `ToolUsage`, `ModelUsage`. The one implementation is the concrete class `RoomUsageRecorder`. (There is a `LlmUsageRecorderTest` in `jvmTest`, but no `LlmUsageRecorder` in production — do not assume a second implementation exists.) |
| `LlmUsageEntity` | **Real** — `core/database/Entities.kt`, registered in `AppDatabase`, with `LlmUsageDao`. |
| `AiUsageScreen` | **Real** — `feature/ai/usage/AiUsageScreen.kt`. |
| `ModelPricing` cost table | **Does not exist.** There is no pricing table and no model→cost mapping. |
| `UsageExtractor` (reads Koog `metaInfo`) | **Does not exist.** Nothing extracts usage from `metaInfo`. |

**Consequence worth knowing:** `ToolUsage.totalCostUsdMicros` and
`ModelUsage.totalCostUsdMicros` are **nullable** (`Long?`), and they stay null
because there is no pricing table to populate them. That is not an oversight to
"fix" by filling in zeros — a fabricated cost is worse than an absent one. If you
add a pricing table, those fields become the only place it surfaces.

The sections titled `UsageRecorder Port`, `ModelPricing — Token Cost Table` and
`UsageExtractor — Reading Koog metaInfo` are kept below as **design intent**, not
as instructions. They are marked inline. If you are implementing one of them, treat
the section as a starting sketch and verify every symbol against the code first.

## Why This Skill Exists

AI tools consume tokens (and money). Without tracking, you have no idea which tools, models, or prompts are expensive. This skill provides a complete local-only observability layer:

1. **Capture** — hook into `promptExecutor.execute()` and extract usage from Koog's `metaInfo`
2. **Record** — persist to `LlmUsageEntity` in Room
3. **Price** — convert tokens to USD via `ModelPricing`
4. **Visualize** — `Settings → AI Usage` debug screen

Privacy-first: all data stays in Room on-device. No Langfuse, no external services by default.

Steps 1 and 3 are **not implemented**; steps 2 and 4 are.

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

## The parts that were never built

Three sections of this skill described a `UsageRecorder` port, a `ModelPricing`
cost table and a `UsageExtractor` for Koog `metaInfo`. **None of them exist.**
They were moved to `DESIGN-INTENT.md` in this directory on 2026-10-04, so the
file you are reading contains only code that is actually in the tree.

Read that file only if you are implementing one of them — and verify its symbols
against the code first; it is 2026-era design, not a description of the repo.

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
// shared/src/commonMain/.../feature/ai/usage/AiUsageScreen.kt
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
| `shared/src/commonMain/.../core/observability/UsageRecorder.kt` | Port + data classes (`ToolUsageEvent.costUsdMicros` — `null` если модель не в pricing table) |
| `shared/src/commonMain/.../core/observability/RoomUsageRecorder.kt` | Room implementation |
| `shared/src/commonMain/.../core/database/Entities.kt` | `LlmUsageEntity` |
| `shared/src/commonMain/.../core/database/Daos.kt` | `LlmUsageDao` |
| `shared/src/commonMain/.../feature/ai/usage/AiUsageScreen.kt` | Compose screen |
| `shared/src/commonMain/.../feature/ai/usage/AiUsageViewModel.kt` | ViewModel with state |

> ⚠️ `ModelPricing` (pricing table) и `UsageExtractor` (Koog `metaInfo` extraction) из примеров
> ниже **ещё не реализованы** — `costUsdMicros` всегда `null`. Примеры описывают целевой
> дизайн, а не существующие файлы. Backlog: `docs/decisions/2026-09-27-doc-and-skills-sprint-findings.md`.

## Related Skills

- `singularity-todo-mcp-server` — MCP tools call `UsageRecorder.record()`.
- `singularity-todo-cli-tool-surface` — write tools record usage after execution.
- `singularity-todo-multi-profile` — `profileId` in every `ToolUsageEvent` enables per-profile breakdown.
- `singularity-todo-pure-formatters` — `SparklineChart` uses `Canvas.drawLine` (pure Compose, no external deps).
- `singularity-todo-shared-ui-components` — `UsageHeaderCard` reuses `SettingsSection` pattern.
- `singularity-todo-room-migration` — v7→v8 migration for `llm_usage` table.
- ADR `2026-09-07-multi-profile-and-usage-tracking` — rationale for local-only storage.
