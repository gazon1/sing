---
title: Multi-Profile and LLM Usage Tracking
date: 2026-09-07
status: accepted
tags: [multi-profile, llm-usage, observability, dogfooding]
---

# Multi-Profile and LLM Usage Tracking

## Context

AI-агенты (ZCode, Claude Code) используют токены при генерации задач и заметок. Нужно:
1. **Изоляция данных** — AI-агентские задачи не мешают пользовательским
2. **Usage observability** — видеть сколько токенов жрёт каждый вызов и агрегированно
3. **Per-profile настройки** — разные AI-провайдеры для разных профилей

Проблема: текущий `CurrentUser` даёт один `userId` на установку. Нет изоляции между "ai-agent" и "personal" профилями.

## Decision

### Multi-Profile

**Доменная модель:**
```kotlin
@JvmInline
value class ProfileId(val value: String) {
    companion object {
        val default = ProfileId("default")
        fun generate() = ProfileId(nextId())
    }
}

data class Profile(
    val id: ProfileId,
    val name: String,
    val emoji: String,
    val colorIdx: Int,
    val isDefault: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)
```

**ProfileAwareCurrentUser** — оборачивает `CurrentUser`:
```kotlin
class ProfileAwareCurrentUser(
    private val currentUser: CurrentUser,
    private val profileRepository: ProfileRepository,
) {
    val scopedUserId: StateFlow<UserId> = combine(
        currentUser.userId,
        profileRepository.activeProfileId
    ) { userId, profileId ->
        if (profileId == ProfileId.default) userId
        else UserId.fromString("${profileId.value}/${userId.value}")
    }.stateIn(scope, SharingStarted.Eagerly, UserId.anonymous)
}
```

**Результат:** scoped userId = `"ai-agent/u123"` для ai-agent профиля, `"u123"` для default.

**ProfileRepository:**
```kotlin
interface ProfileRepository {
    val activeProfileId: StateFlow<ProfileId>
    fun all(): Flow<List<Profile>>
    fun activeProfile(): Flow<Profile?>
    suspend fun create(name: String, emoji: String, colorIdx: Int): Profile
    suspend fun update(id: ProfileId, name: String, emoji: String, colorIdx: Int)
    suspend fun delete(id: ProfileId)
    suspend fun switchTo(id: ProfileId)
    suspend fun getById(id: ProfileId): Profile?
}
```

**Хранение:** профили в Room (`profiles` table), active ID в DataStore (`active_profile_id` key).

### LLM Usage Tracking

**Room entity:**
```kotlin
@Entity(tableName = "llm_usage")
data class LlmUsageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("profile_id") val profileId: String,
    @ColumnInfo("tool_name") val toolName: String,
    @ColumnInfo("model_id") val modelId: String,
    @ColumnInfo("input_tokens") val inputTokens: Int,
    @ColumnInfo("output_tokens") val outputTokens: Int,
    @ColumnInfo("total_tokens") val totalTokens: Int,
    @ColumnInfo("cost_usd_micros") val costUsdMicros: Long?,
    @ColumnInfo("duration_ms") val durationMs: Long,
    @ColumnInfo("error") val error: String?,
    @ColumnInfo("timestamp") val timestamp: Long,  // epoch millis
)
```

**Migration v7→v8:** AutoMigrationSpec добавляет таблицу.

**UsageRecorder port:**
```kotlin
interface UsageRecorder {
    suspend fun record(event: ToolUsageEvent)
}

data class ToolUsageEvent(
    val toolName: String,
    val modelId: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    val costUsdMicros: Long?,
    val durationMs: Long,
    val profileId: String,
    val error: String?,
    val timestamp: Instant,
)
```

**Pricing table (pure Kotlin, pricing last updated: 2026-09-07):**
```kotlin
object ModelPricing {
    val PRICE_PER_1M_INPUT_TOKENS = mapOf(
        "gpt-4o" to 2_500_000L,    // $2.50
        "gpt-4o-mini" to 150_000L,  // $0.15
        "claude-3-5-sonnet" to 3_000_000L,
        "claude-3-haiku" to 250_000L,
    )
    fun costMicros(modelId: String, inputTokens: Int, outputTokens: Int): Long? {
        val price = PRICE_PER_1M_INPUT_TOKENS[modelId] ?: return null
        return (inputTokens * price + outputTokens * price) / 1_000_000
    }
}
```

**UsageExtractor** — вызывается после каждого LLM-вызова:
```kotlin
fun extract(
    metaInfo: ResponseMetaInfo?,
    toolName: String,
    modelId: String,
    durationMs: Long,
    error: String?,
    profileId: String,
    timestamp: Instant,
): ToolUsageEvent
```

**AI Usage Screen** (Settings → AI Usage):
- Header card: total tokens, total cost, date range
- Daily sparkline (7 days)
- Per-tool breakdown: table of tool → calls, tokens, cost
- Per-model breakdown: table of model → calls, tokens, cost
- CSV export button

## Rationale

- **Profile + scoped userId** — готовая изоляция без отдельных userId; Foreign key в TaskEntity не меняется
- **Room для usage** — уже есть Room KMP, не нужен отдельный storage
- **DataStore для active profile** — быстрый sync, survives restart
- **Pricing table в commonMain** — pure Kotlin, можно тестировать без Android
- **AI Usage screen** — пользователь видит куда уходят токены

## Consequences

- Room schema v8 с `llm_usage` table + `profiles` table
- `ProfileAwareCurrentUser` инжектится во все write-tools
- AI Usage screen в Settings
- ZCode подключается с `--profile=ai-agent` → все операции в профиле ai-agent
- 4 ADR entries created + DIGEST.md refreshed

## Links

- Koog `ResponseMetaInfo`: `ai.koog.prompts.ResponseMetaInfo`
- Room KMP: `androidx.room:room-runtime:3.0.0`
- DataStore: `androidx.datastore:datastore-preferences`
- Related: `2026-09-07-dogfooding-mcp-server.md`, `2026-09-05-llm-provider-settings.md`
