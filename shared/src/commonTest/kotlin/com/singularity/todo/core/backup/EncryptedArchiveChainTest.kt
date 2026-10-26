package com.singularity.todo.core.backup

import com.singularity.todo.core.files.ByteArraySink
import com.singularity.todo.core.files.ByteArraySource
import com.singularity.todo.core.security.FakeArchiveCrypto
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The container as a chain: a base and the changes that follow it.
 *
 * ## What these tests are for
 *
 * The chain exists so a scheduled backup can send what changed instead of the whole
 * account. Two things make that safe rather than merely smaller, and both are
 * asserted here:
 *
 * - **a chain is read in order, and nothing is dropped silently** — an entry out of
 *   place, or fewer entries than the head promised, is refused by name;
 * - **an older archive still opens.** The container's version is a promise: version
 *   1 was written by every build before this one, and those archives are in buckets
 *   that nobody empties. The last test here builds a version-1 container by hand and
 *   requires this build to open it.
 */
@Tag("fast")
class EncryptedArchiveChainTest {

    private val crypto = FakeArchiveCrypto()
    private val archive = EncryptedArchive(crypto)
    private val password = "correct horse".toCharArray()

    /**
     * The base and the changes come back separately, in the order they were written.
     *
     * The caller needs the base and each delta apart, because applying a delta is
     * the importer's work and not the container's.
     */
    @Test
    fun `a chain opens its base and its changes in order`() {
        val base = "the base".encodeToByteArray()
        val first = "the first change".encodeToByteArray()
        val second = "the second change".encodeToByteArray()

        val sealed = ByteArraySink()
        archive.sealChain(
            base = ByteArraySource(base),
            entries = listOf(ByteArraySource(first), ByteArraySource(second)),
            sink = sealed,
            password = password,
        )

        val openedBase = ByteArraySink()
        val changes = mutableListOf<ByteArray>()
        archive.openChain(ByteArraySource(sealed.bytes()), openedBase, password) { index, plain ->
            assertEquals(changes.size, index, "the changes must arrive in order")
            changes.add(plain)
        }

        assertContentEquals(base, openedBase.bytes(), "the base must come back as it went in")
        assertEquals(2, changes.size, "both changes must arrive")
        assertContentEquals(first, changes[0])
        assertContentEquals(second, changes[1])
    }

    /**
     * Reading a chain whole is refused, not silently truncated to its base.
     *
     * This is the failure the whole `BrokenChain` case exists for: a caller that
     * asks for the archive as one array would otherwise get the base and no
     * changes — yesterday's state, presented as today's.
     */
    @Test
    fun `reading a chain whole is refused rather than returning the base alone`() {
        val sealed = ByteArraySink()
        archive.sealChain(
            base = ByteArraySource("the base".encodeToByteArray()),
            entries = listOf(ByteArraySource("a change".encodeToByteArray())),
            sink = sealed,
            password = password,
        )

        val error = assertFailsWith<BackupError.BrokenChain> {
            archive.open(sealed.bytes(), password)
        }
        assertTrue(
            error.message?.contains("cannot apply") == true,
            "the message must say what the call could not do: ${error.message}",
        )
    }

    /**
     * A chain whose changes are not there is refused by name.
     *
     * The entries are dropped from the middle of the container — the bytes the head
     * promised are simply absent — and the reader must say so rather than hand back
     * a base that looks whole.
     */
    @Test
    fun `a chain shorter than its head is refused`() {
        val sealed = ByteArraySink()
        archive.sealChain(
            base = ByteArraySource("the base".encodeToByteArray()),
            entries = listOf(ByteArraySource("the only change".encodeToByteArray())),
            sink = sealed,
            password = password,
        )
        val whole = sealed.bytes()

        // Cut the entry's frame out: everything after the header, up to the base.
        val withoutTheEntry = whole.copyOfRange(0, EncryptedArchive.HEADER_BYTES_V2) +
            whole.copyOfRange(EncryptedArchive.HEADER_BYTES_V2 + EncryptedArchive.ENTRY_HEAD_BYTES, whole.size)

        val error = assertFailsWith<BackupError.BrokenChain> {
            archive.openChain(ByteArraySource(withoutTheEntry), ByteArraySink(), password) { _, _ -> }
        }
        assertTrue(
            error.message?.contains("broken") == true || error.message?.contains("ends") == true,
            "the message must name the chain: ${error.message}",
        )
    }

    /**
     * A version-1 archive still opens.
     *
     * Version 1 is what every earlier build wrote, and every one of those archives
     * is in a bucket that nobody empties. This build no longer **writes** v1 — the
     * test builds the container by hand for exactly that reason.
     */
    @Test
    fun `a version-1 container still opens`() {
        val base = "an archive from an older build".encodeToByteArray()
        val salt = crypto.randomBytes(EncryptedArchive.SALT_BYTES)
        val nonce = crypto.randomBytes(EncryptedArchive.NONCE_BYTES)
        val header = EncryptedArchive.MAGIC.encodeToByteArray() + byteArrayOf(1) + salt + nonce
        val key = crypto.deriveKey(password, salt)

        val sealed = ByteArraySink()
        sealed.write(header, 0, header.size)
        crypto.seal(ByteArraySource(base), sealed, key, nonce, header)

        val opened = ByteArraySink()
        archive.openChain(ByteArraySource(sealed.bytes()), opened, password) { _, _ ->
            error("a version-1 archive carries no changes")
        }

        assertContentEquals(base, opened.bytes(), "an archive written before the chain must still open")
    }

    /** A version this build does not read is refused, and the message says which. */
    @Test
    fun `an unknown version is refused by name`() {
        val container = EncryptedArchive.MAGIC.encodeToByteArray() +
            byteArrayOf(99) +
            ByteArray(EncryptedArchive.SALT_BYTES) +
            ByteArray(EncryptedArchive.NONCE_BYTES)

        val error = assertFailsWith<BackupError.UnsupportedArchiveVersion> {
            archive.open(container, password)
        }
        assertEquals(99, error.version, "the message must name the version the archive has")
        assertEquals(EncryptedArchive.VERSION, error.currentVersion, "and the one this build writes")
    }
}
