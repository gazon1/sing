package com.singularity.todo.core.attachments

object AttachmentDomain {
    private val URL_REGEX = Regex("^https?://[^\\s/$.?#].[^\\s]*$")

    fun validateUrl(url: String): Result<Unit> = runCatching {
        require(url.isNotBlank()) { "URL cannot be blank" }
        require(URL_REGEX.matches(url)) { "Invalid URL format: $url" }
    }

    fun generateAttachmentId(): AttachmentId = AttachmentId.generate()

    fun buildLocalPath(dir: String, taskId: String, id: String, ext: String): String {
        require(!taskId.contains('/') && !taskId.contains('\\') && !taskId.contains("..")) {
            "taskId must not contain path separators or '..': $taskId"
        }
        require(!id.contains('/') && !id.contains('\\') && !id.contains("..")) {
            "id must not contain path separators or '..': $id"
        }
        require(!ext.contains('/') && !ext.contains('\\') && !ext.contains("..")) {
            "ext must not contain path separators or '..': $ext"
        }
        val target = "$dir/$taskId/$id${if (ext.isNotBlank()) ".$ext" else ""}"
        val base = dir.trimEnd('/')
        require(target.startsWith("$base/")) { "resolved path escapes attachment dir: $target" }
        return target
    }

    fun extractExtension(filename: String): String = filename.substringAfterLast('.', "").lowercase()
}
