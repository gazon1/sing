package com.singularity.todo.core.attachments

object AttachmentDomain {
    private val URL_REGEX = Regex("^https?://[^\\s/$.?#].[^\\s]*$")

    fun validateUrl(url: String): Result<Unit> = runCatching {
        require(url.isNotBlank()) { "URL cannot be blank" }
        require(URL_REGEX.matches(url)) { "Invalid URL format: $url" }
    }

    fun generateAttachmentId(): AttachmentId = AttachmentId.generate()

    fun buildLocalPath(dir: String, taskId: String, id: String, ext: String): String =
        "$dir/$taskId/$id${if (ext.isNotBlank()) ".$ext" else ""}"

    fun extractExtension(filename: String): String = filename.substringAfterLast('.', "").lowercase()
}
