package com.singularity.todo.core.backup

sealed class BackupError(message: String) : Exception(message) {
    class UnsupportedFormatVersion(val version: Int) : BackupError("formatVersion $version > current")
    class UnsupportedSchemaVersion(val version: Int) : BackupError("schemaVersion $version > current")
    class ChecksumMismatch(val expected: String, val actual: String) : BackupError("checksum mismatch: expected $expected, got $actual")
    class MalformedManifest(reason: String) : BackupError("malformed manifest: $reason")
    class FileNotFound(val path: String) : BackupError("file not found: $path")
}
