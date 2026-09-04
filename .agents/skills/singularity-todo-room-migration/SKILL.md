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

## Files

| File | Role |
|---|---|
| `shared/src/commonMain/.../core/database/AppDatabase.kt` | `@Database` declaration (commonMain!) |
| `shared/src/commonMain/.../core/database/AppDatabaseCtor.kt` | `expect object` |
| `shared/src/jvmMain/.../core/database/AppDatabaseCtor.jvm.kt` | `actual object` (stub or JDBC-based) |
| `shared/src/commonMain/.../core/database/Entities.kt` | `@Entity` classes with `@ColumnInfo` everywhere |
| `shared/src/commonMain/.../core/database/Migrations.kt` | `AutoMigrationSpec` objects (unused until prod) |
| `shared/schemas/<db-class>/<version>.json` | Auto-exported by Room 3 KSP plugin |
| `shared/src/androidMain/.../core/di/PlatformModule.android.kt` | Use `Context.getDatabasePath` |
| `shared/src/jvmMain/.../core/di/PlatformModule.jvm.kt` | Uses `JdbcNotesStore` (raw JDBC, no Room) |
