package com.singularity.todo.core.backup

import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.tasks.UserId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

interface BackupRepository {
    val backups: Flow<List<BackupMetadata>>
    suspend fun export(options: ExportOptions): Result<BackupResult>
    suspend fun import(options: ImportOptions): Result<RestoreResult>
    suspend fun delete(backupId: BackupId): Result<Unit>
    suspend fun push(backupId: BackupId): Result<String>
    suspend fun pull(remoteRef: String, destPath: String): Result<Unit>
}

class BackupRepositoryImpl(
    private val exporter: BackupExporter,
    private val importer: BackupImporter,
    private val remoteService: RemoteBackupService,
    private val fs: FileSystem,
    private val backupDir: String,
    private val clock: Clock
) : BackupRepository {

    override val backups: Flow<List<BackupMetadata>> = flow {
        while (coroutineContext.isActive) {
            emit(scanBackups())
            delay(5_000)
        }
    }

    private fun scanBackups(): List<BackupMetadata> {
        val dir = java.io.File(backupDir)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.extension == "zip" }
            ?.map { file ->
                BackupMetadata(
                    id = BackupId.fromPath(file.absolutePath),
                    path = file.absolutePath,
                    createdAtEpochMillis = file.lastModified(),
                    sizeBytes = file.length(),
                    entityCounts = null // Parsed lazily on demand
                )
            }
            ?.sortedByDescending { it.createdAtEpochMillis }
            ?: emptyList()
    }

    override suspend fun export(options: ExportOptions): Result<BackupResult> = runCatching {
        fs.ensureDir(backupDir)
        exporter.export(options).getOrThrow()
    }

    override suspend fun import(options: ImportOptions): Result<RestoreResult> =
        importer.import(options)

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
