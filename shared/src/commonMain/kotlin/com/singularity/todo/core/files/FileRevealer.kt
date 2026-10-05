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
     *
     * Returns whether the system was asked to show it, and **never throws** — the
     * same contract as [FileSharePort.shareFile], and for the same reason. A
     * `Unit` return left the only failure mode as an exception escaping from
     * `Desktop.getDesktop()` on a host with no display, which is a crash the user
     * caused by clicking a button in Settings, and which no caller could have
     * handled because none of them were expecting anything to come back.
     *
     * `false` means "no file manager was opened", not "the folder is missing" —
     * the implementations create the folder first, so a `false` is about the
     * platform refusing to open anything, which is the case worth telling the
     * user about.
     */
    suspend fun revealAttachmentsFolder(folderPath: String): Boolean

    /** Returns the absolute path to the attachments base folder for the current platform. */
    fun attachmentsBasePath(): String
}
