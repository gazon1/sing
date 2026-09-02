---
name: singularity-todo-backup
description: Backup/restore pattern for the Singularity Todo KMP app. Use whenever adding backup, restore, export, import, or zip compression features. Handles BackupExporter/BackupImporter orchestration, DSL builders with @DslMarker, sealed BackupError, Map-based migration chain, and BackupCodec platform implementations.
---

# singularity-todo-backup

Documents the backup/restore architecture for the Singularity Todo KMP Compose app (Android + JVM Desktop).

## Core Architecture

```
BackupExporter: DAOs → DTOs → JSON payload → manifest → BackupCodec.export()
BackupImporter:  BackupCodec.import() → validate → migrate → DAOs upsert
```

**Key files** (all under `shared/src/commonMain/kotlin/com/singularity/todo/`):
- `core/backup/BackupFormat.kt` — constants (FORMAT_VERSION=1, SCHEMA_VERSION=1, entry names)
- `core/backup/BackupManifest.kt` — `@Serializable` with computed `isCompatibleWith`
- `core/backup/BackupPayload.kt` — `@Serializable` wrapper with schemaVersion + all DTO lists
- `core/backup/BackupDtos.kt` — 6 DTOs: TaskDto, NoteDto, ProjectDto, TagDto, AttachmentDto, TaskTagDto. **Sync metadata stripped on toDto()**
- `core/backup/BackupDomain.kt` — pure: `sha256Hex()`, `extractUserIdHash()`, `buildManifest()`, `validateManifest()`
- `core/backup/BackupMigrations.kt` — `Map<Int, (JsonObject) → JsonObject>` chain via `fold()`
- `core/backup/BackupError.kt` — sealed class: UnsupportedFormatVersion, UnsupportedSchemaVersion, ChecksumMismatch, MalformedManifest, FileNotFound, CodecError
- `core/backup/BackupCodec.kt` — interface with `export()` / `import()`, `CodecReadResult` data class
- `core/backup/BackupOptions.kt` — `@DslMarker` + `ExportOptionsBuilder` / `ImportOptionsBuilder` with nullable `var userId: UserId?` (value classes can't be lateinit)
- `core/backup/BackupRepository.kt` — interface, `backups: Flow<List<BackupMetadata>>` polls every 5s
- `core/backup/BackupExporter.kt` — orchestrator: DAOs → DTOs → JSON → manifest → codec.export()
- `core/backup/BackupImporter.kt` — orchestrator: codec.import() → validate → migrate → DAOs upsert
- `core/backup/RemoteBackupService.kt` — interface + `StubRemoteBackupService` (returns local path)
- `feature/backup/BackupViewModel.kt` — StateFlow with export/import/delete/push, uses `AuthDomain.effectiveUserId()`
- `feature/backup/BackupScreen.kt` — Scaffold with TopAppBar, buttons, LazyColumn of BackupListItem, SnackbarHost

**Platform-specific** (jvmMain / androidMain):
- `core/backup/JvmBackupCodec.kt` — `java.util.zip.ZipOutputStream` / `ZipInputStream`
- `core/backup/AndroidBackupCodec.kt` — `java.util.zip` (same API, minSdk 24)

## DSL Builders Pattern

```kotlin
// Usage:
repository.export(exportOptions {
    userId = currentUserId
    destPath = "/backups/backup.zip"
    includeAttachments = true
})

// Builder definition (BackupOptions.kt):
@DslMarker annotation class BackupDsl

@BackupDsl class ExportOptionsBuilder {
    var userId: UserId? = null  // nullable — value class can't be lateinit
    var destPath: String? = null
    var includeAttachments: Boolean = true
    var appVersion: String = "0.0.11"
    fun build() = ExportOptions(error("userId required"), error("destPath required"), includeAttachments, appVersion)
}

fun exportOptions(block: ExportOptionsBuilder.() -> Unit): ExportOptions =
    ExportOptionsBuilder().apply(block).build()
```

## Sealed Error Handling

```kotlin
// In UI (exhaustive when):
when (val result = repository.import(opts)) {
    is Result.success -> showSuccess()
    is Result.failure -> when (val err = result.exceptionOrNull()) {
        is BackupError.UnsupportedFormatVersion -> showFormatError(err.version)
        is BackupError.UnsupportedSchemaVersion -> showSchemaError(err.version)
        is BackupError.ChecksumMismatch -> showChecksumError()
        is BackupError.MalformedManifest -> showMalformedError()
        is BackupError.FileNotFound -> showFileNotFoundError()
        is BackupError.CodecError -> showCodecError()
    }
}
```

## Migration Chain

```kotlin
// BackupMigrations.kt:
object BackupMigrations {
    val CURRENT = BackupFormat.SCHEMA_VERSION
    private val migrations: Map<Int, (JsonObject) → JsonObject> = emptyMap() // add as: migrations[1] = { obj -> migrateV1toV2(obj) }

    fun migrate(payload: JsonObject, from: Int, to: Int = CURRENT): JsonObject =
        (from until to).fold(payload) { acc, v -> migrations[v]?.invoke(acc) ?: acc }
}
```

## DTO vs Entity

DTOs strip sync metadata on `toDto()`. Sync fields restored as defaults on `toEntity()`:
```kotlin
fun TaskEntity.toDto(): TaskDto = TaskDto(
    id, title, description, dueDate, dueTime, isPinned, completedAt,
    archivedAt, someday, projectId, createdAt, updatedAt
    // serverVersion, syncStatus, hlc — NOT included (defaults on restore)
)
```

## java.util.zip (NOT Kompress)

Both JVM and Android use `java.util.zip` — stable, well-tested, available since API 1 on Android and standard on JVM:
```kotlin
// Export:
val baos = ByteArrayOutputStream()
ZipOutputStream(baos).use { zos ->
    zos.putNextEntry(ZipEntry(BackupFormat.ENTRY_MANIFEST))
    zos.write(manifestBytes); zos.closeEntry()
    zos.putNextEntry(ZipEntry(BackupFormat.ENTRY_PAYLOAD))
    zos.write(payloadBytes); zos.closeEntry()
    for ((name, data) in attachments) {
        zos.putNextEntry(ZipEntry(BackupFormat.DIR_ATTACHMENTS + name))
        zos.write(data); zos.closeEntry()
    }
}
fs.writeBytes(destPath, baos.toByteArray())

// Import:
ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
    var entry: ZipEntry? = zis.nextEntry
    while (entry != null) {
        val data = zis.readBytes()
        when (entry.name) {
            BackupFormat.ENTRY_MANIFEST -> manifest = data
            BackupFormat.ENTRY_PAYLOAD -> payload = data
            else -> if (entry.name.startsWith(BackupFormat.DIR_ATTACHMENTS))
                attachments[entry.name.removePrefix(BackupFormat.DIR_ATTACHMENTS)] = data
        }
        entry = zis.nextEntry
    }
}
```

## DI Wiring

`BackupCodec` is passed as a constructor parameter into `sharedModule()` — NOT instantiated inside DI:
```kotlin
// Android MainActivity:
val backupCodec = AndroidBackupCodec()
startKoin { modules(sharedModule(db, settings, null, attachmentsDir, backupDir, fs, backupCodec)) }

// Desktop main.kt:
val backupCodec = JvmBackupCodec()
startKoin { modules(sharedModule(jvmDatabase, settings, null, attachmentsDir, backupDir, fs, backupCodec)) }
```

## MapFileSystem for Mock-Free Tests

```kotlin
val fs = MapFileSystem()
val codec = JvmBackupCodec()
// write → read → verify identical bytes
```

## BackupRepository Interface

```kotlin
interface BackupRepository {
    val backups: Flow<List<BackupMetadata>>  // polls every 5s
    suspend fun export(options: ExportOptions): Result<BackupResult>
    suspend fun import(options: ImportOptions): Result<RestoreResult>
    suspend fun delete(backupId: BackupId): Result<Unit>
    suspend fun push(backupId: BackupId): Result<String>  // stub → returns local path
    suspend fun pull(remoteRef: String, destPath: String): Result<Unit>
}
```

## Dual Versioning

- `FORMAT_VERSION` — zip container format (currently 1)
- `SCHEMA_VERSION` — payload schema (currently 1)
- `validateManifest()` checks: formatVersion ≤ current, schemaVersion ≤ current, payload checksum
