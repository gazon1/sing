package com.singularity.todo.core.backup

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.version.appVersion

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

    /** Whether the rows the account already holds are left alone — see [ImportOptions.keepExistingRows]. */
    var keepExistingRows: Boolean = false

    fun build(): ImportOptions {
        val s = sourcePath ?: error("sourcePath required")
        val u = targetUserId ?: error("targetUserId required")
        return ImportOptions(s, u, ownerId, ownerId, keepExistingRows = keepExistingRows)
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
    /**
     * Whether the rows the account already holds are left alone.
     *
     * ## What it changes
     *
     * False — today's behaviour, and the default — is the archive winning: every
     * row an archive carries is written over whatever the account holds, and a
     * row a change says was removed is taken out. An archive restored over an
     * account that was edited since replaces it with the archive's older rows.
     *
     * True writes only the rows the account does not have. A row that is already
     * there keeps the account's version, bytes included for an attachment, and
     * **nothing is removed**: a restore that keeps what is there cannot honour a
     * change that says a row is gone, because that row is one of the rows it was
     * asked to leave alone.
     *
     * ## What it reports
     *
     * [RestoreResult.keptEntityCounts] says what was left alone, by kind, so the
     * caller can tell "the account has all of this already" from "the restore
     * did nothing". [RestoreResult.entityCounts] keeps its meaning — the merged
     * archive. The two are answers to different questions and not a sum: a
     * change that says a row was removed takes it out of the archive's counts,
     * while a kept row is counted as kept.
     *
     * ## Where the net is drawn
     *
     * A row is the unit, not an account: the rows that hang off a kept row — a
     * tag link, a checklist item, a reminder — are rows of their own, and they
     * are written when the account does not have them. So a kept task can gain
     * the archive's tags while its own title stays as it was.
     */
    val keepExistingRows: Boolean = false,
)

fun exportOptions(block: ExportOptionsBuilder.() -> Unit): ExportOptions = ExportOptionsBuilder().apply(block).build()

fun importOptions(block: ImportOptionsBuilder.() -> Unit): ImportOptions = ImportOptionsBuilder().apply(block).build()

@JvmInline
value class BackupId(val value: String) {
    companion object {
        fun fromPath(path: String): BackupId {
            val name = path.substringAfterLast('/').substringBeforeLast('.')
            return BackupId(name)
        }
    }
}
