package com.singularity.todo.core.backup

/**
 * What the archive carried is not what it should be.
 *
 * ## What this family is about
 *
 * The container opened, and the container said: here is a manifest, here is a
 * payload, here is what the payload should hash to. Something in there does not
 * hold together — the schema is from the future, the checksum does not match,
 * the manifest is not a manifest.
 *
 * These are the failures that used to be reported as "the archive is broken",
 * which sent the person to their password when their password was fine.
 */
sealed class BackupPayloadError(message: String, cause: Throwable? = null) : BackupError(message, cause) {

    /** The archive's own format version is newer than this build's. */
    class UnsupportedFormatVersion(val version: Int) :
        BackupPayloadError("formatVersion $version > current")

    /** The payload's schema version is newer than this build's. */
    class UnsupportedSchemaVersion(val version: Int) :
        BackupPayloadError("schemaVersion $version > current")

    /** The payload's schema version is older than this build still reads. */
    class SchemaTooOld(val version: Int) :
        BackupPayloadError(
            "schemaVersion $version < minimum supported ${BackupFormat.MIN_SUPPORTED_SCHEMA_VERSION}",
        )

    /** The payload does not hash to what the manifest says it hashes to. */
    class ChecksumMismatch(val expected: String, val actual: String) :
        BackupPayloadError(
            "checksum mismatch: expected $expected, got $actual",
        )

    /** The manifest is not a manifest this build can read. */
    class MalformedManifest(reason: String) :
        BackupPayloadError("malformed manifest: $reason")
}
