package com.singularity.todo.core.backup

import com.singularity.todo.core.ids.UserId
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("fast")
class BackupDomainTest {

    @Test
    fun sha256HexIsDeterministic() {
        val data = "hello world".toByteArray()
        val hash1 = BackupDomain.sha256Hex(data)
        val hash2 = BackupDomain.sha256Hex(data)
        assertEquals(hash1, hash2)
    }

    @Test
    fun sha256HexProduces64CharHexString() {
        val hash = BackupDomain.sha256Hex("test".toByteArray())
        assertEquals(64, hash.length)
        assertTrue(hash.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun extractUserIdHashIsStable() {
        val userId = UserId.fromString("user-123")
        val hash1 = BackupDomain.extractUserIdHash(userId)
        val hash2 = BackupDomain.extractUserIdHash(userId)
        assertEquals(hash1, hash2)
    }

    @Test
    fun buildManifestProducesValidManifest() {
        val userId = UserId.fromString("user-123")
        val payloadBytes = "{\"tasks\":[]}".toByteArray()
        val counts = EntityCounts(tasks = 1, notes = 2, projects = 0, tags = 0, attachments = 0, taskTags = 0)

        val manifest = BackupDomain.buildManifest(
            appVersion = "1.0.0",
            nowEpochMillis = 1000L,
            userId = userId,
            payloadBytes = payloadBytes,
            counts = counts,
        )

        assertEquals(1, manifest.formatVersion)
        assertEquals(BackupFormat.SCHEMA_VERSION, manifest.schemaVersion)
        assertEquals("singularity-todo", manifest.appName)
        assertEquals("1.0.0", manifest.appVersion)
        assertEquals(1000L, manifest.createdAtEpochMillis)
        assertEquals(1, manifest.entityCounts.tasks)
        assertEquals(2, manifest.entityCounts.notes)
        assertEquals(64, manifest.payloadChecksum.length)
    }

    @Test
    fun validateManifestAcceptsValidManifest() {
        val userId = UserId.fromString("user-123")
        val payloadBytes = "{\"tasks\":[]}".toByteArray()
        val manifest = BackupDomain.buildManifest(
            "1.0.0",
            1000L,
            userId,
            payloadBytes,
            EntityCounts(tasks = 0),
        )

        val result = BackupDomain.validateManifest(manifest, payloadBytes)
        assertTrue(result.isSuccess)
    }

    @Test
    fun validateManifestRejectsFutureFormatVersion() {
        val manifest = BackupManifest(
            formatVersion = 99,
            appName = "test",
            appVersion = "1.0",
            createdAtEpochMillis = 0,
            userIdHash = "hash",
            schemaVersion = 1,
            entityCounts = EntityCounts(),
            payloadChecksum = "abc",
        )
        val result = BackupDomain.validateManifest(manifest, "{}".toByteArray())
        result.onFailure {
            assertTrue(it is BackupError.UnsupportedFormatVersion)
        }
    }

    @Test
    fun validateManifestRejectsFutureSchemaVersion() {
        val manifest = BackupManifest(
            formatVersion = 1,
            appName = "test",
            appVersion = "1.0",
            createdAtEpochMillis = 0,
            userIdHash = "hash",
            schemaVersion = 99,
            entityCounts = EntityCounts(),
            payloadChecksum = "abc",
        )
        val result = BackupDomain.validateManifest(manifest, "{}".toByteArray())
        result.onFailure {
            assertTrue(it is BackupError.UnsupportedSchemaVersion)
        }
    }

    @Test
    fun validateManifestRejectsChecksumMismatch() {
        val manifest = BackupManifest(
            formatVersion = 1,
            appName = "test",
            appVersion = "1.0",
            createdAtEpochMillis = 0,
            userIdHash = "hash",
            schemaVersion = 1,
            entityCounts = EntityCounts(),
            payloadChecksum = "wrong_checksum",
        )
        val result = BackupDomain.validateManifest(manifest, "{}".toByteArray())
        result.onFailure {
            assertTrue(it is BackupError.ChecksumMismatch)
        }
    }
}
