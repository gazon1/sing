package com.singularity.todo.core.files

/**
 * What the application can do with a file, beyond knowing its MIME type.
 *
 * This is the only place that decides it. [AttachmentViewerRoute][com.singularity.todo.core.attachments.AttachmentViewerRoute]
 * routes an open request and the attachment storage records a type; both derive from
 * here, so a format cannot be "viewable" to one and "external" to the other.
 */
enum class FileCategory {
    /** Renders in the in-app image viewer. */
    Image,

    /** Renders in the in-app text viewer. */
    Text,

    /** No in-app renderer — handed to the platform. */
    External,
}

/**
 * The application's single source of truth for "which file types does this app know".
 *
 * [TABLE] is the table; everything else is derived from it. The supported-extension set
 * was previously written out separately at each place that needed one, which is how the
 * settings help page ended up promising nine formats against the forty in the table
 * while no code validated anything at all.
 */
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
        "" to "application/octet-stream",
    )

    fun fromExtension(ext: String): String = TABLE[ext.lowercase()] ?: "application/octet-stream"

    /**
     * Every extension in [TABLE], lower-cased and without the empty catch-all entry.
     *
     * `TABLE` carries `"" to "application/octet-stream"` as its fallback row. That row
     * is useful to [fromExtension] and useless as a picker filter — FileKit would be
     * asked to match files whose extension is the empty string — so it is dropped here.
     */
    val supportedExtensions: Set<String> = TABLE.keys.filter { it.isNotBlank() }.toSet()

    /**
     * Extensions the in-app text viewer can render.
     *
     * Markdown is here because the viewer formats it; the rest are plain text. The set
     * is about *rendering*, not about the MIME table — `pdf` is a known type with no
     * in-app renderer, which is exactly why `FileCategory` is a separate question from
     * `supportedExtensions`.
     */
    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "json", "csv", "log", "xml", "yml", "yaml",
        // Source. `ts`/`tsx`/`jsx` sit next to `js` deliberately: a file of code is
        // read in an editor, and the in-app text viewer is the closest thing this app
        // has to one for an attachment.
        "css", "js", "jsx", "ts", "tsx",
        "kt", "kts", "java", "py", "sh", "sql", "ini", "toml",
    )

    /**
     * Extensions the in-app image viewer can render.
     *
     * `svg` is deliberately absent. Its MIME type starts with `image/`, but the project
     * has no SVG renderer, so classifying it as [FileCategory.Image] would promise a
     * viewer that does not exist. It goes out to the platform like any other format the
     * app cannot show.
     */
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

    /** Classify by file name, using its extension. Unknown extensions are [FileCategory.External]. */
    fun classify(fileName: String): FileCategory {
        val ext = extensionOf(fileName) ?: return FileCategory.External
        return classifyExtension(ext)
    }

    /** Classify by a bare extension, with or without a leading dot. */
    fun classifyExtension(extension: String): FileCategory {
        val ext = extension.removePrefix(".").lowercase()
        return when {
            ext in IMAGE_EXTENSIONS -> FileCategory.Image
            ext in TEXT_EXTENSIONS -> FileCategory.Text
            else -> FileCategory.External
        }
    }

    /** Classify by a MIME type. An unknown or blank type falls back to [classify]. */
    fun classifyMime(mimeType: String?, fileName: String? = null): FileCategory {
        val mime = mimeType?.lowercase()
        val byMime = when {
            mime.isNullOrBlank() -> FileCategory.External

            mime == "image/svg+xml" -> FileCategory.External

            mime.startsWith("image/") -> FileCategory.Image

            mime.startsWith("text/") ||
                mime == "application/json" ||
                mime == "application/xml" ||
                mime == "application/javascript" ||
                mime == "application/x-sh" -> FileCategory.Text

            else -> FileCategory.External
        }
        if (byMime != FileCategory.External) return byMime
        // The MIME type said "not something I render". That is not the same as "I know
        // what this is and cannot show it": `application/octet-stream` is what a file
        // picked on Android, or restored from a backup, carries — and half of those are
        // images the app renders perfectly well. So the extension gets to say yes
        // before the answer becomes External.
        val ext = fileName?.let { extensionOf(it) }
        return if (ext != null) classifyExtension(ext) else FileCategory.External
    }

    /** The lower-cased extension of [fileName], or `null` when it has none. */
    fun extensionOf(fileName: String): String? {
        val dot = fileName.lastIndexOf('.')
        if (dot < 0 || dot == fileName.lastIndex) return null
        return fileName.substring(dot + 1).lowercase().takeIf { it.isNotBlank() }
    }

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
