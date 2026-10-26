package com.singularity.todo.core.backup

import com.singularity.todo.core.security.FakeArchiveCrypto
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag

/**
 * The encrypted archive's container: what it looks like, and what it refuses.
 *
 * ## What is worth a test here
 *
 * The container is the project's own decision — the header, the salt, the nonce,
 * the version — and it lives above the cryptography, so it can be checked without
 * a cipher and without a platform. Four things can go wrong in it, and each of
 * them has a consequence the person would feel:
 *
 * - the bytes that reach the bucket are the archive in the clear (the whole
 *   point of the feature);
 * - the header is not authenticated, so a salt or a nonce could be swapped and
 *   the archive would still open — or, worse, would open with the wrong content;
 * - a wrong password is reported as a broken archive instead of as a wrong
 *   password;
 * - an archive that is not encrypted — one from before the feature — is reported
 *   as a corrupt download instead of as "this build cannot read it".
 *
 * ## Why the cryptography is faked
 *
 * The real one is a platform implementation and cannot be reached from
 * `commonTest`; the round trip against the real AES-GCM is proved by
 * `JvmArchiveCryptoTest`, on the JVM. What is checked here is the container.
 */
@Tag("fast")
class EncryptedArchiveTest {

    private val crypto = FakeArchiveCrypto()
    private val archive = EncryptedArchive(crypto)

    private val plaintext = "the-archive-bytes".encodeToByteArray()
    private val password = "correct horse".toCharArray()

    /**
     * The archive in the bucket is not the archive.
     *
     * This is the whole feature: what leaves the device is ciphertext, and the
     * plaintext is nowhere in the container.
     */
    @Test
    fun `the sealed archive is not the plaintext`() {
        val sealed = archive.seal(plaintext, password)

        assertNotEquals(
            plaintext.decodeToString(),
            sealed.decodeToString(),
            "the bytes that leave the device must not be the archive in the clear",
        )
        assertTrue(
            sealed.copyOfRange(0, ArchiveFormat.MAGIC.length).decodeToString() == ArchiveFormat.MAGIC,
            "the container must say what it is: ${sealed.copyOfRange(0, 8).decodeToString()}",
        )
    }

    /**
     * The archive comes back as it went in.
     *
     * The round trip is the feature's contract: what the person pushed is what
     * the person gets back.
     */
    @Test
    fun `the sealed archive opens with the same password`() {
        val sealed = archive.seal(plaintext, password)

        val opened = archive.open(sealed, password)

        assertContentEquals(plaintext, opened, "the archive must come back as it went in")
    }

    /**
     * The header is the authenticated data.
     *
     * The salt and the nonce travel in the clear, and a header that is not
     * covered by the tag could be swapped: the archive would then open with the
     * wrong content, or not open at all. The tag has to cover the header, or the
     * container is only pretending to be safe.
     */
    @Test
    fun `the header is the authenticated data`() {
        archive.seal(plaintext, password)

        val aad = crypto.lastAad
        assertTrue(aad != null, "the seal must have been given something to authenticate")
        assertEquals(
            // The base's authenticated data is the whole version-2 header: the prefix
            // and the head, so the chain's own count cannot be rewritten either.
            ArchiveFormat.HEADER_BYTES_V2,
            aad.size,
            "the authenticated data is the header, and nothing else",
        )
    }

    /**
     * Two archives under one password are not the same bytes.
     *
     * The salt and the nonce are fresh for every archive. Two archives that
     * shared them would be two files under one key, and the second one would
     * tell an attacker that the first one was there.
     */
    @Test
    fun `two archives under one password differ`() {
        val first = archive.seal(plaintext, password)
        val second = archive.seal(plaintext, password)

        assertNotEquals(
            first.decodeToString(),
            second.decodeToString(),
            "the salt and the nonce must be fresh for every archive",
        )
    }

    /**
     * A wrong password says so.
     *
     * "The password does not open the archive" and "the archive is broken" are
     * different sentences, and the person who typed the wrong password needs the
     * first one.
     */
    @Test
    fun `a wrong password is reported as a wrong password`() {
        val sealed = archive.seal(plaintext, password)

        val error = assertFailsWith<ArchiveEnvelopeError.WrongPassword> {
            archive.open(sealed, "wrong horse".toCharArray())
        }
        assertTrue(
            error.message?.contains("password") == true,
            "the message must name the password: ${error.message}",
        )
    }

    /**
     * Bytes that are not an encrypted archive are refused by name.
     *
     * An archive pushed before the encryption existed is a plain zip. Reading it
     * as one would fail as a checksum mismatch, which names a cause that was
     * never in play.
     */
    @Test
    fun `bytes that are not an encrypted archive are refused`() {
        val error = assertFailsWith<ArchiveEnvelopeError.NotEncrypted> {
            archive.open("PK\u0003\u0004a-plain-zip".encodeToByteArray(), password)
        }
        assertTrue(
            error.message?.contains("not encrypted") == true,
            "the message must say what is wrong: ${error.message}",
        )
    }

    /**
     * A container from a newer build is refused by version.
     *
     * The version is the one part of the header that says how to read the rest,
     * and a build that does not understand it must say so rather than guess.
     */
    @Test
    fun `a newer container version is refused`() {
        val sealed = archive.seal(plaintext, password)
        val newer = sealed.copyOf().also {
            it[ArchiveFormat.MAGIC.length] = (ArchiveFormat.LATEST_VERSION + 1).toByte()
        }

        val error = assertFailsWith<ArchiveEnvelopeError.UnsupportedArchiveVersion> {
            archive.open(newer, password)
        }
        assertEquals(ArchiveFormat.LATEST_VERSION + 1, error.version)
    }

    /**
     * An archive without a password is not encrypted.
     *
     * The empty password is refused rather than quietly used: an archive pushed
     * with an empty key would be readable by anyone who has the bucket.
     */
    @Test
    fun `an empty password is refused`() {
        assertFailsWith<ArchiveEnvelopeError.MissingPassword> {
            archive.seal(plaintext, CharArray(0))
        }
    }
}
