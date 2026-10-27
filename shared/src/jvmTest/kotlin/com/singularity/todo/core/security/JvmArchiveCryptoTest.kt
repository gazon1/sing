package com.singularity.todo.core.security

import com.singularity.todo.core.backup.ArchiveEnvelopeError
import com.singularity.todo.core.backup.ArchiveFormat
import com.singularity.todo.core.backup.EncryptedArchive
import com.singularity.todo.core.files.ByteArraySink
import com.singularity.todo.core.files.ByteArraySource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag

/**
 * The real cryptography, on the JVM.
 *
 * ## Why this test and not only the faked one
 *
 * The container is proved in `commonTest` with a faked cryptography, because that
 * is where the container lives. What the fake cannot prove is that the *real*
 * cipher agrees with the container: that AES-GCM with the header as the
 * authenticated data opens what the container sealed, that a wrong password fails
 * at the tag, and that two archives under one password do not share a salt and a
 * nonce.
 *
 * That is what this file is for, and it is the JVM's own providers — the same
 * ones Android ships, so an archive written on the desktop is read on the phone.
 *
 * The key derivation is deliberately slow (210 000 iterations of PBKDF2), so the
 * file holds a handful of cases and no more.
 */
@Tag("fast")
class JvmArchiveCryptoTest {

    private val crypto = JvmArchiveCrypto()
    private val archive = EncryptedArchive(crypto)

    private val plaintext = "the-archive-bytes".encodeToByteArray()
    private val password = "correct horse".toCharArray()

    /** The archive comes back as it went in, through the real cipher. */
    @Test
    fun `the real cipher opens what the container sealed`() {
        val sealed = archive.seal(plaintext, password)

        val opened = archive.open(sealed, password)

        assertContentEquals(plaintext, opened, "the archive must come back as it went in")
    }

    /** The bytes that leave the device are not the archive. */
    @Test
    fun `the real cipher does not leave the plaintext in the container`() {
        val sealed = archive.seal(plaintext, password)

        assertNotEquals(
            plaintext.decodeToString(),
            sealed.decodeToString(),
            "the container must not carry the archive in the clear",
        )
        assertTrue(
            sealed.copyOfRange(0, ArchiveFormat.MAGIC.length).decodeToString() == ArchiveFormat.MAGIC,
            "the container must say what it is",
        )
    }

    /** A wrong password fails at the tag, not with garbage. */
    @Test
    fun `the real cipher refuses a wrong password`() {
        val sealed = archive.seal(plaintext, password)

        assertFailsWith<ArchiveEnvelopeError.WrongPassword> {
            archive.open(sealed, "wrong horse".toCharArray())
        }
    }

    /**
     * Two archives under one password are two different files.
     *
     * The salt and the nonce are fresh for every archive, so the same archive
     * pushed twice is not the same bytes twice.
     */
    @Test
    fun `the real cipher gives every archive its own salt and nonce`() {
        val first = archive.seal(plaintext, password)
        val second = archive.seal(plaintext, password)

        assertNotEquals(
            first.decodeToString(),
            second.decodeToString(),
            "the salt and the nonce must be fresh for every archive",
        )
    }

    /** A tampered header does not open: the header is covered by the tag. */
    @Test
    fun `the real cipher refuses a tampered header`() {
        val sealed = archive.seal(plaintext, password)
        val tampered = sealed.copyOf().also { copy ->
            val last = ArchiveFormat.HEADER_BYTES - 1
            copy[last] = (copy[last] + 1).toByte()
        }

        assertFailsWith<ArchiveEnvelopeError.WrongPassword> {
            archive.open(tampered, password)
        }
    }

    /**
     * The streaming path produces the same bytes as the byte-shaped one.
     *
     * The archive's format does not depend on how it was fed: a container sealed
     * in pieces has to be byte-for-byte the container sealed in one go, or the
     * two would be two formats — and the one written by the phone would not open
     * on the desktop.
     */
    @Test
    fun `the streaming cipher produces the same bytes as the byte-shaped one`() {
        val salt = "the-salt".encodeToByteArray()
        val nonce = "the-nonce".encodeToByteArray()
        val aad = "the-header".encodeToByteArray()
        val key = crypto.deriveKey(password, salt)

        val byBytes = crypto.seal(key, nonce, plaintext, aad)
        val streamed = ByteArraySink()
        crypto.seal(ByteArraySource(plaintext), streamed, key, nonce, aad)

        assertContentEquals(byBytes, streamed.bytes(), "the streaming path must not change the bytes")
    }

    /** And the streaming path opens what it sealed. */
    @Test
    fun `the streaming cipher opens what it sealed`() {
        val salt = "the-salt".encodeToByteArray()
        val nonce = "the-nonce".encodeToByteArray()
        val aad = "the-header".encodeToByteArray()
        val key = crypto.deriveKey(password, salt)

        val sealed = ByteArraySink()
        crypto.seal(ByteArraySource(plaintext), sealed, key, nonce, aad)
        val opened = ByteArraySink()
        crypto.open(ByteArraySource(sealed.bytes()), opened, key, nonce, aad)

        assertContentEquals(plaintext, opened.bytes(), "the archive must come back as it went in")
    }
}
