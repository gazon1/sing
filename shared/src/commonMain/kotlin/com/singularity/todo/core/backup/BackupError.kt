package com.singularity.todo.core.backup

import com.singularity.todo.core.backup.BackupFormat.MIN_SUPPORTED_SCHEMA_VERSION

sealed class BackupError(message: String) : Exception(message) {
    class UnsupportedFormatVersion(val version: Int) : BackupError("formatVersion $version > current")
    class UnsupportedSchemaVersion(val version: Int) : BackupError("schemaVersion $version > current")
    class SchemaTooOld(val version: Int) :
        BackupError(
            "schemaVersion $version < minimum supported ${BackupFormat.MIN_SUPPORTED_SCHEMA_VERSION}",
        )
    class ChecksumMismatch(val expected: String, val actual: String) :
        BackupError(
            "checksum mismatch: expected $expected, got $actual",
        )
    class MalformedManifest(reason: String) : BackupError("malformed manifest: $reason")
    class FileNotFound(val path: String) : BackupError("file not found: $path")
}
