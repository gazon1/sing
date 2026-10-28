
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.backup

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.backup.BackupId
import com.singularity.todo.core.backup.BackupManifest
import com.singularity.todo.core.backup.BackupMetadata
import com.singularity.todo.core.backup.BackupResult
import com.singularity.todo.core.backup.DefaultBackupFileNamer
import com.singularity.todo.core.backup.EntityCounts
import com.singularity.todo.core.backup.RemoteBackup
import com.singularity.todo.core.backup.RestoreResult
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.files.FileSource
import com.singularity.todo.core.files.FileSourceFactory
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.settings.SettingsExporter
import com.singularity.todo.core.settings.SettingsImporter
import com.singularity.todo.feature.backup.BackupUiEvent
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeBackupRepository
import com.singularity.todo.test.fakes.FakeFileSharePort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
class BackupViewModelTest {

    private val testUserId = UserId("test-user")
    private fun fakeAuth(session: Session = Session.Anonymous(testUserId)) = FakeAuthRepository(session)

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

    // Stub settings exporter / importer — tests here are about backup, not settings snapshot.
    private val stubSettingsExporter = object : SettingsExporter(emptySet()) {
        override suspend fun exportAsJson(): String = """{"schemaVersion":1}"""
    }
    private val stubSettingsImporter = object : SettingsImporter(emptySet()) {
        override suspend fun importFromJson(json: String): SettingsImporter.ImportResult =
            SettingsImporter.ImportResult.Success
    }

    /**
     * Default for [createVm]: reading a picked file is not what these tests exercise,
     * so the read fails loudly. A test that *does* exercise it passes its own factory
     * and asserts on the bytes.
     */
    private val unreadableFileSourceFactory = object : FileSourceFactory {
        override fun invoke(path: String): FileSource = object : FileSource {
            override suspend fun readBytes(): ByteArray = error("this test does not read files ($path)")
        }
    }

    private fun createVm(
        repo: FakeBackupRepository,
        auth: FakeAuthRepository,
        scope: CoroutineScope,
        fileSourceFactory: FileSourceFactory = unreadableFileSourceFactory,
    ): BackupViewModel {
        val namer = DefaultBackupFileNamer { _ -> "test_backup.zip" }
        return BackupViewModel(
            repository = repo,
            authRepository = auth,
            backupFileNamer = namer,
            clock = kotlin.time.Clock.System,
            settingsExporter = stubSettingsExporter,
            settingsImporter = stubSettingsImporter,
            fileSourceFactory = fileSourceFactory,
            fileSharePort = FakeFileSharePort(),
            scope = testScope(scope),
        )
    }

    // ─── init subscribes to backups ───────────────────────────────────────────

    @Test
    fun `init subscribes to backups flow`() = runTest {
        val repo = FakeBackupRepository()
        repo.addBackup(BackupMetadata(BackupId("b1"), "path/b1.zip", 0L, 1024L, null))
        val vm = createVm(repo, fakeAuth(), backgroundScope)
        advanceUntilIdle()
        testScheduler.runCurrent()
        testScheduler.runCurrent()
        assertEquals(1, vm.stateFlow.value.backups.size)
    }

    // ─── createBackup ─────────────────────────────────────────────────────────

    @Test
    fun `createBackup calls repository export`() = runTest {
        val repo = FakeBackupRepository()
        repo.exportResult = Result.success(BackupResult(testManifest, "test.zip", 1024L))
        val vm = createVm(repo, fakeAuth(), backgroundScope)
        advanceUntilIdle()
        testScheduler.runCurrent()
        testScheduler.runCurrent()

        vm.onIntent(BackupIntent.CreateBackup)
        advanceUntilIdle()
        testScheduler.runCurrent()
        testScheduler.runCurrent()

        assertNotNull(repo.lastExportOptions)
        // The identity is the repository's to fill — it is the one that knows the
        // signed-in user. The ViewModel owns the destination and the attachments.
        assertEquals("test_backup.zip", repo.lastExportOptions?.destPath)
        assertEquals(true, repo.lastExportOptions?.includeAttachments)
    }

    @Test
    fun `createBackup sets lastBackup on success`() = runTest {
        val repo = FakeBackupRepository()
        repo.exportResult = Result.success(
            BackupResult(testManifest.copy(entityCounts = testEntityCounts), "test.zip", 2048L),
        )
        val vm = createVm(repo, fakeAuth(), backgroundScope)
        advanceUntilIdle()
        testScheduler.runCurrent()

        vm.onIntent(BackupIntent.CreateBackup)
        advanceUntilIdle()
        testScheduler.runCurrent()

        assertNotNull(vm.stateFlow.value.lastBackup)
        assertEquals(2048L, vm.stateFlow.value.lastBackup?.byteSize)
        assertEquals(8, vm.stateFlow.value.lastBackup?.entityCount) // 5+2+1
    }

    @Test
    fun `createBackup emits snackbar on success`() = runTest {
        val repo = FakeBackupRepository()
        repo.exportResult = Result.success(BackupResult(testManifest, "test.zip", 1024L))
        val vm = createVm(repo, fakeAuth(), backgroundScope)
        advanceUntilIdle()
        testScheduler.runCurrent()

        // Collect events in background so the event channel has an active receiver
        var capturedSnackbar: String? = null
        backgroundScope.launch {
            vm.events.collect { e -> if (e is BackupUiEvent.ShowSnackbar) capturedSnackbar = e.message }
        }
        advanceUntilIdle()
        testScheduler.runCurrent()

        vm.onIntent(BackupIntent.CreateBackup)
        advanceUntilIdle()
        testScheduler.runCurrent()

        assertEquals("Backup created", capturedSnackbar)
    }

    @Test
    fun `createBackup emits error event on failure`() = runTest {
        val repo = FakeBackupRepository()
        repo.exportResult = Result.failure(RuntimeException("disk full"))
        val vm = createVm(repo, fakeAuth(), backgroundScope)
        advanceUntilIdle()
        testScheduler.runCurrent()

        // Collect events in background so emit() has an active collector
        var errorMessage: String? = null
        backgroundScope.launch { vm.events.collect { e -> if (e is BackupUiEvent.Error) errorMessage = e.message } }
        advanceUntilIdle()
        testScheduler.runCurrent()

        vm.onIntent(BackupIntent.CreateBackup)
        advanceUntilIdle()
        testScheduler.runCurrent()
        testScheduler.runCurrent()

        assertEquals("disk full", errorMessage)
    }

    // ─── import ───────────────────────────────────────────────────────────────

    @Test
    fun `import calls repository import`() = runTest {
        val repo = FakeBackupRepository()
        repo.importResult = Result.success(RestoreResult(testManifest, testEntityCounts, 0, emptyList()))
        val vm = createVm(repo, fakeAuth(), backgroundScope)
        advanceUntilIdle()
        testScheduler.runCurrent()

        vm.onIntent(BackupIntent.Restore("/path/to/backup.zip"))
        advanceUntilIdle()
        testScheduler.runCurrent()

        assertNotNull(repo.lastImportOptions)
        assertEquals("/path/to/backup.zip", repo.lastImportOptions?.sourcePath)
    }

    // ─── delete ───────────────────────────────────────────────────────────────

    @Test
    fun `delete calls repository delete`() = runTest {
        val repo = FakeBackupRepository()
        repo.addBackup(BackupMetadata(BackupId("b1"), "path/b1.zip", 0L, 512L, null))
        val vm = createVm(repo, fakeAuth(), backgroundScope)
        advanceUntilIdle()
        testScheduler.runCurrent()

        vm.onIntent(BackupIntent.Delete(BackupId("b1")))
        advanceUntilIdle()
        testScheduler.runCurrent()

        assertEquals(BackupId("b1"), repo.lastDeletedId)
        assertEquals(0, vm.stateFlow.value.backups.size)
    }

    // ─── push ─────────────────────────────────────────────────────────────────

    @Test
    fun `push calls repository push`() = runTest {
        val repo = FakeBackupRepository()
        repo.addBackup(BackupMetadata(BackupId("b1"), "path/b1.zip", 0L, 512L, null))
        val vm = createVm(repo, fakeAuth(), backgroundScope)
        advanceUntilIdle()
        testScheduler.runCurrent()

        vm.onIntent(BackupIntent.Push(BackupId("b1"), "correct horse".toCharArray()))
        advanceUntilIdle()
        testScheduler.runCurrent()

        assertEquals(BackupId("b1"), repo.lastPushedId)
        assertEquals(
            "correct horse",
            repo.lastPushedPassword,
            "the password the person typed has to reach the archive — it is the key",
        )
    }

    // ─── cloud backups ────────────────────────────────────────────────────────

    /**
     * The other half of the push: the archives in the cloud have to be listable.
     *
     * Push without a way back is half a feature. The person who uploaded an archive
     * could never fetch it again, and nothing anywhere said why.
     */
    @Test
    fun `the cloud archives reach the screen`() = runTest {
        val repo = FakeBackupRepository()
        repo.listRemoteResult = Result.success(listOf(RemoteBackup("user-1/b1.zip", "b1.zip")))
        val vm = createVm(repo, fakeAuth(), backgroundScope)
        advanceUntilIdle()
        testScheduler.runCurrent()

        vm.onIntent(BackupIntent.LoadRemoteBackups)
        advanceUntilIdle()
        testScheduler.runCurrent()

        assertEquals(1, vm.stateFlow.value.remoteBackups.size)
        assertEquals("b1.zip", vm.stateFlow.value.remoteBackups.first().name)
        assertTrue(
            vm.stateFlow.value.remoteBackupsLoaded,
            "the cloud was asked, and the answer has to be marked as an answer",
        )
    }

    /** A restore from the cloud asks the repository for that archive. */
    @Test
    fun `a restore from the cloud reaches the repository`() = runTest {
        val repo = FakeBackupRepository()
        repo.restoreFromCloudResult = Result.success(RestoreResult(testManifest, testEntityCounts, 0, emptyList()))
        val vm = createVm(repo, fakeAuth(), backgroundScope)
        advanceUntilIdle()
        testScheduler.runCurrent()

        vm.onIntent(BackupIntent.RestoreFromCloud("user-1/b1.zip", "correct horse".toCharArray()))
        advanceUntilIdle()
        testScheduler.runCurrent()

        assertEquals("user-1/b1.zip", repo.lastRestoredRemoteRef)
        assertEquals(
            "correct horse",
            repo.lastRestoredPassword,
            "the password the person typed has to reach the archive — it is the key",
        )
    }

    /**
     * A cloud that cannot be read is said out loud.
     *
     * The screen would otherwise show an empty list, which reads as "there is nothing
     * in the cloud" — a different answer from "the cloud could not be reached".
     */
    @Test
    fun `a cloud that cannot be read reports the failure`() = runTest {
        val repo = FakeBackupRepository()
        repo.listRemoteResult = Result.failure(IllegalStateException("the bucket is gone"))
        val vm = createVm(repo, fakeAuth(), backgroundScope)
        advanceUntilIdle()
        testScheduler.runCurrent()

        vm.onIntent(BackupIntent.LoadRemoteBackups)
        advanceUntilIdle()
        testScheduler.runCurrent()

        assertTrue(
            vm.stateFlow.value.remoteBackups.isEmpty(),
            "a failed read must not leave a list that looks like an answer",
        )
        assertFalse(vm.stateFlow.value.isWorking, "the screen must stop working after the failure")
    }

    // ─── settings import from a picked file ──────────────────────────────────

    /**
     * The path a user actually takes: the picker hands back a path (a `content://` URI
     * on Android), and the ViewModel — not the Composable — is what opens it. A screen
     * that read the file itself would break on SAF URIs.
     */
    @Test
    fun `ImportSettingsFrom reads the picked file and feeds the importer`() = runTest {
        var imported: String? = null
        val importer = object : SettingsImporter(emptySet()) {
            override suspend fun importFromJson(json: String): SettingsImporter.ImportResult {
                imported = json
                return SettingsImporter.ImportResult.Success
            }
        }
        val factory = object : FileSourceFactory {
            override fun invoke(path: String): FileSource = object : FileSource {
                override suspend fun readBytes(): ByteArray = """{"version":1}""".encodeToByteArray()
            }
        }
        val vm = BackupViewModel(
            repository = FakeBackupRepository(),
            authRepository = fakeAuth(),
            backupFileNamer = DefaultBackupFileNamer { "b.zip" },
            clock = kotlin.time.Clock.System,
            settingsExporter = stubSettingsExporter,
            settingsImporter = importer,
            fileSourceFactory = factory,
            fileSharePort = FakeFileSharePort(),
            scope = testScope(backgroundScope),
        )
        advanceUntilIdle()
        testScheduler.runCurrent()

        vm.onIntent(BackupIntent.ImportSettingsFrom("content://docs/1"))
        advanceUntilIdle()
        testScheduler.runCurrent()

        assertEquals("""{"version":1}""", imported, "the picked file's contents must reach the importer")
        assertEquals(false, vm.stateFlow.value.isWorking, "the working flag must clear when the import lands")
    }

    @Test
    fun `ImportSettingsFrom reports a read failure instead of crashing`() = runTest {
        val factory = object : FileSourceFactory {
            override fun invoke(path: String): FileSource = object : FileSource {
                override suspend fun readBytes(): ByteArray = throw java.io.FileNotFoundException(path)
            }
        }
        val vm = createVm(FakeBackupRepository(), fakeAuth(), backgroundScope, fileSourceFactory = factory)
        advanceUntilIdle()
        testScheduler.runCurrent()

        vm.onIntent(BackupIntent.ImportSettingsFrom("/nope/missing.json"))
        advanceUntilIdle()
        testScheduler.runCurrent()

        assertEquals(false, vm.stateFlow.value.isWorking, "a failed read must not leave the screen stuck on 'working'")
    }
}
