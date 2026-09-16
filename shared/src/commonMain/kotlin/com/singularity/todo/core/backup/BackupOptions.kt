package com.singularity.todo.core.backup

import com.singularity.todo.core.ids.UserId

@DslMarker
annotation class BackupDsl

@BackupDsl
class ExportOptionsBuilder {
    var userId: UserId? = null
    var destPath: String? = null
    var includeAttachments: Boolean = true
    var appVersion: String = "0.0.11"
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
    var overwriteExisting: Boolean = true
    fun build(): ImportOptions {
        val s = sourcePath ?: error("sourcePath required")
        val u = targetUserId ?: error("targetUserId required")
        return ImportOptions(s, u, overwriteExisting)
    }
}

data class ExportOptions(
    val userId: UserId,
    val destPath: String,
    val includeAttachments: Boolean = true,
    val appVersion: String = "0.0.11",
)

data class ImportOptions(val sourcePath: String, val targetUserId: UserId, val overwriteExisting: Boolean = true)

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
