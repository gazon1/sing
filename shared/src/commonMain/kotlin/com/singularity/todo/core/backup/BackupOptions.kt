package com.singularity.todo.core.backup

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.version.appVersion

@DslMarker
annotation class BackupDsl

@BackupDsl
class ExportOptionsBuilder {
    var userId: UserId? = null
    var destPath: String? = null
    var includeAttachments: Boolean = true
    var appVersion: String = appVersion().name
    fun build(): ExportOptions {
        val d = destPath ?: error("destPath required")
        return ExportOptions(userId, d, includeAttachments, appVersion)
    }
}

@BackupDsl
class ImportOptionsBuilder {
    var sourcePath: String? = null
    var targetUserId: UserId? = null
    var overwriteExisting: Boolean = true
    fun build(): ImportOptions {
        val s = sourcePath ?: error("sourcePath required")
        val u = targetUserId ?: error("targetUserId required")
        return ImportOptions(s, u, overwriteExisting)
    }
}

data class ExportOptions(
    val userId: UserId?,
    /**
     * **File name** of the archive, not a path.
     *
     * The repository owns the backup directory and joins the two, so a caller
     * cannot aim the export outside that directory. A path here is rejected
     * rather than followed.
     */
    val destPath: String,
    val includeAttachments: Boolean = true,
    val appVersion: String = appVersion().name,
)

data class ImportOptions(
    val sourcePath: String,
    val targetUserId: UserId,
    val overwriteExisting: Boolean = true,
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

@JvmInline
value class BackupId(val value: String) {
    companion object {
        fun fromPath(path: String): BackupId {
            val name = path.substringAfterLast('/').substringBeforeLast('.')
            return BackupId(name)
        }
    }
}
