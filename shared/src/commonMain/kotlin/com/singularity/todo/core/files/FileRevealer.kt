package com.singularity.todo.core.files

/**
 * Opens the system file manager at a given absolute path.
 * On Android: fires `ACTION_OPEN_DOCUMENT_TREE` with the attachments directory.
 * On Desktop/JVM: opens the directory path via `Desktop.getDesktop().browse()`.
 */
interface FileRevealer {
    /**
     * Reveals the given absolute [folderPath] in the system file manager.
     * On Android opens the directory picker; on JVM opens the folder in the OS file explorer.
     */
    suspend fun revealAttachmentsFolder(folderPath: String)

    /** Returns the absolute path to the attachments base folder for the current platform. */
    fun attachmentsBasePath(): String
}
