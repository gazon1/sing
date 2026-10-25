package com.singularity.todo.core.backup

import com.singularity.todo.core.auth.SupabaseClientProvider
import com.singularity.todo.core.error.TransportException
import com.singularity.todo.core.files.ByteSink
import com.singularity.todo.core.files.ByteSource
import com.singularity.todo.core.transport.SupabaseTransportFailure
import io.github.jan.supabase.storage.UploadData
import io.github.jan.supabase.storage.storage
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive

/**
 * The bucket, over Supabase Storage.
 *
 * ## Why this file is a vendor seam
 *
 * The SDK is what knows the bucket's REST shape, the upsert header and the way a
 * storage error is parsed. Everything above it — [BackupBucket] and
 * [RemoteBackupService] — is a project interface, and the feature code talks to
 * those. This file is one of the places where the vendor's types appear, and it is
 * declared as such in `VendorSdkConfinementTest`.
 *
 * ## Why the session is the identity
 *
 * The upload is made through the client the app already authenticated, so the SDK
 * attaches the signed-in user's token to the request. A hand-built request would
 * have to carry an access token around and set an `Authorization` header by hand,
 * and the day the token expires the request would arrive as a stranger — which for
 * a bucket with row-level security means the upload is refused.
 *
 * That is not hypothetical: the client this asks for has to be the *same* client the
 * sign-in ran against, or the session is in an instance nobody else can reach.
 * [SupabaseClientProvider] is where that is guaranteed.
 *
 * ## Why the failure is translated here
 *
 * A vendor exception carries the vendor's own shape, and the code above cannot read
 * it: the difference between "your session was refused" and "the policy refused
 * this write" has to survive the trip up, or the crash reporter groups two defects
 * under one key. The translation happens at the seam, where the vendor's types are
 * still in sight.
 */
class SupabaseBackupBucket(private val clientProvider: SupabaseClientProvider) : BackupBucket {

    /**
     * The upload, in pieces.
     *
     * The SDK takes a channel and a size, so the archive is fed into the channel
     * rather than handed over: a push of a large account must not turn the whole
     * account into one array in the app's memory.
     *
     * The producer and the request run together — the request reads the channel
     * while the producer fills it, and both end when the archive is over.
     */
    override suspend fun upload(path: String, source: ByteSource, sizeBytes: Long) {
        translating("the archive could not be uploaded") {
            val client = requireClient()
            val channel = ByteChannel(autoFlush = true)
            coroutineScope {
                // The producer and the request are siblings: whoever fails first
                // cancels the other, and the failure that killed the scope is the
                // one the caller hears. Catching it here would only hide which of
                // the two halves broke — the file that could not be read, or the
                // bucket that refused the write.
                launch {
                    val buffer = ByteArray(CHUNK_BYTES)
                    while (true) {
                        val read = source.read(buffer, 0, buffer.size)
                        if (read < 0) break
                        channel.writeFully(buffer, 0, read)
                    }
                    // The end of the archive is the end of the channel: the request
                    // is reading the length it was promised, and the promise ends here.
                    channel.close()
                }
                client.storage.from(BUCKET_ID)
                    .upload(path, UploadData(channel, sizeBytes)) { upsert = true }
            }
        }
    }

    /**
     * The download, in pieces.
     *
     * The SDK writes into a channel and the consumer reads it out into the sink,
     * so the archive travels from the bucket to the file without ever being whole
     * in memory.
     */
    override suspend fun download(path: String, sink: ByteSink) {
        translating("the archive could not be downloaded") {
            val client = requireClient()
            val channel = ByteChannel(autoFlush = true)
            coroutineScope {
                // Same shape as the upload: the consumer and the request are
                // siblings, and a failure in either cancels the other.
                launch {
                    val buffer = ByteArray(CHUNK_BYTES)
                    while (true) {
                        val read = channel.readAvailable(buffer, 0, buffer.size)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                    }
                }
                client.storage.from(BUCKET_ID).downloadAuthenticated(path, channel)
                // The SDK closes the channel when the body is over; closing it
                // again is harmless and keeps the consumer from waiting for an
                // end that a future SDK version might not send.
                channel.close()
            }
        }
    }

    override suspend fun list(prefix: String): List<BucketObject> = translating("the archives could not be listed") {
        val client = requireClient()
        client.storage.from(BUCKET_ID).list(prefix).map { file ->
            BucketObject(
                path = file.name,
                updatedAtEpochMillis = file.updatedAt?.toEpochMilliseconds(),
                // The size lives in the object's metadata rather than in a field of its
                // own, and the vendor hands the metadata over as raw JSON — so the read
                // of it is this file's business, and a bucket that does not report a
                // size reports none rather than a wrong one.
                sizeBytes = file.metadata?.get(SIZE_KEY)?.jsonPrimitive?.content?.toLongOrNull(),
            )
        }
    }

    /**
     * The vendor's failure, in the project's words.
     *
     * Everything that is not a project failure is the vendor's, and the vendor's
     * exceptions are what carry the status code the classification needs.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> translating(what: String, block: suspend () -> T): T = try {
        block()
    } catch (error: TransportException) {
        throw error
    } catch (error: RemoteBackupException) {
        throw error
    } catch (error: Throwable) {
        throw SupabaseTransportFailure.toTransportException(error, what)
    }

    private suspend fun requireClient() = clientProvider.client() ?: throw RemoteBackupException(
        "No cloud storage is configured. Add a Supabase project in the Account settings first.",
    )

    companion object {
        /** The bucket the archives go into. It has to exist in the project. */
        const val BUCKET_ID = "backups"

        /** How much of the archive travels at a time. */
        private const val CHUNK_BYTES = 64 * 1024

        /** The metadata key the bucket reports an object's size under. */
        private const val SIZE_KEY = "size"
    }
}
