package com.singularity.todo.core.backup

/**
 * The bucket the archives live in, as the backup feature sees it.
 *
 * ## Why this is a port
 *
 * The class that decides *where* an archive goes and *what a failure means* is the
 * one worth testing: the prefix under the user's id is what keeps one person's
 * archive out of another person's list, and the four kinds of failure are what tell
 * "sign in again" from "the bucket is not there". Both are decisions, and a decision
 * that can only be made by talking to a bucket is a decision nobody can check.
 *
 * So the bucket's own calls sit behind this interface, and the vendor's types stay
 * in the one file that implements it. The service above is then testable with a fake
 * that records what was asked for — which is how "the archive went under the user's
 * own prefix" becomes an assertion instead of a hope.
 *
 * ## Why the paths are the caller's business
 *
 * The port takes a path and nothing else: no user id, no bucket name. The prefix is
 * the service's decision, and a port that computed it would make the decision
 * invisible — and untestable.
 */
interface BackupBucket {

    /**
     * Puts [bytes] at [path], replacing what was there.
     *
     * The replacement is the point: a push of the same archive twice must leave one
     * archive, not two.
     */
    suspend fun upload(path: String, bytes: ByteArray)

    /** The bytes stored at [path]. */
    suspend fun download(path: String): ByteArray

    /** The objects under [prefix]. */
    suspend fun list(prefix: String): List<BucketObject>
}

/**
 * One object in the bucket, as the backup feature sees it.
 *
 * The bucket knows more about an object than its path — when it was written and how
 * large it is — and a list that carries only the path throws that away. The person
 * choosing an archive to restore wants to know which one it is and how big it is;
 * a bare name answers neither.
 *
 * @param path the object's path in the bucket — the reference a download takes.
 * @param updatedAtEpochMillis when the object was last written, or null when the
 *   bucket does not say. A bucket may answer a listing without the metadata of
 *   every object, and "unknown" is what null means — not zero, and not the epoch.
 *   The list leaves the line out rather than showing a date that never was.
 * @param sizeBytes how large the object is, or null when the bucket does not say.
 *   The same holds here: an unknown size is not a size of zero.
 */
data class BucketObject(val path: String, val updatedAtEpochMillis: Long?, val sizeBytes: Long?)
