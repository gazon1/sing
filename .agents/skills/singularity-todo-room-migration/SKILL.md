---
name: singularity-todo-room-migration
description: Room 3 (androidx.room3:3.0.0) setup for KMP with the specific gotchas this project hit: EROFS when name is treated as relative path (must use Context.getDatabasePath), missing @ColumnInfo causes SQL validation failures, no autoMigrations needs fallbackToDestructiveMigration for dev. Use when configuring AppDatabase, adding entities, or fixing migration errors.
---

# Singularity TODO — Room 3 Setup

**Three problems this skill prevents:**

1. `EROFS (Read-only file system)` — `todo.db.lck: open failed: EROFS` at runtime (caught by checking logcat after install, NOT by `checkModules`)
2. `Room cannot find migration path` from old schema to current `version = N`
3. `Unresolved reference: AppDatabase` at JVM compile time when `@Database` lives in `androidMain`

All three have simple fixes below.

## Configuration

### `gradle/libs.versions.toml`

```toml
room = "3.0.0"
sqlite = "2.7.0"   # sqlite-bundled:2.7.0 (sqlite:3.0.0 does not exist)

androidx-room3-runtime = { module = "androidx.room3:room3-runtime", version.ref = "room" }
androidx-room3-compiler = { module = "androidx.room3:room3-compiler", version.ref = "room" }
androidx-room3-ktx = { module = "androidx.room3:room3-ktx", version.ref = "room" }
androidx-room3-testing = { module = "androidx.room3:room3-testing", version.ref = "room" }
androidx-sqlite = { module = "androidx.sqlite:sqlite", version.ref = "sqlite" }
androidx-sqlite-bundled = { module = "androidx.sqlite:sqlite-bundled", version.ref = "sqlite" }
```

### `shared/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)   // ← Room 3 KSP plugin
}

dependencies {
    add("kspAndroid", libs.androidx.room3.compiler)
    // NO kspJvm — Room JVM uses JdbcNotesStore (raw JDBC), no annotation processing
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

// Source set dependencies
commonMain.dependencies {
    implementation(libs.androidx.room3.runtime)
    implementation(libs.androidx.sqlite)
}
androidMain.dependencies {
    implementation(libs.androidx.room3.runtime)
    implementation(libs.androidx.sqlite.bundled)
}
jvmMain.dependencies {
    implementation(libs.androidx.room3.runtime)
    implementation(libs.androidx.sqlite)
}
jvmTest.dependencies {
    implementation(libs.androidx.room3.testing)
}
```

## Schema lives in commonMain

```kotlin
// shared/src/commonMain/kotlin/com/singularity/todo/core/database/AppDatabase.kt
@Database(
    entities = [TaskEntity::class, TaskTagCrossRef::class, NoteEntity::class,
                ProjectEntity::class, TagEntity::class, SyncOutboxEntity::class,
                AttachmentEntity::class, TaskReminderEntity::class],
    version = 4,
    exportSchema = true
)
@ConstructedBy(AppDatabaseCtor::class)
@ColumnTypeConverters(AttachmentConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    // ... all DAOs
}
```

```kotlin
// shared/src/commonMain/kotlin/com/singularity/todo/core/database/AppDatabaseCtor.kt
expect object AppDatabaseCtor : RoomDatabaseConstructor<AppDatabase>
```

```kotlin
// shared/src/jvmMain/kotlin/com/singularity/todo/core/database/AppDatabaseCtor.jvm.kt
actual object AppDatabaseCtor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase = error("JVM uses JdbcNotesStore instead")
}
```

## CRITICAL: `Context.getDatabasePath` for the DB name

**The bug that took an hour to track down:**

```kotlin
// ❌ WRONG — Room 3 KMP treats `name` as a relative path.
// On Android `user.dir = /` which is read-only, so Room throws:
//   "Unable to open database 'todo.db'. Was a proper path / name used
//    in Room's database builder?"
// Caused by: FileLock.lock trying to create todo.db.lck at the root.
Room.databaseBuilder<AppDatabase>(name = "todo.db")
    .setDriver(BundledSQLiteDriver())
    .build()

// ✅ CORRECT — pass absolute path inside the app's writable sandbox.
val dbPath = get<android.content.Context>().getDatabasePath("todo.db").absolutePath
Room.databaseBuilder<AppDatabase>(name = dbPath)
    .setDriver(BundledSQLiteDriver())
    .fallbackToDestructiveMigration(dropAllTables = true)
    .build()
```

The error appears as `Unable to open database 'todo.db'` in `TasksUiState.Error` text — NOT in logcat. Always look at the UI text on the device.

## Migration strategy for dev

```kotlin
Room.databaseBuilder<AppDatabase>(name = dbPath)
    .setDriver(BundledSQLiteDriver())
    // Dev-only: drops the on-device DB and recreates when schema version
    // changes and no migration is registered. Replace with explicit
    // addMigrations(...) before any production release.
    .fallbackToDestructiveMigration(dropAllTables = true)
    .build()
```

If you have `@AutoMigration` annotations on `@Database(autoMigrations = [...])`, you MUST also have `schemas/<db-class>/N.json` for **every** intermediate version. If you only have `4.json`, KSP can't generate the diff for `AutoMigration(3, 4)` and the build fails or Room throws at runtime.

For dev, just remove `autoMigrations = [...]` entirely and use `fallbackToDestructiveMigration()`.

## Snake_case column names — required for every field

```kotlin
@Entity
data class TaskEntity(
    @PrimaryKey @ColumnInfo("id") val id: String,
    @ColumnInfo("user_id") val userId: String,
    @ColumnInfo("title") val title: String,
    // ...
)

// Also for cross-ref tables:
@Entity(primaryKeys = ["task_id", "tag_id"])
data class TaskTagCrossRef(
    @ColumnInfo("task_id") val taskId: String,
    @ColumnInfo("tag_id") val tagId: String,
)
```

Room 3 validates SQL queries against entity column names strictly. If SQL uses `WHERE user_id = :uid` but `userId` has no `@ColumnInfo("user_id")`, build fails with `no such column: user_id`.

## TypeConverters are now `@ColumnTypeConverter`

```kotlin
// ❌ WRONG — Room 2 name
@TypeConverter
fun fromInstant(value: Instant?): Long? = value?.toEpochMilliseconds()

// ✅ CORRECT — Room 3 name (renamed to clarify scope)
@ColumnTypeConverter
fun fromInstant(value: Instant?): Long? = value?.toEpochMilliseconds()
```

And apply via `@ColumnTypeConverters(...)` on the `@Database` class, not `@TypeConverters`.

## Debugging checklist

When "DB won't open" on device:

1. **Check the UI text** — `Unable to open database 'X'` or `FileLock.lock: open failed: EROFS` → use `Context.getDatabasePath(...).absolutePath`
2. **Check logcat for `RoomDatabase` errors** — `no such column` → add missing `@ColumnInfo`
3. **Check `version` vs schema files** — `AppDatabase_Impl.kt` generation requires JSON schema at `schemas/<db-class>/<version>.json`
4. **Check `addMigrations(...)` vs `autoMigrations`** — for dev, drop both and use `fallbackToDestructiveMigration()`

## Cross-Process Concurrent Access (CLI + Android)

When the same SQLite file is opened by both the Android app and the MCP-server JVM CLI, additional setup is required. See `singularity-todo-room-multi-instance` for the full pattern.

Key points:

### enableMultiInstanceInvalidation() on Android

```kotlin
// shared/src/androidMain/.../core/di/PlatformModule.android.kt
Room.databaseBuilder<AppDatabase>(name = dbPath)
    .setDriver(BundledSQLiteDriver())
    .setSQLiteDatabaseConfigurationParameters(
        openInMemory = false,
        journalMode = OpenHelper.JOURNAL_MODE_WRITE_AHEAD_LOGGING,
    )
    .enableMultiInstanceInvalidation()   // ← CRITICAL for CLI ↔ Android cross-process
    .build()
```

**Requirement:** SQLite ≥ 3.38.0 (Android API 21+ uses bundled SQLite 3.38.2+). Android Room handles this automatically via `BundledSQLiteDriver`.

**What it does:** Room registers cross-process invalidation hooks. When the CLI writes to the same DB file, Android Room's `InvalidationTracker` wakes up and marks in-memory caches as stale. The next query fetches fresh data.

**Limitation:** works between Room instances on **Android only**. For JVM CLI → Android, use polling fallback (see `singularity-todo-room-multi-instance`).

### RowVersion — Cheap Insurance for Future Sync

Add `RowVersion` to Task and Note entities for optimistic locking (future use):

```kotlin
// In TaskEntity:
@ColumnInfo("row_version") val rowVersion: Int = 0,

// In NoteEntity:
@ColumnInfo("row_version") val rowVersion: Int = 0,
```

```kotlin
// Usage in UpdateTaskUseCase:
val updated = task.copy(
    updatedAt = clock.now(),
    rowVersion = task.rowVersion + 1,
)
repo.update(updated).getOrThrow()
```

**Rule:** always increment `rowVersion` on update. This enables optimistic locking: if two processes update the same entity, the second update can detect the version mismatch and retry or merge.

### LlmUsageEntity — Token Usage Tracking Table

Add `LlmUsageEntity` to AppDatabase for token observability:

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
    @ColumnInfo("cost_usd_micros") val costUsdMicros: Long?,
    @ColumnInfo("duration_ms") val durationMs: Long,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("error") val error: String?,
)
```

Migration v7→v8 is **additive** — adds `llm_usage` table only. No schema changes to existing tables.

See `singularity-todo-llm-usage-tracking` for the full usage tracking pattern.

### Migration(10, 11) — Add idempotency_key, Remove is_notebook

```kotlin
// shared/src/commonMain/.../core/database/Migrations.kt
// Room KSP auto-infers both changes from schema diff. No migrate() override needed.
@androidx.room3.DeleteColumn(tableName = "projects", columnName = "is_notebook")
class Migration10To11 : AutoMigrationSpec
```

**Why no migrate()?** Room KSP processes `@DeleteColumn` automatically and compares schema 10.json vs 11.json to infer the `idempotency_key` addition. Only add `migrate()` when extra SQL beyond what annotations can express is needed.

**SQLite version note:** Android 21+ uses bundled SQLite 3.38.2 which supports `ALTER TABLE ADD COLUMN` natively.
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL,
                name TEXT NOT NULL,
                description TEXT,
                color INTEGER NOT NULL DEFAULT 4284951163,
                icon TEXT,
                parent_id TEXT REFERENCES projects(id) ON DELETE SET NULL,
                is_archived INTEGER NOT NULL DEFAULT 0,
                sort_order INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                deleted_at INTEGER,
                idempotency_key TEXT UNIQUE
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO projects_v11
            SELECT id, user_id, name, description, color, icon, parent_id,
                   is_archived, sort_order, created_at, updated_at, deleted_at,
                   lower(hex(randomblob(16))) as idempotency_key
            FROM projects
        """.trimIndent())
        db.execSQL("DROP TABLE projects")
        db.execSQL("ALTER TABLE projects_v11 RENAME TO projects")
    }
}
```

The Room KSP plugin exports the new schema to `schemas/com.singularity.todo.core.database.AppDatabase/11.json`.

## Files

| File | Role |
|---|---|
| `shared/src/commonMain/.../core/database/AppDatabase.kt` | `@Database` declaration (commonMain!) |
| `shared/src/commonMain/.../core/database/AppDatabaseCtor.kt` | `expect object` |
| `shared/src/jvmMain/.../core/database/AppDatabaseCtor.jvm.kt` | `actual object` (stub or JDBC-based) |
| `shared/src/commonMain/.../core/database/Entities.kt` | `@Entity` classes with `@ColumnInfo` everywhere |
| `shared/src/commonMain/.../core/database/Migrations.kt` | `AutoMigrationSpec` objects (unused until prod) |
| `shared/schemas/<db-class>/<version>.json` | Auto-exported by Room 3 KSP plugin |
| `shared/src/androidMain/.../core/di/PlatformModule.android.kt` | Use `Context.getDatabasePath`, `enableMultiInstanceInvalidation()` |
| `shared/src/jvmMain/.../core/di/PlatformModule.jvm.kt` | Uses `JdbcNotesStore` (raw JDBC, no Room) |
