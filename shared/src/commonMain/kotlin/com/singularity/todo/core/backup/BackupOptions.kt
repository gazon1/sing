package com.singularity.todo.core.backup

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.version.appVersion
import kotlinx.serialization.Serializable

@DslMarker
annotation class BackupDsl

@BackupDsl
class ExportOptionsBuilder {
    var userId: UserId? = null
    var ownerId: UserId? = null

    /**
     * Where the archive is written: a file name, or a destination the person
     * chose. See [ExportOptions.destPath].
     */
    var destPath: String? = null
    var includeAttachments: Boolean = true
    var appVersion: String = appVersion().name

    fun build(): ExportOptions {
        val d = destPath ?: error("destPath required")
        return ExportOptions(userId, ownerId, ownerId, d, includeAttachments, appVersion)
    }
}

@BackupDsl
class ImportOptionsBuilder {
    var sourcePath: String? = null
    var targetUserId: UserId? = null
    var ownerId: UserId? = null

    fun build(): ImportOptions {
        val s = sourcePath ?: error("sourcePath required")
        val u = targetUserId ?: error("targetUserId required")
        return ImportOptions(s, u, ownerId, ownerId)
    }
}

data class ExportOptions(
    val userId: UserId?,
    val ownerId: UserId? = null,
    val ownerUserId: UserId? = null,
    /**
     * Where the archive is written.
     *
     * Two kinds of value are accepted, and the shape of the value says which:
     *
     * - A **file name** — `backup.zip`. The repository owns the backup
     *   directory and joins the two, so the archive lands beside the app's
     *   other backups.
     * - A **destination the person chose** — an absolute path, or a
     *   `content://` URI from the system save dialog. It is used as it is:
     *   nothing is joined to it, and the archive lands exactly there.
     *
     * A value carrying a scheme (`://`), a `content:` prefix, or a path
     * separator is the second kind; a value carrying none of them is the
     * first.
     */
    val destPath: String,
    val includeAttachments: Boolean = true,
    val appVersion: String = appVersion().name,
)

data class ImportOptions(
    val sourcePath: String,
    val targetUserId: UserId,
    val ownerId: UserId? = null,
    val ownerUserId: UserId? = null,
    /**
     * The changes that follow the archive, in order.
     *
     * Each one is a whole delta — a set of rows — and it has to be applied
     * whole, because a delta is not a patch. An archive from before the chain
     * existed carries none, and is restored exactly as it always was.
     */
    val entries: List<ByteArray> = emptyList(),
)

fun exportOptions(block: ExportOptionsBuilder.() -> Unit): ExportOptions = ExportOptionsBuilder().apply(block).build()

fun importOptions(block: ImportOptionsBuilder.() -> Unit): ImportOptions = ImportOptionsBuilder().apply(block).build()

@Serializable
@JvmInline
value class BackupId(val value: String) {
    companion object {
        fun fromPath(path: String): BackupId {
            val name = path.substringAfterLast('/').substringBeforeLast('.')
            return BackupId(name)
        }
    }
}
