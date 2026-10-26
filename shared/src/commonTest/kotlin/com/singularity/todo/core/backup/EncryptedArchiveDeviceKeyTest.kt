package com.singularity.todo.core.backup

import com.singularity.todo.core.files.ByteArraySink
import com.singularity.todo.core.files.ByteArraySource
import com.singularity.todo.core.security.FakeArchiveCrypto
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The version-3 container: a chain sealed under a **device key**.
 *
 * ## What this is about
 *
 * A scheduled export has no person, and therefore no password — but the archive
 * still has to be openable by the person who owns the account. So the container
 * carries the key it was sealed with, in a form only the password can open: the
 * key block.
 *
 * The three properties that make that work, and that this file holds to:
 *
 * 1. the password opens the container, and the content comes out;
 * 2. a **different** password does not — the block is what the password opens,
 *    and a wrong one fails there;
 * 3. the container says it is a version 3, so a build that does not know the
 *    version refuses it by name rather than misreading it.
 *
 * The cryptography here is the fake: what is proved is the **container** — the
 * header, the key block, the routing. The real AES-GCM round trip is proved
 * against the real cipher in the golden test on the JVM.
 */
@Tag("fast")
class EncryptedArchiveDeviceKeyTest {

    private val crypto = FakeArchiveCrypto()
    private val archive = EncryptedArchive(crypto)

    private val password = "correct horse".toCharArray()
    private val deviceKey = ByteArray(ArchiveFormat.DEVICE_KEY_BYTES) { it.toByte() }

    /** The block the device made once, under the password. */
    private val block: ArchiveKeyBlock by lazy { archive.wrapDeviceKey(deviceKey, password) }

    @Test
    fun `the password opens a container sealed under the device key`() {
        val plain = "the archive's own bytes".encodeToByteArray()
        val sealed = ByteArraySink()
        archive.sealChain(
            base = ByteArraySource(plain),
            entries = emptyList(),
            sink = sealed,
            deviceKey = deviceKey,
            keyBlock = block,
        )

        val opened = archive.open(sealed.bytes(), password)

        assertContentEquals(plain, opened)
    }

    @Test
    fun `another password does not open it`() {
        val sealed = ByteArraySink()
        archive.sealChain(
            base = ByteArraySource("the archive's own bytes".encodeToByteArray()),
            entries = emptyList(),
            sink = sealed,
            deviceKey = deviceKey,
            keyBlock = block,
        )

        // The failure is named: a person who typed the wrong password must not be
        // told the archive is damaged.
        assertFailsWith<ArchiveEnvelopeError.WrongPassword> {
            archive.open(sealed.bytes(), "a different password".toCharArray())
        }
    }

    @Test
    fun `the container says it is a version three`() {
        val sealed = ByteArraySink()
        archive.sealChain(
            base = ByteArraySource("bytes".encodeToByteArray()),
            entries = emptyList(),
            sink = sealed,
            deviceKey = deviceKey,
            keyBlock = block,
        )

        val bytes = sealed.bytes()
        val version = bytes[ArchiveFormat.MAGIC.length].toInt() and 0xFF

        assertTrue(
            version == ArchiveFormat.DEVICE_KEY_VERSION,
            "the container says $version, and this build writes ${ArchiveFormat.DEVICE_KEY_VERSION}",
        )
    }

    @Test
    fun `the block has a salt of its own, and still carries the same key`() {
        // The block is wrapped once per device and carried in every archive, so
        // it cannot be tied to one archive's salt: it has a salt of its own.
        val other = archive.wrapDeviceKey(deviceKey, password)

        assertTrue(!other.salt.contentEquals(block.salt), "two wrappings share a salt")

        // And it is the *same* key underneath: a container sealed with either
        // block opens under the same password and gives the same archive back.
        val withFirst = sealUnder(block)
        val withSecond = sealUnder(other)

        assertContentEquals(
            archive.open(withFirst, password),
            archive.open(withSecond, password),
            "two wrappings of one key open the same archive",
        )
    }

    /** A container sealed under the device key, with [keyBlock] in its header. */
    private fun sealUnder(keyBlock: ArchiveKeyBlock): ByteArray {
        val sealed = ByteArraySink()
        archive.sealChain(
            base = ByteArraySource("the archive's own bytes".encodeToByteArray()),
            entries = emptyList(),
            sink = sealed,
            deviceKey = deviceKey,
            keyBlock = keyBlock,
        )
        return sealed.bytes()
    }

    @Test
    fun `a container without a key block is refused when it says it has one`() {
        // A version-3 header whose key block does not open is a broken chain, and
        // it says so by name instead of returning an empty archive.
        val sealed = ByteArraySink()
        archive.sealChain(
            base = ByteArraySource("bytes".encodeToByteArray()),
            entries = emptyList(),
            sink = sealed,
            deviceKey = deviceKey,
            keyBlock = block,
        )
        val bytes = sealed.bytes()

        // The key block is the salt, the nonce and the ciphertext: changing one
        // byte of the ciphertext makes the tag fail, and the tag failing is the
        // password being wrong — which is what a wrong block looks like.
        val tampered = bytes.copyOf()
        val ciphertextStart = ArchiveFormat.HEADER_BYTES_V3 - ArchiveFormat.KEY_BLOCK_BYTES +
            ArchiveFormat.SALT_BYTES + ArchiveFormat.NONCE_BYTES
        tampered[ciphertextStart] = (tampered[ciphertextStart] + 1).toByte()

        assertFailsWith<ArchiveEnvelopeError.WrongPassword> {
            archive.open(tampered, password)
        }
    }
}
