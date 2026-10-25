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
 *
 * Both are nullable, and null means *the bucket did not say* — not zero, and not the
 * epoch. The screen leaves the line out when it is missing, because a date of
 * 1970 or a size of "0 B" is a fact the bucket never stated, and a person who
 * reads one of them believes it.
 */
data class RemoteBackup(
    val ref: String,
    val name: String,
    val updatedAtEpochMillis: Long? = null,
    val sizeBytes: Long? = null,
)
