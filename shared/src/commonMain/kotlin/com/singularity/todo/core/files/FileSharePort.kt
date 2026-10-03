package com.singularity.todo.core.files

/**
 * Shares a file to an external app via the platform's native share sheet.
 *
 * Android: uses `FileProvider` — the file must be accessible via a registered
 * `files-path` in `file_paths.xml`. The existing `logs/` mapping is used by
 * [LogBundleExporter][com.singularity.todo.core.log.LogBundleExporter].
 *
 * JVM: uses `Desktop.browse` on the file's URI. Only ZIP archives are shared.
 *
 * This port does not own the lifecycle of [filePath] — callers are responsible
 * for the file's creation and cleanup.
 */
interface FileSharePort {
    /**
     * Offers [filePath] to the platform's share sheet under [mimeType].
     *
     * @return `true` if a share was started, `false` if the platform refused.
     *   The platform may refuse if no app can handle [mimeType].
     */
    fun shareFile(filePath: String, mimeType: String): Boolean
}
