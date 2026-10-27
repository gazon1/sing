package com.singularity.todo.core.backup

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.profile.ProfileId
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
        val u = userId ?: error("userId required")
        val d = destPath ?: error("destPath required")
        return ExportOptions(u, d, includeAttachments, appVersion)
    }
}

@BackupDsl
class ImportOptionsBuilder {
    var sourcePath: String? = null
    var targetUserId: UserId? = null
    var targetProfileId: ProfileId? = null
    var overwriteExisting: Boolean = true
    fun build(): ImportOptions {
        val s = sourcePath ?: error("sourcePath required")
        val u = targetUserId ?: error("targetUserId required")
        return ImportOptions(s, u, overwriteExisting, targetProfileId)
    }
}

data class ExportOptions(
    val userId: UserId,
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
     * The profile the restored rows belong to, when the caller knows it.
     *
     * The repository stamps the active profile on both restore paths. A direct
     * importer caller that does not set it falls back to [targetUserId], which is
     * the repair's scope only for the default profile.
     */
    val targetProfileId: ProfileId? = null,
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
