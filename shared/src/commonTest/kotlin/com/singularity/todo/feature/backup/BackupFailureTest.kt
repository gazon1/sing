package com.singularity.todo.feature.backup

import com.singularity.todo.core.backup.ArchiveEnvelopeError
import com.singularity.todo.core.backup.BackupPayloadError
import com.singularity.todo.core.backup.BackupStorageError
import com.singularity.todo.core.error.AppError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The failures the backup layer reports, said the way a person can act on.
 *
 * The screen and the crash report used to be told different things: the report
 * got one code for everything and the screen got whatever the exception's message
 * happened to be. A person was told "backup failed" for a wrong password, for a
 * broken chain and for a device that is not allowed to push — three different
 * actions behind one sentence.
 */
class BackupFailureTest {

    /** The fallback is what a failure that is not the backup's own says. */
    private val fallback = BackupFailure("backup.something_else", "Something else went wrong")

    @Test
    fun `a wrong password says so`() {
        val failure = backupFailure(ArchiveEnvelopeError.WrongPassword(), fallback)

        assertEquals(WRONG_PASSWORD, failure.code)
        assertEquals("That password does not open the archive", failure.message)
    }

    @Test
    fun `a broken chain says so`() {
        val failure = backupFailure(ArchiveEnvelopeError.BrokenChain("the chain is broken"), fallback)

        assertEquals(BROKEN_CHAIN, failure.code)
        assertTrue(
            failure.message.contains("chain"),
            "the sentence must name what is broken: ${failure.message}",
        )
    }

    @Test
    fun `a device that may not push says so`() {
        val failure = backupFailure(BackupStorageError.DeviceNotAuthorized("push on its own"), fallback)

        assertEquals(DEVICE_NOT_AUTHORIZED, failure.code)
        assertEquals("This device is not allowed to back up on its own", failure.message)
    }

    @Test
    fun `a payload that does not match its manifest says so`() {
        val failure = backupFailure(BackupPayloadError.ChecksumMismatch("abc", "def"), fallback)

        assertEquals(CHECKSUM_MISMATCH, failure.code)
        assertTrue(
            failure.message.contains("manifest"),
            "the sentence must say what did not match: ${failure.message}",
        )
    }

    @Test
    fun `an archive that is not encrypted says so`() {
        val failure = backupFailure(ArchiveEnvelopeError.NotEncrypted(), fallback)

        assertEquals(NOT_ENCRYPTED, failure.code)
    }

    @Test
    fun `an app error keeps its own code and its own words`() {
        val failure = backupFailure(AppError.Validation("The name is too long"), fallback)

        assertEquals("error.validation", failure.code)
        assertEquals("The name is too long", failure.message)
    }

    @Test
    fun `a failure that is not the backup's own keeps the fallback code and its own words`() {
        val failure = backupFailure(IllegalStateException("the bucket refused"), fallback)

        assertEquals(fallback.code, failure.code, "the code groups by the operation that failed")
        assertEquals("the bucket refused", failure.message, "the words are what actually happened")
    }

    @Test
    fun `a failure with no words of its own keeps the fallback words`() {
        val failure = backupFailure(IllegalStateException(), fallback)

        assertEquals(fallback.code, failure.code)
        assertEquals(fallback.message, failure.message, "a failure with nothing to say says the fallback")
    }
}
