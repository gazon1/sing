package com.singularity.todo.core.backup

object BackupFormat {
    const val FORMAT_VERSION = 1       // zip layout version
    const val SCHEMA_VERSION = 1       // entity shape version
    const val ENTRY_MANIFEST = "manifest.json"
    const val ENTRY_PAYLOAD = "payload.json"
    const val DIR_ATTACHMENTS = "attachments/"
    const val APP_NAME = "singularity-todo"
}
