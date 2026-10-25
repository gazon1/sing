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
     * Pushes a local backup to remote storage, encrypted under [password].
     *
     * What lands in the bucket is ciphertext: the archive is sealed on the device
     * and the key is the person's password, which the server never sees.
     *
     * The call **consumes** the password: the array is wiped before it returns, so
     * a failure does not leave the secret lying in memory for the next attempt.
     * A person who has to try again types it again.
     *
     * @return the remote reference (URL or path) on success.
     */
    suspend fun push(backupId: BackupId, password: CharArray): Result<String>

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
     *
     * The archive is encrypted in the bucket and is opened here, under [password].
     * The password is consumed the way [push] consumes it.
     */
    suspend fun restoreFromCloud(remoteRef: String, password: CharArray): Result<RestoreResult>
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

    override suspend fun push(backupId: BackupId, password: CharArray): Result<String> =
        try {
            runCatchingCancellable {
                val backup =
                    scanBackups().find { it.id == backupId }
                        ?: throw BackupError.FileNotFound(backupId.value)
                remoteService
                    .upload(backup.path, currentUser.scopedUserId.value, password)
                    .getOrThrow()
            }
        } finally {
            // The secret leaves with the call, whatever the call answered. The
            // service wipes it too — this is the path that never reaches the
            // service, the archive was not there to be encrypted.
            password.fill('\u0000')
        }

    override suspend fun listRemote(): Result<List<RemoteBackup>> =
        runCatchingCancellable {
            remoteService.list(currentUser.scopedUserId.value).getOrThrow().map { stored ->
                RemoteBackup(
                    ref = stored.path,
                    // The name the person reads is the archive's own name, not the
                    // bucket's: the object carries the `.enc` that says it is
                    // sealed, and that is the archive's business, not theirs.
                    name = RemoteBackupService.localFileName(stored.path),
                    updatedAtEpochMillis = stored.updatedAtEpochMillis,
                    sizeBytes = stored.sizeBytes,
                )
            }
        }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun restoreFromCloud(remoteRef: String, password: CharArray): Result<RestoreResult> =
        try {
            runCatchingCancellable {
                // The name comes from the ref, and only the name: a ref is reduced to its
                // last segment, so the download cannot aim outside the backup directory
                // and cannot write a file under a name the bucket made up. The suffix
                // that says "this is sealed" is dropped — the file that lands here is
                // the archive again, and the importer wants a zip.
                val fileName = RemoteBackupService.localFileName(remoteRef)
                fs.ensureDir(backupDir)
                val destPath = resolveBackupDestination(backupDir, fileName)
                remoteService
                    .download(remoteRef, destPath, currentUser.scopedUserId.value, password)
                    .getOrThrow()
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
        } finally {
            password.fill('\u0000')
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
