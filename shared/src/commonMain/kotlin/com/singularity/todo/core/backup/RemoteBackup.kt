package com.singularity.todo.core.backup

/**
 * An archive in the cloud, as the list of them reports it.
 *
 * [ref] is what the download takes — the path of the object in the bucket — and [name]
 * is what the user reads. The two are different on purpose: the ref is the bucket's
 * business, and a screen that shows it asks the user to read a path.
 *
 * [updatedAtEpochMillis] and [sizeBytes] are what the bucket knows about the object
 * and the path alone does not say. A list of names is a list the person has to
 * guess from; these are the two answers that tell one archive from another.
 */
data class RemoteBackup(
    val ref: String,
    val name: String,
    val updatedAtEpochMillis: Long? = null,
    val sizeBytes: Long? = null,
)
