package com.singularity.todo.feature.backup

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.backup.BackupId
import com.singularity.todo.core.backup.BackupManifest
import com.singularity.todo.core.backup.BackupMetadata
import com.singularity.todo.core.backup.BackupRepository
import com.singularity.todo.core.backup.BackupResult
import com.singularity.todo.core.backup.EntityCounts
import com.singularity.todo.core.backup.ExportOptions
import com.singularity.todo.core.backup.ImportOptions
import com.singularity.todo.core.backup.RestoreResult
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.feature.tasks.UserId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Test double for [BackupRepository] that records calls and returns configurable results.
 */
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

class BackupViewModelTest {

    private val testUserId = UserId("test-user")
    private fun fakeAuth(session: Session = Session.Anonymous(testUserId)) =
        FakeAuthRepository(session)

    private fun createVm(
        repo: RecordingBackupRepository,
        auth: FakeAuthRepository,
        scope: CoroutineScope? = null,
    ) = BackupViewModel(repo, auth, scope)

    private fun manifest(
        tasks: Int = 0,
        notes: Int = 0,
        projects: Int = 0,
    ) = BackupManifest(
        formatVersion = 1,
        appName = "Singularity",
        appVersion = "1.0.0",
        createdAtEpochMillis = System.currentTimeMillis(),
        userIdHash = "testhash",
        schemaVersion = 1,
        entityCounts = EntityCounts(tasks = tasks, notes = notes, projects = projects),
        payloadChecksum = "abc123",
    )

    // ─── init subscribes to backups ───────────────────────────────────────────

    @Test
    fun `init subscribes to backups flow`() = runTest {
        val repo = RecordingBackupRepository()
        repo.addBackup(BackupMetadata(
            id = BackupId.fromPath("/path/backup.zip"),
            path = "/path/backup.zip",
            createdAtEpochMillis = 1000,
            sizeBytes = 1024,
            entityCounts = null,
        ))
        val vm = createVm(repo, fakeAuth(), backgroundScope)
        advanceUntilIdle()

        assertEquals(1, vm.state.value.backups.size)
    }

    // ─── createBackup ─────────────────────────────────────────────────────────

    @Test
    fun `createBackup sets isWorking then clears on success`() = runTest {
        val repo = RecordingBackupRepository()
        repo.exportResult = Result.success(
            BackupResult(
                manifest = manifest(),
                destPath = "/path/backup.zip",
                byteSize = 1024,
            )
        )
        val vm = createVm(repo, fakeAuth(), backgroundScope)
        advanceUntilIdle()

        vm.createBackup()
        advanceUntilIdle()

        assertFalse(vm.state.value.isWorking)
        assertNotNull(vm.state.value.lastBackup)
        assertEquals(1024, vm.state.value.lastBackup!!.byteSize)
    }

    @Test
    fun `createBackup shows error on failure`() = runTest {
        val repo = RecordingBackupRepository()
        repo.exportResult = Result.failure(RuntimeException("Disk full"))
        val vm = createVm(repo, fakeAuth(), backgroundScope)
        advanceUntilIdle()

        vm.createBackup()
        advanceUntilIdle()

        assertFalse(vm.state.value.isWorking)
        assertTrue(vm.state.value.showError)
        assertEquals("Disk full", vm.state.value.error)
    }

    @Test
    fun `createBackup passes effectiveUserId to export`() = runTest {
        val repo = RecordingBackupRepository()
        repo.exportResult = Result.success(
            BackupResult(
                manifest = manifest(),
                destPath = "/path/backup.zip",
                byteSize = 1024,
            )
        )
        val auth = fakeAuth(Session.Anonymous(testUserId))
        val vm = createVm(repo, auth, backgroundScope)
        advanceUntilIdle()

        vm.createBackup()
        advanceUntilIdle()

        assertEquals(testUserId, repo.lastExportOptions?.userId)
    }

    // ─── export ──────────────────────────────────────────────────────────────

    @Test
    fun `export with custom path computes entity count`() = runTest {
        val repo = RecordingBackupRepository()
        repo.exportResult = Result.success(
            BackupResult(
                manifest = manifest(tasks = 5, notes = 3, projects = 2),
                destPath = "/custom.zip",
                byteSize = 4096,
            )
        )
        val vm = createVm(repo, fakeAuth(), backgroundScope)
        advanceUntilIdle()

        vm.export("/custom.zip")
        advanceUntilIdle()

        assertFalse(vm.state.value.isWorking)
        assertEquals("/custom.zip", vm.state.value.lastBackup?.destPath)
        assertEquals(4096, vm.state.value.lastBackup?.byteSize)
        assertEquals(10, vm.state.value.lastBackup?.entityCount)
    }

    // ─── delete ──────────────────────────────────────────────────────────────

    @Test
    fun `delete removes backup from list`() = runTest {
        val id = BackupId.fromPath("/path/backup.zip")
        val repo = RecordingBackupRepository()
        repo.addBackup(BackupMetadata(id, "/path/backup.zip", 1000, 1024, null))
        val vm = createVm(repo, fakeAuth(), backgroundScope)
        advanceUntilIdle()

        vm.delete(id)
        advanceUntilIdle()

        assertTrue(vm.state.value.backups.isEmpty())
        assertEquals(id, repo.lastDeletedId)
    }

    @Test
    fun `delete shows error on failure`() = runTest {
        val id = BackupId.fromPath("/path/backup.zip")
        val repo = RecordingBackupRepository()
        repo.addBackup(BackupMetadata(id, "/path/backup.zip", 1000, 1024, null))
        repo.deleteResult = Result.failure(RuntimeException("Permission denied"))
        val vm = createVm(repo, fakeAuth(), backgroundScope)
        advanceUntilIdle()

        vm.delete(id)
        advanceUntilIdle()

        assertTrue(vm.state.value.showError)
        assertEquals("Permission denied", vm.state.value.error)
    }

    // ─── push ─────────────────────────────────────────────────────────────────

    @Test
    fun `push clears isWorking on success`() = runTest {
        val id = BackupId.fromPath("/path/backup.zip")
        val repo = RecordingBackupRepository()
        repo.addBackup(BackupMetadata(id, "/path/backup.zip", 1000, 1024, null))
        repo.pushResult = Result.success("https://remote/backup.zip")
        val vm = createVm(repo, fakeAuth(), backgroundScope)
        advanceUntilIdle()

        vm.push(id)
        advanceUntilIdle()

        assertFalse(vm.state.value.isWorking)
        assertEquals(id, repo.lastPushedId)
    }

    @Test
    fun `push shows error on failure`() = runTest {
        val id = BackupId.fromPath("/path/backup.zip")
        val repo = RecordingBackupRepository()
        repo.addBackup(BackupMetadata(id, "/path/backup.zip", 1000, 1024, null))
        repo.pushResult = Result.failure(RuntimeException("Network error"))
        val vm = createVm(repo, fakeAuth(), backgroundScope)
        advanceUntilIdle()

        vm.push(id)
        advanceUntilIdle()

        assertFalse(vm.state.value.isWorking)
        assertTrue(vm.state.value.showError)
    }

    // ─── import ───────────────────────────────────────────────────────────────

    @Test
    fun `import clears isWorking on success`() = runTest {
        val repo = RecordingBackupRepository()
        repo.importResult = Result.success(
            RestoreResult(
                manifest = manifest(),
                entityCounts = EntityCounts(0, 0, 0),
                restoredAttachmentCount = 0,
                missingAttachmentIds = emptyList(),
            )
        )
        val vm = createVm(repo, fakeAuth(), backgroundScope)
        advanceUntilIdle()

        vm.import("/restore.zip")
        advanceUntilIdle()

        assertFalse(vm.state.value.isWorking)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `import shows error on failure`() = runTest {
        val repo = RecordingBackupRepository()
        repo.importResult = Result.failure(RuntimeException("Corrupt archive"))
        val vm = createVm(repo, fakeAuth(), backgroundScope)
        advanceUntilIdle()

        vm.import("/bad.zip")
        advanceUntilIdle()

        assertFalse(vm.state.value.isWorking)
        assertTrue(vm.state.value.showError)
        assertEquals("Corrupt archive", vm.state.value.error)
    }

    // ─── clearError ───────────────────────────────────────────────────────────

    @Test
    fun `clearError hides error state`() = runTest {
        val repo = RecordingBackupRepository()
        repo.exportResult = Result.failure(RuntimeException("Boom"))
        val vm = createVm(repo, fakeAuth(), backgroundScope)
        advanceUntilIdle()

        vm.createBackup()
        advanceUntilIdle()
        assertTrue(vm.state.value.showError)

        vm.clearError()
        assertFalse(vm.state.value.showError)
        assertNull(vm.state.value.error)
    }

    // ─── restore (stub) ───────────────────────────────────────────────────────

    @Test
    fun `restore is a no-op stub that does not crash`() = runTest {
        val repo = RecordingBackupRepository()
        val vm = createVm(repo, fakeAuth(), backgroundScope)
        advanceUntilIdle()

        vm.restore() // must not throw

        assertFalse(vm.state.value.isWorking)
    }
}
