package com.singularity.todo.core.backup

import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.ids.UserId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlin.time.Duration.Companion.milliseconds

/**
 * Repository for local backup management and remote backup push/pull.
 *
 * Local backups are zip files scanned from `backupDir` every 5 seconds via [observeAll].
 * Remote operations use [StubRemoteBackupService] for upload/download.
 */
interface BackupRepository {
    /**
     * Emits the list of local backups, rescanned every 5 seconds.
     * List is sorted newest-first by `createdAtEpochMillis`.
     */
    fun observeAll(): Flow<List<BackupMetadata>>

    /** Exports a full backup (tasks, notes, projects, tags, settings) to a zip in `backupDir`. */
    suspend fun export(options: ExportOptions): Result<BackupResult>

    /** Imports a backup zip, restoring all entities. */
    suspend fun import(options: ImportOptions): Result<RestoreResult>

    /** Deletes the local backup file with the given [BackupId]. */
    suspend fun delete(backupId: BackupId): Result<Unit>

    /**
     * Pushes a local backup to remote storage.
     * Returns the remote reference (URL or path) on success.
     */
    suspend fun push(backupId: BackupId): Result<String>

    /**
     * Pulls a backup from remote storage to `destPath`.
     * The caller is responsible for importing it via [import].
     */
    suspend fun pull(remoteRef: String, destPath: String): Result<Unit>
}

class BackupRepositoryImpl(
    private val exporter: BackupExporter,
    private val importer: BackupImporter,
    private val remoteService: StubRemoteBackupService,
    private val fs: FileSystem,
    private val backupDir: String,
) : BackupRepository {

    override fun observeAll(): Flow<List<BackupMetadata>> = flow {
        while (currentCoroutineContext().isActive) {
            emit(scanBackups())
            delay(5_000.milliseconds)
        }
    }

    private suspend fun scanBackups(): List<BackupMetadata> {
        val entries = fs.listDir(backupDir)
            .mapNotNull { path -> fs.stat(path) }
            .filter { !it.isDirectory && pathEndsWithZip(it.path) }
            .sortedByDescending { it.lastModifiedEpochMillis }
        return entries.map { stat ->
            BackupMetadata(
                id = BackupId.fromPath(stat.path),
                path = stat.path,
                createdAtEpochMillis = stat.lastModifiedEpochMillis,
                sizeBytes = stat.sizeBytes,
                entityCounts = null, // Parsed lazily on demand
            )
        }
    }

    private fun pathEndsWithZip(path: String): Boolean = path.endsWith(".zip", ignoreCase = true)

    override suspend fun export(options: ExportOptions): Result<BackupResult> = runCatching {
        fs.ensureDir(backupDir)
        exporter.export(options).getOrThrow()
    }

    override suspend fun import(options: ImportOptions): Result<RestoreResult> = importer.import(options)

    override suspend fun delete(backupId: BackupId): Result<Unit> = runCatching {
        // Find by ID from backups list
        val backup = scanBackups().find { it.id == backupId }
        if (backup != null) {
            fs.delete(backup.path)
        }
    }

    override suspend fun push(backupId: BackupId): Result<String> = runCatching {
        val backup = scanBackups().find { it.id == backupId }
            ?: throw BackupError.FileNotFound(backupId.value)
        remoteService.upload(backup.path, UserId.anonymous).getOrThrow()
    }

    override suspend fun pull(remoteRef: String, destPath: String): Result<Unit> =
        remoteService.download(remoteRef, destPath, UserId.anonymous)
}
