package com.singularity.todo.core.security

import com.singularity.todo.core.backup.ArchiveFormat
import com.singularity.todo.core.backup.EncryptedArchive
import com.singularity.todo.core.files.ByteArraySink
import com.singularity.todo.core.files.ByteArraySource
import com.singularity.todo.test.fakes.ArchiveCryptoGolden
import com.singularity.todo.test.fakes.buildGoldenChain
import com.singularity.todo.test.fakes.hexToBytes
import com.singularity.todo.test.fakes.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import org.junit.jupiter.api.Tag

/**
 * The desktop's cryptography, against the bytes the phone has to agree with.
 *
 * ## What this pins
 *
 * The archive is written on one platform and read on the other, and the two
 * implementations are copies of each other — `javax.crypto` cannot live in
 * `commonMain`. [ArchiveCryptoGolden] holds an archive the JDK produced; this
 * test says the desktop still produces exactly that, and the Android test in
 * `androidHostTest` says the phone opens exactly that.
 *
 * Between them the two tests are the answer to the question the archive's whole
 * design rests on: does an archive written here open there? Without them the
 * only evidence is a person's backup failing to restore, with a message that
 * points at the password.
 *
 * The key derivation is deliberately slow (210 000 iterations of PBKDF2), so
 * the file holds two cases and no more.
 */
@Tag("fast")
class ArchiveCryptoGoldenJvmTest {

    private val crypto = JvmArchiveCrypto()
    private val archive = EncryptedArchive(crypto)

    /** The desktop derives the same key the golden archive was sealed under. */
    @Test
    fun `the desktop derives the golden key`() {
        val key = crypto.deriveKey(
            ArchiveCryptoGolden.PASSWORD.toCharArray(),
            ArchiveCryptoGolden.salt,
        )

        assertEquals(
            ArchiveCryptoGolden.KEY_HEX,
            key.toHex(),
            "the key derivation must not drift between the platforms",
        )
    }

    /**
     * The desktop opens the golden archive, and seals it again to the same bytes.
     *
     * Opening proves the container and the cipher; sealing proves that the
     * desktop is not merely able to read the golden archive but produces it —
     * which is what makes it worth pinning at all.
     */
    @Test
    fun `the desktop agrees with the golden archive`() {
        val container = hexToBytes(ArchiveCryptoGolden.CONTAINER_HEX)

        assertContentEquals(
            ArchiveCryptoGolden.plaintext,
            archive.open(container, ArchiveCryptoGolden.PASSWORD.toCharArray()),
            "the desktop must open the archive the golden bytes describe",
        )

        val key = crypto.deriveKey(
            ArchiveCryptoGolden.PASSWORD.toCharArray(),
            ArchiveCryptoGolden.salt,
        )
        val header = container.copyOfRange(0, ArchiveFormat.HEADER_BYTES)
        val sealed = crypto.seal(key, ArchiveCryptoGolden.nonce, ArchiveCryptoGolden.plaintext, header)

        assertEquals(
            ArchiveCryptoGolden.SEALED_HEX,
            sealed.toHex(),
            "the desktop must seal the golden bytes exactly",
        )
    }

    /**
     * The desktop agrees with the golden chain.
     *
     * The chain is the container an incremental backup is written in, and it has
     * three things the version-1 container does not: a **head** that says how many
     * changes there are, a **frame** around every change, and the changes
     * themselves. Each of those is a rule the two platforms have to share, and a
     * rule that differs by one byte between them reads as a broken chain — not as
     * a mismatch — which sends the person looking for damage that is not there.
     *
     * So the chain is pinned the way the version-1 archive is: the desktop builds
     * the golden bytes, opens them, and gets the base and the change back.
     */
    @Test
    fun `the desktop agrees with the golden chain`() {
        val key = crypto.deriveKey(
            ArchiveCryptoGolden.PASSWORD.toCharArray(),
            ArchiveCryptoGolden.chainSalt,
        )

        assertEquals(
            ArchiveCryptoGolden.CHAIN_KEY_HEX,
            key.toHex(),
            "the key derivation must not drift between the platforms",
        )

        assertEquals(
            ArchiveCryptoGolden.CHAIN_CONTAINER_HEX,
            buildGoldenChain(crypto, key).toHex(),
            "the desktop must build the golden chain exactly",
        )

        val base = ByteArraySink()
        val changes = mutableListOf<ByteArray>()
        archive.openChain(
            source = ByteArraySource(hexToBytes(ArchiveCryptoGolden.CHAIN_CONTAINER_HEX)),
            sink = base,
            password = ArchiveCryptoGolden.PASSWORD.toCharArray(),
        ) { _, plain ->
            changes.add(plain)
        }

        assertContentEquals(
            ArchiveCryptoGolden.chainBase,
            base.bytes(),
            "the chain's base must come back as it went in",
        )
        assertEquals(1, changes.size, "the chain carries one change")
        assertContentEquals(ArchiveCryptoGolden.chainEntry, changes[0])
    }
}
