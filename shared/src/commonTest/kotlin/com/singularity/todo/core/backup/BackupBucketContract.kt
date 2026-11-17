package com.singularity.todo.core.backup

import com.singularity.todo.core.error.TransportException
import com.singularity.todo.core.error.TransportFailure
import com.singularity.todo.core.files.ByteArraySink
import com.singularity.todo.core.files.ByteArraySource
import kotlinx.coroutines.CancellationException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * What every backup bucket has to do, said once.
 *
 * ## Why this is a suite and not a test
 *
 * The port hides the object store behind three calls and says nothing about what
 * the calls *mean*. The decisions worth making — does an upload replace, are the
 * bytes exact, is a listing scoped, what is a missing object, what is a failure,
 * what is a cancellation — were made once, by the adapter, and were invisible to
 * every test that used the fake instead: the fake could answer differently from
 * the adapter and nothing would have noticed.
 *
 * So the claims are here, as plain functions over a [BackupBucket], and the fake
 * and the real adapter are both put through the very same ones. A claim that
 * only one of them keeps is a defect in one of them, and it is named as such
 * instead of passing quietly.
 *
 * ## What is deliberately not claimed
 *
 * The order of a listing is not claimed: the bucket decides it, and a claim
 * about it would be a claim about one server's sort. The *paths* are claimed —
 * they come back exactly as they were given, with no bucket name and no prefix
 * the bucket invented — because a path is the reference a download takes, and a
 * path that changes shape between the upload and the listing is a reference that
 * cannot be used.
 *
 * What a path *means* is not claimed either: the port adds no user scope, and
 * resolves no "..". Who may write under which prefix is the caller's decision
 * and the bucket's policy — the port is the narrow slice between the two, and a
 * claim about it would be a claim about a decision that is made elsewhere.
 */
class BackupBucketContract(private val bucket: BackupBucket) {
    /**
     * The bytes that went up come back down, byte for byte.
     *
     * The payload is larger than the chunk the adapter reads at a time, so a
     * bucket that only ever moves the first piece cannot pass this.
     */
    suspend fun roundTripsStoredBytes(owner: String) {
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        bucket.upload("$owner/archive.enc", ByteArraySource(payload), payload.size.toLong())

        val sink = ByteArraySink()
        bucket.download("$owner/archive.enc", sink)

        assertContentEquals(payload, sink.bytes(), "the stored bytes must come back unchanged")
    }

    /** An archive of nothing is still an archive, and it comes back as nothing. */
    suspend fun roundTripsEmptyPayload(owner: String) {
        bucket.upload("$owner/archive.enc", ByteArraySource(ByteArray(0)), 0L)

        val sink = ByteArraySink()
        bucket.download("$owner/archive.enc", sink)

        assertContentEquals(ByteArray(0), sink.bytes(), "an empty object must come back empty")
    }

    /** A second push of the same archive leaves one archive, not two. */
    suspend fun uploadReplacesWhatWasThere(owner: String) {
        bucket.upload("$owner/archive.enc", ByteArraySource("first".encodeToByteArray()), 5)
        bucket.upload("$owner/archive.enc", ByteArraySource("second".encodeToByteArray()), 6)

        val sink = ByteArraySink()
        bucket.download("$owner/archive.enc", sink)

        assertContentEquals(
            "second".encodeToByteArray(),
            sink.bytes(),
            "the second upload must replace the first one",
        )
    }

    /**
     * A listing shows what is under the prefix, and nothing else.
     *
     * The prefix is what keeps one person's archives out of another person's
     * list, so an object outside it must not appear — and the paths that do
     * appear are the ones that were written, unchanged.
     */
    suspend fun listingIsScopedToThePrefix(owner: String) {
        bucket.upload("$owner/archive.enc", ByteArraySource(ByteArray(3)), 3)
        bucket.upload("someone-else/archive.enc", ByteArraySource(ByteArray(4)), 4)

        val listed = bucket.list("$owner/")

        assertEquals(
            listOf("$owner/archive.enc"),
            listed.map { it.path },
            "the listing must hold the objects under the prefix, with the paths as written",
        )
    }

    /**
     * An object that is not there is a failure the project knows how to read.
     *
     * The class of that failure is the implementation's own business — a bucket
     * that was told "there is nothing at this path" says so, and a server that
     * answers 404 says so in its own way — but it arrives as a
     * [TransportException], never as a vendor's exception or a bare crash.
     */
    suspend fun aMissingObjectIsAStorageFailure(owner: String) {
        val failure = failureOf { bucket.download("$owner/nope.enc", ByteArraySink()) }

        assertIs<TransportException>(failure, "a missing object must arrive as a classified failure")
    }

    /**
     * An upload that is not the size it was promised is refused, and nothing is
     * written.
     *
     * The size is what the request is lengthened with, so a body of another
     * length is a body the bucket was never promised — storing it would leave
     * the archive short, and the reader would find out at restore time.
     *
     * The class of the refusal is left to the implementation: the fake knows
     * the size it was given and refuses the write itself, while the server
     * refuses the request. What is claimed is that the write does not happen
     * and that the refusal arrives classified.
     *
     * "The write does not happen" is checked, not assumed: a bucket that stored
     * the short body and then complained would pass a claim about the error
     * alone, and the object under the path would be the damaged one. So the
     * object that was there is read back afterwards and has to be the one that
     * was put there.
     */
    suspend fun aWrongSizeIsRefused(owner: String) {
        val path = "$owner/archive.enc"
        val stored = "first".encodeToByteArray()
        bucket.upload(path, ByteArraySource(stored), stored.size.toLong())

        val failure = failureOf { bucket.upload(path, ByteArraySource(ByteArray(3)), 10L) }

        assertIs<TransportException>(failure, "an upload of the wrong size must be refused")

        val sink = ByteArraySink()
        bucket.download(path, sink)

        assertContentEquals(stored, sink.bytes(), "a refused upload must leave what was stored alone")
    }

    /**
     * A failure reaches the caller as the class it deserves.
     *
     * @param expected the class the failure belongs to.
     * @param trigger the call that fails.
     */
    suspend fun aFailureIsAStorageFailure(
        expected: TransportFailure,
        trigger: suspend () -> Unit,
    ) {
        val failure = failureOf(trigger)

        val error = assertIs<TransportException>(failure, "a failure must arrive as a classified failure")
        assertEquals(expected, error.failure, "the failure must keep the class it belongs to")
    }

    /**
     * The failure the call made, or null when it made none.
     *
     * A cancellation is not a failure to report about: it goes back out the way
     * it came in, so a cancelled call can never pass for a call that failed.
     */
    private suspend fun failureOf(trigger: suspend () -> Unit): Throwable? =
        try {
            trigger()
            null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            failure
        }

    /**
     * A cancellation is not a storage failure.
     *
     * The person walked away from the screen, or the screen went away. That is
     * not a statement about the bucket, and reporting it as one would put a
     * cancellation into the crash reporter as "the archive could not be
     * uploaded".
     */
    suspend fun aCancellationIsNotAStorageFailure(trigger: suspend () -> Unit) {
        assertFailsWith<CancellationException> {
            trigger()
        }
    }
}
