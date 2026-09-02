package com.singularity.todo.core.files

object MimeTypes {
    private val TABLE = mapOf(
        // Images
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "svg" to "image/svg+xml",
        "heic" to "image/heic",
        "heif" to "image/heif",
        // Documents
        "pdf" to "application/pdf",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "txt" to "text/plain",
        "rtf" to "application/rtf",
        "csv" to "text/csv",
        // Audio
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "ogg" to "audio/ogg",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        // Video
        "mp4" to "video/mp4",
        "mov" to "video/quicktime",
        "avi" to "video/x-msvideo",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        // Archives
        "zip" to "application/zip",
        "rar" to "application/x-rar-compressed",
        "7z" to "application/x-7z-compressed",
        "tar" to "application/x-tar",
        "gz" to "application/gzip",
        // Other
        "" to "application/octet-stream"
    )

    fun fromExtension(ext: String): String =
        TABLE[ext.lowercase()] ?: "application/octet-stream"

    fun isImage(mime: String): Boolean = mime.startsWith("image/")
    fun isDocument(mime: String): Boolean = mime == "application/pdf" ||
        mime.startsWith("application/msword") ||
        mime.startsWith("application/vnd.openxmlformats")
    fun isAudio(mime: String): Boolean = mime.startsWith("audio/")
    fun isVideo(mime: String): Boolean = mime.startsWith("video/")
    fun isArchive(mime: String): Boolean = mime == "application/zip" ||
        mime == "application/x-rar-compressed" ||
        mime == "application/x-7z-compressed"
}
