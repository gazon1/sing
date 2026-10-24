package com.singularity.todo.core.backup

import com.singularity.todo.core.auth.SupabaseClientProvider
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.TransportException
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.error.transportError
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.transport.SupabaseTransportFailure
import io.github.jan.supabase.storage.storage

/**
 * The remote half of the backup feature, over a Supabase Storage bucket.
 *
 * ## Why this file is a vendor seam
 *
 * The SDK is what knows the bucket's REST shape, the upsert header and the way a
 * storage error is parsed. Everything above it — [RemoteBackupService] — is a
 * project interface, and the feature code talks to that. This file is the one
 * place where the vendor's types appear, and it is declared as such in
 * `VendorSdkConfinementTest`.
 *
 * ## Why the session is the identity
 *
 * The upload is made through the client the app already authenticated, so the SDK
 * attaches the signed-in user's token to the request. A hand-built request would
 * have to carry an access token around and set an `Authorization` header by hand,
 * and the day the token expires the request would arrive as a stranger — which
 * for a bucket with row-level security means the upload is refused.
 *
 * That is not hypothetical: the client this service asks for has to be the *same*
 * client the sign-in ran against, or the session is in an instance nobody else can
 * reach. [SupabaseClientProvider] is where that is guaranteed.
 *
 * ## Why the bucket is not created by the app
 *
 * Creating a bucket needs the service-role key, and an app that carries a
 * service-role key is an app that can read every row of the database. The
 * bucket is therefore something the project's owner creates once — and the
 * failure when it is missing says so, rather than reporting a generic error.
 *
 * ## Why each user's archives live under their own prefix
 *
 * The bucket is shared by every account of the project, so the path carries the
 * user id: `<userId>/<file name>`. A listing for one user then never sees
 * another user's archives, whatever the bucket's own policies are.
 *
 * ## What the failures say
 *
 * The bucket's policies are the reason a push can fail in four different ways,
 * and the difference between them is the difference between "sign in again",
 * "the bucket does not exist", "there is no connection" and "the policy
 * refused this write". The first is a session problem, the second a deployment
 * problem, and they used to arrive as one line: `backup.push_failed`.
 */
class SupabaseStorageRemoteBackupService(
    private val clientProvider: SupabaseClientProvider,
    private val fileSystem: FileSystem,
    private val bucketId: String = DEFAULT_BUCKET,
) : RemoteBackupService {

    override suspend fun upload(localPath: String, userId: UserId): Result<String> =
        runCatchingCancellable {
            val client = requireClient()
            val bytes = fileSystem.readBytes(localPath)
            val remotePath = remotePath(userId, localPath)
            client.storage.from(bucketId).upload(remotePath, bytes) { upsert = true }
            remotePath
        }.recoverCatching { error ->
            throw classified(error, "the archive could not be uploaded")
        }

    override suspend fun download(
        remoteRef: String,
        destPath: String,
        userId: UserId,
    ): Result<Unit> = runCatchingCancellable {
        val client = requireClient()
        val bytes = client.storage.from(bucketId).downloadAuthenticated(remoteRef)
        fileSystem.writeBytes(destPath, bytes)
    }.recoverCatching { error ->
        throw classified(error, "the archive could not be downloaded")
    }

    override suspend fun list(userId: UserId): Result<List<String>> = runCatchingCancellable {
        val client = requireClient()
        client.storage.from(bucketId).list("${userId.value}/").map { it.name }
    }.recoverCatching { error ->
        throw classified(error, "the archives could not be listed")
    }

    /**
     * The failure as the project reports it.
     *
     * The vendor's failure is classified at the seam, so the code the crash
     * reporter groups by says which of the four happened.
     */
    private fun classified(error: Throwable, what: String): Throwable = when (error) {
        is TransportException -> transportError(
            area = "backup",
            failure = error.failure,
            message = error.message ?: what,
            cause = error,
            rejectedCode = REJECTED_CODE,
        )

        // The project already said what is wrong: no cloud storage is configured.
        // Classifying it as a network failure would be a worse message than the
        // one it has.
        is RemoteBackupException -> error

        is AppError -> error

        else -> {
            val transport = SupabaseTransportFailure.toTransportException(error, what)
            transportError(
                area = "backup",
                failure = transport.failure,
                message = transport.message ?: what,
                cause = transport,
                rejectedCode = REJECTED_CODE,
            )
        }
    }

    private suspend fun requireClient() = clientProvider.client() ?: throw RemoteBackupException(
        "No cloud storage is configured. Add a Supabase project in the Account settings first.",
    )

    private fun remotePath(userId: UserId, localPath: String): String =
        "${userId.value}/${localPath.substringAfterLast('/')}"

    companion object {
        /** The bucket the archives go into. It has to exist in the project. */
        const val DEFAULT_BUCKET = "backups"

        /** The code for the class that means "something else went wrong". */
        const val REJECTED_CODE = "backup.push_failed"
    }
}
