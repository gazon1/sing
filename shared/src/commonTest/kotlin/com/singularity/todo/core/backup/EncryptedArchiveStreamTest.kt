package com.singularity.todo.core.backup

import com.singularity.todo.core.files.ByteArraySink
import com.singularity.todo.core.files.ByteArraySource
import com.singularity.todo.core.files.ByteSource
import com.singularity.todo.core.security.FakeArchiveCrypto
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag

/**
 * The container, sealed and opened in pieces.
 *
 * ## Why this is a separate file
 *
 * The container has two shapes: the whole-archive one, which reads the archive
 * into memory and hands it over as one `ByteArray`, and the streaming one, which
 * never holds more than a chunk of it.
 *
 * The streaming shape exists because the archive of an account with attachments
 * does not fit in a phone's memory twice over, and because the bytes that leave
 * the device have to be the same bytes either way — a container that only the
 * streaming path can read would be a container nobody could restore from.
 *
 * So the two are checked against each other here: the same archive, sealed both
 * ways, has to come out the same length, with the same header, and open again.
 */
@Tag("fast")
class EncryptedArchiveStreamTest {

    private val archive = EncryptedArchive(FakeArchiveCrypto())
    private val password = "correct horse".toCharArray()

    /** The archive comes back as it went in, through the streaming path. */
    @Test
    fun `the archive comes back as it went in`() {
        val plain = "the-archive-bytes".encodeToByteArray()

        val sealed = ByteArraySink()
        archive.seal(ByteArraySource(plain), sealed, password)
        val opened = ByteArraySink()
        archive.open(ByteArraySource(sealed.bytes()), opened, password)

        assertContentEquals(plain, opened.bytes(), "the archive must come back as it went in")
    }

    /** The two shapes produce the same container. */
    @Test
    fun `the streaming container is the container the byte-shaped one produces`() {
        val plain = "the-archive-bytes".encodeToByteArray()

        val byBytes = archive.seal(plain, password)
        val byStream = ByteArraySink()
        archive.seal(ByteArraySource(plain), byStream, password)
        val streamed = byStream.bytes()

        assertEquals(byBytes.size, streamed.size, "the two shapes must produce the same container")
        assertContentEquals(
            byBytes.copyOfRange(0, EncryptedArchive.MAGIC.length + 1),
            streamed.copyOfRange(0, EncryptedArchive.MAGIC.length + 1),
            "the magic and the version must be the same",
        )
        assertTrue(
            streamed.decodeToString().startsWith(EncryptedArchive.MAGIC),
            "the container must say what it is",
        )
    }

    /** A wrong password fails at the tag, not with garbage. */
    @Test
    fun `the streaming path refuses a wrong password too`() {
        val sealed = ByteArraySink()
        archive.seal(ByteArraySource("the-archive-bytes".encodeToByteArray()), sealed, password)

        assertFailsWith<BackupError.WrongPassword> {
            archive.open(ByteArraySource(sealed.bytes()), ByteArraySink(), "wrong horse".toCharArray())
        }
    }

    /** Bytes that are too short to be a container are refused by name. */
    @Test
    fun `the streaming path refuses bytes that are not an archive`() {
        assertFailsWith<BackupError.NotEncrypted> {
            archive.open(ByteArraySource("too short".encodeToByteArray()), ByteArraySink(), password)
        }
    }

    /** An empty password is refused in both directions. */
    @Test
    fun `the streaming path refuses an empty password`() {
        assertFailsWith<BackupError.MissingPassword> {
            archive.seal(ByteArraySource("the-archive-bytes".encodeToByteArray()), ByteArraySink(), CharArray(0))
        }
    }

    /**
     * A source that breaks is not a wrong password.
     *
     * The header is served and then the disk falls over — the shape of a file that
     * cannot be read to the end. The person who is told "wrong password" will
     * retype it, and it was never the password.
     */
    @Test
    fun `a source that breaks is not reported as a wrong password`() {
        val sealed = ByteArraySink()
        archive.seal(ByteArraySource("the-archive-bytes".encodeToByteArray()), sealed, password)

        val error = assertFailsWith<BackupError.Unreadable> {
            archive.open(brokenAfterHeader(sealed.bytes()), ByteArraySink(), password)
        }
        assertTrue(
            error.message?.contains("read") == true,
            "the message must name the reading, not the password: ${error.message}",
        )
    }

    /**
     * A source that serves the header and then fails.
     *
     * The header is read before the cipher is asked for anything, so the failure
     * has to happen after it — otherwise this would be a container that is too
     * short, which is a different sentence entirely.
     */
    private fun brokenAfterHeader(container: ByteArray): ByteSource {
        var served = false
        return object : ByteSource {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (served) throw IllegalStateException("the disk fell over")
                served = true
                val count = minOf(length, container.size)
                container.copyInto(buffer, offset, 0, count)
                return count
            }

            override fun close() = Unit
        }
    }
}
