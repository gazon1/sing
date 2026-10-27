package com.singularity.todo.core.backup

import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.platform.TimeConstants
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
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

    /**
     * Lets this device seal its own archives, so that a scheduled run needs no person.
     *
     * ## What it does
     *
     * The password is asked **once**, and it does not stay: it wraps the device
     * key, and the wrapped key is what travels in every archive from then on.
     * The password itself is wiped before the call returns — storing it would
     * remove the very property the encryption exists for.
     *
     * The archive that is already in the cloud is **sealed again** under the new
     * password and pushed once more, because the copy that is there carries the
     * block it was written with. The device holds the key, so it can write a copy
     * that opens with the password the person just typed; what it cannot do is
     * reach into the cloud and change the copy that is already there. The
     * archives that were pushed earlier keep asking for the password they were
     * made with, and the screen says so.
     *
     * ## What it does not do
     *
     * It does not change what a manual push does. It says: *this device may seal
     * archives on its own, and the person who owns the account can still open
     * them with their password.*
     */
    suspend fun authorizeScheduledBackup(password: CharArray): Result<ScheduledAuthorization>

    /**
     * Whether this device may seal archives on its own.
     *
     * The answer is what the scheduled run asks before it does anything: a
     * device that was never authorized has no key to seal with, and asking it
     * to push anyway would produce an archive nobody can open.
     */
    suspend fun isScheduledBackupAuthorized(): Boolean

    /**
     * Pushes the newest local archive, sealed under this device's key.
     *
     * No password is involved: the archive is sealed under the key that
     * [authorizeScheduledBackup] wrapped, and the person who restores it still
     * needs their own password to unwrap that key out of the container.
     *
     * Fails when the device has not been authorized — a scheduled run that
     * cannot open its own archives is not a backup, and saying so is better
     * than pushing something nobody can read.
     */
    suspend fun pushScheduled(): Result<String>
}

/**
 * What authorizing this device did.
 *
 * ## Why the answer has three cases
 *
 * The password wraps the device key, and the archives that are already in the
 * cloud carry the block they were sealed with — the old one. So authorizing the
 * device again with a new password does not reach into the cloud: what it can do
 * is seal the newest local archive again, under the new password, and push that.
 *
 * The three cases are the three things that can happen, and the person is told
 * which one did: the archive in the cloud now opens with the new password, there
 * was no archive to seal again, or the archive in the cloud still needs the old
 * password. A screen that said only "this device can now back up on its own"
 * would leave the second and the third cases looking like the first.
 */
sealed interface ScheduledAuthorization {

    /** The archive in the cloud was sealed again, and opens with the new password. */
    data class Repushed(val remoteRef: String) : ScheduledAuthorization

    /** There was no local archive to seal again. */
    data class Authorized(val note: String? = null) : ScheduledAuthorization

    /** The archive in the cloud was not sealed again; it still needs the old password. */
    data class AuthorizedButNotRepushed(val reason: String) : ScheduledAuthorization
}

class BackupRepositoryImpl(
    private val exporter: ArchiveExporter,
    private val importer: ArchiveImporter,
    private val remoteService: RemoteBackupService,
    private val fs: FileSystem,
    private val backupDir: String,
    private val currentUser: ProfileAwareCurrentUser,
    private val clock: Clock,
    /**
     * The key a device seals with when no person is there.
     */
    private val deviceKeyStore: DeviceKeyStore,
    /**
     * Where the backup keeps its own bookkeeping.
     *
     * A push has to know which changes follow the archive it uploads — that is
     * what the bookkeeping holds — and a restore has to know what the archive
     * it fetched was. The bookkeeping is device-local and never uploaded: see
     * [BackupCursorStore].
     */
    private val cursors: BackupCursorStore,
    /**
     * Where the archive's own work happens.
     *
     * An export reads every row out of the database, serializes them and zips the
     * result; an import does the same in the other direction. Both are arithmetic
     * over the whole account, and the caller is the ViewModel — so without this
     * the screen stops answering while a backup is being written.
     *
     * The dispatcher is a parameter rather than a constant so a test can hand in a
     * thread of its own and see where the work ran.
     */
    private val ioDispatcher: CoroutineDispatcher,
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
        withContext(ioDispatcher) {
            runCatchingCancellable {
                fs.ensureDir(backupDir)
                val destPath = resolveBackupDestination(backupDir, options.destPath)
                exporter.export(options.copy(destPath = destPath)).getOrThrow()
            }
        }

    override suspend fun import(options: ImportOptions): Result<RestoreResult> =
        withContext(ioDispatcher) { importer.import(options) }

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
                        ?: throw BackupStorageError.FileNotFound(backupId.value)
                remoteService
                    .upload(
                        chainFor(backup.path),
                        currentUser.scopedUserId.value,
                        ArchiveSealer.UnderPassword(password),
                    ).getOrThrow()
            }
        } finally {
            // The secret leaves with the call, whatever the call answered. The
            // service wipes it too — this is the path that never reaches the
            // service, the archive was not there to be encrypted.
            password.fill('\u0000')
        }

    /**
     * What a push carries: the archive, and the changes that follow it.
     *
     * The chain is the base the changes were written against and the changes
     * themselves — so a push of an archive in the middle of a chain carries the
     * base of that chain, not the archive. The name in the bucket is still the
     * archive's own: it is the one the person pushed and the one they will see
     * in the list.
     *
     * When the chain cannot be assembled — the base is gone, or a change is —
     * the archive goes up alone. A push of a whole snapshot is not what the
     * person asked for, but it is a backup, and the alternative is a failure
     * over bookkeeping.
     */
    private suspend fun chainFor(archivePath: String): RemoteArchive {
        val name = archivePath.substringAfterLast('/')
        val state = cursors.load(currentUser.scopedUserId.value)
        val base = state.baseArchivePath
        val index = state.entries.indexOfFirst { it.archivePath == archivePath }
        if (base == null || index < 0 || !fs.exists(base)) {
            return RemoteArchive(basePath = archivePath, name = name)
        }
        val changes = state.entries.take(index + 1)
        if (changes.any { !fs.exists(it.deltaPath) }) {
            return RemoteArchive(basePath = archivePath, name = name)
        }
        return RemoteArchive(
            basePath = base,
            entries = changes.map { it.deltaPath },
            name = name,
        )
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
                val downloaded =
                    remoteService
                        .download(remoteRef, destPath, currentUser.scopedUserId.value, password)
                        .getOrThrow()
                try {
                    importer.import(
                        ImportOptions(
                            sourcePath = destPath,
                            targetUserId = currentUser.scopedUserId.value,
                            entries = downloaded.entries,
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

    override suspend fun authorizeScheduledBackup(password: CharArray): Result<ScheduledAuthorization> =
        try {
            runCatchingCancellable {
                // The password is asked once: it wraps the device key, and the
                // wrapped key is what travels from now on. The password itself
                // is not kept — see the interface's note on why.
                deviceKeyStore.prepare(password)
                if (scanBackups().isEmpty()) {
                    return@runCatchingCancellable ScheduledAuthorization.Authorized()
                }
                val pushed = runCatchingCancellable { pushScheduled().getOrThrow() }
                pushed.fold(
                    onSuccess = { ref -> ScheduledAuthorization.Repushed(ref) },
                    // The device is authorized either way — the key is wrapped and
                    // the next scheduled run will use it. What failed is the
                    // sealing of the archive that is already in the cloud, and
                    // that copy keeps asking for the old password.
                    onFailure = { error ->
                        ScheduledAuthorization.AuthorizedButNotRepushed(
                            error.message ?: "the archive was not sealed again",
                        )
                    },
                )
            }
        } finally {
            password.fill('\u0000')
        }

    override suspend fun isScheduledBackupAuthorized(): Boolean = deviceKeyStore.keyBlock() != null

    override suspend fun pushScheduled(): Result<String> =
        withContext(ioDispatcher) {
            runCatchingCancellable {
                // No block means no authorized device: the archive would be
                // sealed under a key nobody can unwrap, and a backup that
                // cannot be restored is not a backup.
                val block = deviceKeyStore.keyBlock()
                    ?: throw BackupStorageError.DeviceNotAuthorized("push backups on its own")
                val backup = scanBackups().firstOrNull()
                    ?: throw BackupStorageError.FileNotFound("there is no backup to push")
                val deviceKey = deviceKeyStore.deviceKey()
                try {
                    // The same chain a person pushes by hand: the base and the
                    // changes that follow it. The scheduled run differs in one
                    // thing — the key it seals under.
                    remoteService
                        .upload(
                            chainFor(backup.path),
                            currentUser.scopedUserId.value,
                            ArchiveSealer.UnderDeviceKey(deviceKey, block),
                        ).getOrThrow()
                } finally {
                    deviceKey.fill(0)
                }
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
