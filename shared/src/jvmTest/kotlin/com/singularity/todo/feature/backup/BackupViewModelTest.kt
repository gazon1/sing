package com.singularity.todo.feature.backup

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.backup.BackupFileNamer
import com.singularity.todo.core.backup.BackupId
import com.singularity.todo.core.backup.BackupManifest
import com.singularity.todo.core.backup.BackupMetadata
import com.singularity.todo.core.backup.BackupRepository
import com.singularity.todo.core.backup.BackupResult
import com.singularity.todo.core.backup.DefaultBackupFileNamer
import com.singularity.todo.core.backup.EntityCounts
import com.singularity.todo.core.backup.ExportOptions
import com.singularity.todo.core.backup.ImportOptions
import com.singularity.todo.core.backup.RestoreResult
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.feature.tasks.UserId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Test double for [BackupRepository] that records calls and returns configurable results. */
class RecordingBackupRepository : BackupRepository {
    private val _backups = MutableStateFlow<List<BackupMetadata>>(emptyList())
    override val backups: Flow<List<BackupMetadata>> = _backups.asStateFlow()

    var exportResult: Result<BackupResult>? = null
    var importResult: Result<RestoreResult>? = null
    var deleteResult: Result<Unit> = Result.success(Unit)
    var pushResult: Result<String> = Result.success("https://remote/backup.zip")

    var lastExportOptions: ExportOptions? = null
    var lastImportOptions: ImportOptions? = null
    var lastDeletedId: BackupId? = null
    var lastPushedId: BackupId? = null

    fun addBackup(backup: BackupMetadata) {
        _backups.value = _backups.value + backup
    }

    override suspend fun export(options: ExportOptions): Result<BackupResult> {
        lastExportOptions = options
        return exportResult ?: Result.failure(NotImplementedError("export not configured"))
    }

    override suspend fun import(options: ImportOptions): Result<RestoreResult> {
        lastImportOptions = options
        return importResult ?: Result.failure(NotImplementedError("import not configured"))
    }

    override suspend fun delete(backupId: BackupId): Result<Unit> {
        lastDeletedId = backupId
        _backups.value = _backups.value.filter { it.id != backupId }
        return deleteResult
    }

    override suspend fun push(backupId: BackupId): Result<String> {
        lastPushedId = backupId
        return pushResult
    }

    override suspend fun pull(remoteRef: String, destPath: String): Result<Unit> =
        Result.failure(NotImplementedError())
}

@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {

    private val testUserId = UserId("test-user")
    private fun fakeAuth(session: Session = Session.Anonymous(testUserId)) =
        FakeAuthRepository(session)

    private val testManifest = BackupManifest(
        formatVersion = 1,
        appName = "Singularity",
        appVersion = "1.0.0",
        createdAtEpochMillis = 0L,
        userIdHash = "testhash",
        schemaVersion = 1,
        entityCounts = EntityCounts(tasks = 0, notes = 0, projects = 0),
        payloadChecksum = "abc123",
    )

    private val testEntityCounts = EntityCounts(tasks = 5, notes = 2, projects = 1)

    private fun createVm(
        repo: RecordingBackupRepository,
        auth: FakeAuthRepository,
        scope: CoroutineScope,
    ): BackupViewModel {
        val namer: BackupFileNamer = object : BackupFileNamer {
            override fun nextBackupName(timestampMs: Long): String = "test_backup.zip"
        }
        return BackupViewModel(repo, auth, namer, com.singularity.todo.core.platform.Clock, scope)
    }

    // ─── init subscribes to backups ───────────────────────────────────────────

    @Test
    fun `init subscribes to backups flow`() = runTest {
        val repo = RecordingBackupRepository()
        repo.addBackup(BackupMetadata(BackupId("b1"), "path/b1.zip", 0L, 1024L, null))
        val vm = createVm(repo, fakeAuth(), backgroundScope)
        advanceUntilIdle(); testScheduler.runCurrent(); testScheduler.runCurrent()
        assertEquals(1, vm.state.value.backups.size)
    }

    // ─── createBackup ─────────────────────────────────────────────────────────

    @Test
    fun `createBackup calls repository export`() = runTest {
        val repo = RecordingBackupRepository()
        repo.exportResult = Result.success(
            BackupResult(testManifest, "test.zip", 1024L)
        )
        val vm = createVm(repo, fakeAuth(), backgroundScope)
        advanceUntilIdle(); testScheduler.runCurrent(); testScheduler.runCurrent()

        vm.createBackup()
        advanceUntilIdle(); testScheduler.runCurrent(); testScheduler.runCurrent()

        assertNotNull(repo.lastExportOptions)
        assertEquals(testUserId, repo.lastExportOptions?.userId)
    }

    @Test
    fun `createBackup sets lastBackup on success`() = runTest {
        val repo = RecordingBackupRepository()
        repo.exportResult = Result.success(
            BackupResult(testManifest.copy(entityCounts = testEntityCounts), "test.zip", 2048L)
        )
        val vm = createVm(repo, fakeAuth(), backgroundScope)
        advanceUntilIdle(); testScheduler.runCurrent()

        vm.createBackup()
        advanceUntilIdle(); testScheduler.runCurrent()

        assertNotNull(vm.state.value.lastBackup)
        assertEquals(2048L, vm.state.value.lastBackup?.byteSize)
        assertEquals(8, vm.state.value.lastBackup?.entityCount) // 5+2+1
    }

    @Test
    fun `createBackup emits error event on failure`() = runTest {
        val repo = RecordingBackupRepository()
        repo.exportResult = Result.failure(RuntimeException("disk full"))
        val vm = createVm(repo, fakeAuth(), backgroundScope)
        advanceUntilIdle(); testScheduler.runCurrent()

        // Collect events in background so emit() has an active collector
        var errorMessage: String? = null
        backgroundScope.launch { vm.events.collect { e -> if (e is BackupUiEvent.Error) errorMessage = e.message } }
        advanceUntilIdle(); testScheduler.runCurrent()

        vm.createBackup()
        advanceUntilIdle(); testScheduler.runCurrent(); testScheduler.runCurrent()

        assertEquals("disk full", errorMessage)
    }

    // ─── import ───────────────────────────────────────────────────────────────

    @Test
    fun `import calls repository import`() = runTest {
        val repo = RecordingBackupRepository()
        repo.importResult = Result.success(
            RestoreResult(testManifest, testEntityCounts, 0, emptyList())
        )
        val vm = createVm(repo, fakeAuth(), backgroundScope)
        advanceUntilIdle(); testScheduler.runCurrent()

        vm.import("/path/to/backup.zip")
        advanceUntilIdle(); testScheduler.runCurrent()

        assertNotNull(repo.lastImportOptions)
        assertEquals("/path/to/backup.zip", repo.lastImportOptions?.sourcePath)
    }

    // ─── delete ───────────────────────────────────────────────────────────────

    @Test
    fun `delete calls repository delete`() = runTest {
        val repo = RecordingBackupRepository()
        repo.addBackup(BackupMetadata(BackupId("b1"), "path/b1.zip", 0L, 512L, null))
        val vm = createVm(repo, fakeAuth(), backgroundScope)
        advanceUntilIdle(); testScheduler.runCurrent()

        vm.delete(BackupId("b1"))
        advanceUntilIdle(); testScheduler.runCurrent()

        assertEquals(BackupId("b1"), repo.lastDeletedId)
        assertEquals(0, vm.state.value.backups.size)
    }

    // ─── push ─────────────────────────────────────────────────────────────────

    @Test
    fun `push calls repository push`() = runTest {
        val repo = RecordingBackupRepository()
        repo.addBackup(BackupMetadata(BackupId("b1"), "path/b1.zip", 0L, 512L, null))
        val vm = createVm(repo, fakeAuth(), backgroundScope)
        advanceUntilIdle(); testScheduler.runCurrent()

        vm.push(BackupId("b1"))
        advanceUntilIdle(); testScheduler.runCurrent()

        assertEquals(BackupId("b1"), repo.lastPushedId)
    }
}
