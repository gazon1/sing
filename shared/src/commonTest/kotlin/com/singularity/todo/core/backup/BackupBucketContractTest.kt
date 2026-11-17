package com.singularity.todo.core.backup

import com.singularity.todo.core.error.TransportException
import com.singularity.todo.core.error.TransportFailure
import com.singularity.todo.core.files.ByteArraySink
import com.singularity.todo.core.files.ByteArraySource
import com.singularity.todo.test.fakes.FakeBackupBucket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The backup bucket's contract, kept by the bucket the tests use.
 *
 * The claims themselves live in [BackupBucketContract] — they are the same
 * claims the real adapter is put through, so the fake cannot answer differently
 * from the adapter without a test going red.
 */
@Tag("fast")
class BackupBucketContractTest {
    private val bucket = FakeBackupBucket()
    private val contract = BackupBucketContract(bucket)

    @Test
    fun `the bucket round-trips the stored bytes`() =
        runTest {
            contract.roundTripsStoredBytes("user-1")
        }

    @Test
    fun `the bucket round-trips an empty payload`() =
        runTest {
            contract.roundTripsEmptyPayload("user-1")
        }

    @Test
    fun `the bucket replaces what was at the path`() =
        runTest {
            contract.uploadReplacesWhatWasThere("user-1")
        }

    @Test
    fun `the bucket lists only what is under the prefix`() =
        runTest {
            contract.listingIsScopedToThePrefix("user-1")
        }

    @Test
    fun `the bucket reports a missing object as a storage failure`() =
        runTest {
            contract.aMissingObjectIsAStorageFailure("user-1")
        }

    @Test
    fun `the bucket keeps the path the caller gave it`() =
        runTest {
            // The path is a key, not something to resolve: the bucket does not
            // scope it to a user, normalize it, or read a ".." in it. Who may write
            // under which prefix is the caller's decision and the bucket's policy —
            // the port itself adds nothing to the path it was handed.
            val path = "user-1/../other/archive.enc"
            bucket.upload(path, ByteArraySource(ByteArray(1)), 1)

            assertEquals(listOf(path), bucket.list("user-1/").map { it.path })
        }

    @Test
    fun `the bucket refuses an upload that is not the size it was promised`() =
        runTest {
            contract.aWrongSizeIsRefused("user-1")
        }

    @Test
    fun `the bucket refuses a wrong size as a rejection`() =
        runTest {
            contract.aFailureIsAStorageFailure(TransportFailure.REJECTED) {
                bucket.upload("user-1/archive.enc", ByteArraySource(ByteArray(3)), 10L)
            }
        }

    @Test
    fun `the bucket reports a missing object as missing`() =
        runTest {
            contract.aFailureIsAStorageFailure(TransportFailure.MISSING) {
                bucket.download("user-1/nope.enc", ByteArraySink())
            }
        }

    @Test
    fun `the bucket reports a storage failure as the failure it was`() =
        runTest {
            bucket.failure = TransportException(TransportFailure.NETWORK, "the socket gave up")

            contract.aFailureIsAStorageFailure(TransportFailure.NETWORK) {
                bucket.upload("user-1/archive.enc", ByteArraySource(ByteArray(1)), 1)
            }
        }

    @Test
    fun `the bucket propagates a cancellation`() =
        runTest {
            bucket.failure = CancellationException("the screen went away")

            contract.aCancellationIsNotAStorageFailure {
                bucket.upload("user-1/archive.enc", ByteArraySource(ByteArray(1)), 1)
            }
        }
}
