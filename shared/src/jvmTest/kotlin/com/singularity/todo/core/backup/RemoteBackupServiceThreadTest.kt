package com.singularity.todo.core.backup

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.security.JvmArchiveCrypto
import com.singularity.todo.test.fakes.MapFileSystem
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The archive's work does not run on the thread that asked for it.
 *
 * ## What this is the only test for
 *
 * Sealing an archive is slow on purpose: 210 000 rounds of PBKDF2, and then the
 * cipher walks the whole archive. The caller is the ViewModel, so before the
 * dispatcher was introduced the screen a person was looking at stopped answering
 * while their backup was being encrypted — and the freeze grew with the archive,
 * which grows with the account.
 *
 * A test that says "the upload is fast" would not have caught that: it passes on a
 * fast machine and fails on a slow one, and it says nothing about *where* the work
 * happened. So the dispatcher is a constructor parameter, and this test hands in a
 * thread with a name of its own and checks where the upload actually ran.
 */
@Tag("fast")
class RemoteBackupServiceThreadTest {

    /** A bucket that remembers which thread it was called on. */
    private class ThreadRecordingBucket : BackupBucket {
        var uploadThread: String? = null
            private set

        override suspend fun upload(path: String, bytes: ByteArray) {
            uploadThread = Thread.currentThread().name
        }

        override suspend fun download(path: String): ByteArray = ByteArray(0)

        override suspend fun list(prefix: String): List<BucketObject> = emptyList()
    }

    /** The upload runs on the dispatcher's thread, not on the caller's. */
    @Test
    fun `the upload does not run on the caller's thread`() = runTest {
        val fs = MapFileSystem()
        fs.writeBytes("/backups/archive.zip", "the archive".encodeToByteArray())
        val bucket = ThreadRecordingBucket()
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "backup-io")
        }.asCoroutineDispatcher()

        try {
            val service = RemoteBackupServiceImpl(bucket, fs, EncryptedArchive(JvmArchiveCrypto()), dispatcher)
            val caller = Thread.currentThread().name

            val result = service.upload("/backups/archive.zip", UserId("user-1"), "horse".toCharArray())

            assertTrue(result.isSuccess, "the upload must succeed: ${result.exceptionOrNull()}")
            assertNotEquals(
                caller,
                bucket.uploadThread,
                "the archive must not be encrypted on the thread that asked for it",
            )
            assertEquals("backup-io", bucket.uploadThread, "the work must run on the dispatcher's thread")
        } finally {
            dispatcher.close()
        }
    }
}
