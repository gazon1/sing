---
title: "Sync scheduling abstraction: SyncScheduler + DataStoreSyncPrefs + RemoteConfig + SecureStorage"
date: 2026-09-23
status: accepted
tags: [sync, architecture, core, scheduling, remote-config, persistence]
---

## Context

Tier 1 introduced `SyncScheduler` as a `NoOpSyncScheduler` stub and `InMemorySyncPrefs`. This ADR replaces both with production implementations and adds the `RemoteConfig` entity for Supabase connection settings.

## Decision

### 1. `SyncScheduler` — platform-specific scheduling

`SyncScheduler` is a **commonMain interface** (not expect/actual class — per project convention, `expect` is reserved for `object`/`val`/`fun` primitives):

```kotlin
// shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncScheduler.kt
interface SyncScheduler {
    fun schedule(interval: Duration)
    fun cancel()
}
```

**Android implementation** (`androidMain`):
- Uses `WorkManager.PeriodicWorkRequestBuilder<SyncWorker>`
- Unique work name `"com.singularity.todo.sync.SyncWorker"` with `ExistingPeriodicWorkPolicy.UPDATE`
- `OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST` for fairness
- Input: `IS_AUTO_SYNC = true/false` (manual vs scheduled)
- `WorkManager.getInstance(context).enqueueUniquePeriodicWork(...)`

**JVM implementation** (`jvmMain`):
- Uses `CoroutineScope.launch { while(isActive) { syncOnce(); delay(interval) } }`
- Same `createBackgroundScope()` that `SyncRunner` uses — consistent lifecycle

Both are bound as `single<SyncScheduler> { PlatformSyncScheduler(...) }` in respective `platformModule()`.

### 2. `DataStoreSyncPrefs` — replacing `InMemorySyncPrefs`

Backed by `DataStore<Preferences>` with keys in `SyncPrefs.Keys`:

```kotlin
// shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncPrefs.kt
interface SyncPrefs {
    val autoSyncEnabled: Boolean
    val enabledTriggers: Set<SyncTrigger>
    val scheduledInterval: Duration
    val lastSuccessfulSyncAt: Long?   // epoch millis
    val lastLsn: Long                // last server log sequence number

    fun setAutoSyncEnabled(value: Boolean)
    fun setEnabledTriggers(triggers: Set<SyncTrigger>)
    fun setScheduledInterval(interval: Duration)
    suspend fun recordSuccessfulSync()
    suspend fun setLastLsn(lsn: Long)
}

class DataStoreSyncPrefs(
    private val dataStore: DataStore<Preferences>,
) : SyncPrefs {
    companion object Keys {
        val AUTO_SYNC_ENABLED = booleanPreferencesKey("sync_auto_sync_enabled")
        val ENABLED_TRIGGERS = stringSetPreferencesKey("sync_enabled_triggers")
        val SCHEDULED_INTERVAL_MINUTES = intPreferencesKey("sync_interval_minutes")
        val LAST_SUCCESSFUL_SYNC_AT = longPreferencesKey("sync_last_successful_at")
        val LAST_LSN = longPreferencesKey("sync_last_lsn")
    }

    override val autoSyncEnabled: Boolean
        get() = dataStore.data.value[AUTO_SYNC_ENABLED] ?: false

    override val enabledTriggers: Set<SyncTrigger>
        get() = dataStore.data.value[ENABLED_TRIGGERS]
            ?.mapNotNull { runCatching { SyncTrigger.valueOf(it) }.getOrNull() }
            ?.toSet()
            ?: SyncTrigger.entries.toSet()

    override val scheduledInterval: Duration
        get() = (dataStore.data.value[SCHEDULED_INTERVAL_MINUTES] ?: 30).minutes

    override val lastSuccessfulSyncAt: Long?
        get() = dataStore.data.value[LAST_SUCCESSFUL_SYNC_AT]

    override val lastLsn: Long
        get() = dataStore.data.value[LAST_LSN] ?: 0L

    override fun setAutoSyncEnabled(value: Boolean) {
        dataStore.update { it[AUTO_SYNC_ENABLED] = value }
    }

    override fun setEnabledTriggers(triggers: Set<SyncTrigger>) {
        dataStore.update { it[ENABLED_TRIGGERS] = triggers.map { it.name }.toSet() }
    }

    override fun setScheduledInterval(interval: Duration) {
        dataStore.update { it[SCHEDULED_INTERVAL_MINUTES] = interval.inWholeMinutes.toInt() }
    }

    override suspend fun recordSuccessfulSync() {
        dataStore.edit { it[LAST_SUCCESSFUL_SYNC_AT] = System.currentTimeMillis() }
    }

    override suspend fun setLastLsn(lsn: Long) {
        dataStore.edit { it[LAST_LSN] = lsn }
    }
}
```

`InMemorySyncPrefs` is removed (replaced by `FakeSyncPrefs` for tests only).

### 3. `RemoteConfig` — Room entity for Supabase connection

```kotlin
// shared/src/commonMain/kotlin/com/singularity/todo/core/sync/RemoteConfigEntity.kt
@Entity(tableName = "sync_remote_config")
data class RemoteConfigEntity(
    @PrimaryKey val id: String = "default",
    val baseUrl: String,
    val projectId: String,
    val anonKey: String? = null,    // stored in SecureStoragePort, not here
    val encryptAtRest: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

// shared/src/commonMain/kotlin/com/singularity/todo/core/sync/RemoteConfigDao.kt
@Dao
interface RemoteConfigDao {
    @Query("SELECT * FROM sync_remote_config WHERE id = 'default' LIMIT 1")
    fun watch(): Flow<RemoteConfigEntity?>

    @Query("SELECT * FROM sync_remote_config WHERE id = 'default' LIMIT 1")
    suspend fun get(): RemoteConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RemoteConfigEntity)

    @Query("DELETE FROM sync_remote_config WHERE id = 'default'")
    suspend fun delete()
}
```

### 4. AutoMigration DB 15 → 16

```kotlin
// In AppDatabase.kt
entities = [
    // ... existing ...
    RemoteConfigEntity::class,
],
version = 16,
autoMigrations = [
    // ... existing migrations ...
    AutoMigration(from = 15, to = 16),
]
```

### 5. Supabase credentials via `SecureStoragePort`

Credentials (anon key, access token, refresh token) are stored via `SecureStoragePort`, **not in Room**.

```kotlin
// In RemoteConfigRepositoryImpl:
class RemoteConfigRepositoryImpl(
    private val dao: RemoteConfigDao,
    private val secureStorage: SecureStoragePort,
    private val prefs: SyncPrefs,
) : RemoteConfigRepository {

    suspend fun testConnection(config: RemoteConfigEntity, anonKey: String): ConnectionResult {
        return try {
            val response = httpClient.get("$baseUrl/rest/v1/?apikey=$anonKey")
            if (response.ok) ConnectionResult.Success()
            else ConnectionResult.Error("HTTP ${response.statusCode}")
        } catch (e: Throwable) {
            ConnectionResult.Error(e.localizedMessage ?: "Connection failed")
        }
    }
}
```

### 6. `SyncPrefs` injected into `SyncEngine`

To make `pull()` use `prefs.lastLsn` (Tier 3 requirement), `SyncEngine` constructor gains a `prefs: SyncPrefs` parameter:

```kotlin
internal class SyncEngine(
    private val log: Logger,
    private val api: SyncApiClient,
    private val authRepository: AuthRepository,
    private val outboxDao: SyncOutboxDao,
    private val hlcFactory: HlcFactory,
    private val idGenerator: IdGenerator,
    private val prefs: SyncPrefs,       // NEW
    private val scope: AutoCloseableCoroutineScope,
) : AutoCloseable by scope {
```

DI binding in `coreModule()`:
```kotlin
single { SyncEngine(..., get(), get(), get(), get(), get(), get(), AutoCloseableCoroutineScope(...)) }
```

## Architecture (updated)

```
SyncPrefs (DataStore) ──→ SyncEngine.pull() ──→ uses lastLsn for incremental pull
                        ──→ SyncRunner ──→ uses scheduledInterval
SecureStoragePort ──→ RemoteConfigRepository ──→ stores anon key / tokens
RemoteConfigDao ──→ RemoteConfigRepository ──→ stores baseUrl / projectId
SyncScheduler (WorkManager/delay-loop) ──→ SyncRunner.startScheduledSync()
```

## Consequences

- **Positive**: Persistent `lastLsn` enables incremental pull — server sends only new events.
- **Positive**: `autoSyncEnabled` and `scheduledInterval` survive app restarts.
- **Positive**: Supabase credentials never touch Room — `SecureStoragePort` is hardware-backed on both platforms.
- **Positive**: `DataStoreSyncPrefs` follows the exact same pattern as `DataStoreSessionStore` — consistent with project.
- **Negative**: Schema migration 15→16 required. AutoMigration handles it automatically.
- **Negative**: `SyncEngine` constructor grows from 7 to 8 parameters. Mitigated by Koin named parameters at call site.

## Links

- `core/sync/SyncScheduler.kt` — interface
- `core/sync/SyncPrefs.kt` — `DataStoreSyncPrefs` implementation
- `core/sync/RemoteConfigEntity.kt` — Room entity
- `core/sync/RemoteConfigDao.kt` — DAO
- `core/sync/RemoteConfigRepository.kt` — interface
- `core/sync/RemoteConfigRepositoryImpl.kt` — implementation
- `core/security/SecureStoragePort.kt` — credential storage
- Orgzly reference: `orgzly-android-revived/app/src/main/java/com/orgzly/android/sync/AutoSyncScheduler.kt`
