package com.singularity.todo.core.backup

import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.platform.TimeConstants
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlin.time.Clock

/**
 * Repository for local backup management and remote backup push/pull.
 *
 * Local backups are zip files scanned from `backupDir` every 5 seconds via [observeAll].
 * Remote operations use [RemoteBackupService] for upload/download.
 */
interface BackupRepository {
    /**
     * Emits the list of local backups, rescanned every 5 seconds.
     * List is sorted newest-first by `createdAtEpochMillis`.
     */
    fun observeAll(): Flow<List<BackupMetadata>>

    /**
     * Creates a local backup using auto-generated file name and default settings.
     * Used by auto-backup background job.
     */
    suspend fun createBackup(): Result<BackupResult>

    /**
     * Exports a full backup (tasks, notes, projects, tags, settings) to a zip in `backupDir`.
     *
     * [ExportOptions.destPath] is a **file name**, not a path: the repository owns the
     * backup directory and joins the two. A caller that passes a path instead writes
     * outside the backup directory — on Android that is the read-only root, and the
     * export fails with `EROFS`.
     */
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
    suspend fun pull(
        remoteRef: String,
        destPath: String,
    ): Result<Unit>

    /**
     * The archives that live in the cloud, for this profile.
     *
     * Push without a way back is half a feature: the person who uploaded an archive
     * has no way to fetch it again, and the app never says why.
     */
    suspend fun listRemote(): Result<List<RemoteBackup>>

    /**
     * Restores an archive that lives in the cloud.
     *
     * The archive is downloaded into the backup directory and then imported. The
     * repository owns that directory — a caller that named the destination could aim
     * the download anywhere — and the two steps belong together: an archive that was
     * downloaded and not imported is a file nobody asked for.
     */
    suspend fun restoreFromCloud(remoteRef: String): Result<RestoreResult>
}

class BackupRepositoryImpl(
    private val exporter: ArchiveExporter,
    private val importer: ArchiveImporter,
    private val remoteService: RemoteBackupService,
    private val fs: FileSystem,
    private val backupDir: String,
    private val currentUser: ProfileAwareCurrentUser,
    private val clock: Clock,
) : BackupRepository {
    override fun observeAll(): Flow<List<BackupMetadata>> =
        flow {
            while (currentCoroutineContext().isActive) {
                emit(scanBackups())
                delay(TimeConstants.BackupRefreshIntervalMs)
            }
        }

    override suspend fun createBackup(): Result<BackupResult> {
        val timestamp = clock.now().toEpochMilliseconds()
        val fileName = "singularity_backup_$timestamp.zip"
        val userId = currentUser.scopedUserId.value
        return export(ExportOptions(userId = userId, destPath = fileName))
    }

    private suspend fun scanBackups(): List<BackupMetadata> {
        val entries =
            fs
                .listDir(backupDir)
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

    override suspend fun export(options: ExportOptions): Result<BackupResult> =
        runCatchingCancellable {
            fs.ensureDir(backupDir)
            val destPath = resolveBackupDestination(backupDir, options.destPath)
            exporter.export(options.copy(destPath = destPath)).getOrThrow()
        }

    override suspend fun import(options: ImportOptions): Result<RestoreResult> = importer.import(options)

    override suspend fun delete(backupId: BackupId): Result<Unit> =
        runCatchingCancellable {
            // Find by ID from backups list
            val backup = scanBackups().find { it.id == backupId }
            if (backup != null) {
                fs.delete(backup.path)
            }
        }

    override suspend fun push(backupId: BackupId): Result<String> =
        runCatchingCancellable {
            val backup =
                scanBackups().find { it.id == backupId }
                    ?: throw BackupError.FileNotFound(backupId.value)
            remoteService.upload(backup.path, currentUser.scopedUserId.value).getOrThrow()
        }

    override suspend fun pull(
        remoteRef: String,
        destPath: String,
    ): Result<Unit> = remoteService.download(remoteRef, destPath, currentUser.scopedUserId.value)

    override suspend fun listRemote(): Result<List<RemoteBackup>> =
        runCatchingCancellable {
            remoteService.list(currentUser.scopedUserId.value).getOrThrow().map { stored ->
                RemoteBackup(
                    ref = stored.path,
                    name = stored.path.substringAfterLast('/'),
                    updatedAtEpochMillis = stored.updatedAtEpochMillis,
                    sizeBytes = stored.sizeBytes,
                )
            }
        }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun restoreFromCloud(remoteRef: String): Result<RestoreResult> =
        runCatchingCancellable {
            // The name comes from the ref, and only the name: a ref is reduced to its
            // last segment, so the download cannot aim outside the backup directory
            // and cannot write a file under a name the bucket made up.
            val fileName = remoteRef.substringAfterLast('/')
            fs.ensureDir(backupDir)
            val destPath = resolveBackupDestination(backupDir, fileName)
            remoteService.download(remoteRef, destPath, currentUser.scopedUserId.value).getOrThrow()
            try {
                importer.import(
                    ImportOptions(
                        sourcePath = destPath,
                        targetUserId = currentUser.scopedUserId.value,
                    ),
                ).getOrThrow()
            } catch (error: Exception) {
                // The archive was fetched and could not be read: a file nobody asked
                // for, and it would sit in the list of the backups this person can
                // restore from as if it were one of theirs. It goes away with the
                // failure that brought it.
                runCatchingCancellable { fs.delete(destPath) }
                throw error
            }
        }
}

/**
 * Resolves a backup **file name** to its full path inside [backupDir].
 *
 * The repository owns the backup directory, so no caller can aim the archive
 * outside it: a path that arrives here is rejected rather than followed. That
 * rejection is what turns "the export silently wrote to the filesystem root" —
 * which on Android is a read-only `/`, and fails with `EROFS` — into a loud,
 * explainable failure.
 *
 * @throws IllegalArgumentException when [fileName] is blank or carries a path
 *   separator.
 */
internal fun resolveBackupDestination(backupDir: String, fileName: String): String {
    require(fileName.isNotBlank()) { "a backup needs a file name" }
    require('/' !in fileName && '\\' !in fileName) {
        "a backup destination is a file name inside the backup directory, not a path: $fileName"
    }
    return "$backupDir/$fileName"
}
