package com.singularity.todo.core.security

import com.singularity.todo.core.backup.ArchiveFormat
import com.singularity.todo.core.backup.EncryptedArchive
import com.singularity.todo.core.files.ByteArraySink
import com.singularity.todo.core.files.ByteArraySource
import com.singularity.todo.test.fakes.ArchiveCryptoGolden
import com.singularity.todo.test.fakes.buildGoldenChain
import com.singularity.todo.test.fakes.buildGoldenDeviceKeyContainer
import com.singularity.todo.test.fakes.hexToBytes
import com.singularity.todo.test.fakes.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The phone's cryptography, against the bytes the desktop produced.
 *
 * ## What this is the only local test for
 *
 * `AndroidArchiveCrypto` had no test at all. It is a copy of
 * `JvmArchiveCrypto`, and a copy is a number waiting to drift: an archive
 * written by the desktop would stop opening on the phone, and the message the
 * app would give is "the password does not open it" — which sends the person
 * looking for a wrong password instead of a changed constant.
 *
 * Nothing else in the project could have caught that. The container is proved
 * in `commonTest` with a faked cryptography, and the desktop's real cipher is
 * proved in `jvmTest`; the phone's half was the one with no witness. This
 * class is that witness: it opens the archive the desktop's JDK produced, and
 * it seals the same bytes itself.
 *
 * ## Why a JUnit4 class
 *
 * `androidHostTest` runs Robolectric, which is a JUnit4 runner, discovered
 * through the Vintage engine. JUnit4 has no `@Tag`, so this class carries no
 * `fast`/`slow` tag and is not picked by a tagged selection — it runs when the
 * whole `androidHostTest` source set runs.
 *
 * ## Why no Robolectric runner
 *
 * Nothing here touches the Android framework: the cryptography is
 * `javax.crypto`, which the JVM provides. The class is in `androidHostTest`
 * only because `AndroidArchiveCrypto` lives in `androidMain`, and that is
 * where the Android half of the archive is.
 */
class ArchiveCryptoGoldenAndroidTest {

    private val crypto = AndroidArchiveCrypto()
    private val archive = EncryptedArchive(crypto)

    /** The phone derives the same key the golden archive was sealed under. */
    @Test
    fun `the phone derives the golden key`() {
        val key = crypto.deriveKey(
            ArchiveCryptoGolden.PASSWORD.toCharArray(),
            ArchiveCryptoGolden.salt,
        )

        assertEquals(
            "the key derivation must not drift between the platforms",
            ArchiveCryptoGolden.KEY_HEX,
            key.toHex(),
        )
    }

    /**
     * The phone opens the archive the desktop wrote, and writes the same one.
     *
     * This is the round trip the feature depends on: the desktop pushes an
     * archive, the phone restores it. Here the desktop's bytes are the input
     * and the phone is the one reading them.
     */
    @Test
    fun `the phone agrees with the golden archive`() {
        val container = hexToBytes(ArchiveCryptoGolden.CONTAINER_HEX)

        assertArrayEquals(
            "the phone must open the archive the desktop wrote",
            ArchiveCryptoGolden.plaintext,
            archive.open(container, ArchiveCryptoGolden.PASSWORD.toCharArray()),
        )

        val key = crypto.deriveKey(
            ArchiveCryptoGolden.PASSWORD.toCharArray(),
            ArchiveCryptoGolden.salt,
        )
        val header = container.copyOfRange(0, ArchiveFormat.HEADER_BYTES)
        val sealed = crypto.seal(key, ArchiveCryptoGolden.nonce, ArchiveCryptoGolden.plaintext, header)

        assertEquals(
            "the phone must seal the golden bytes exactly",
            ArchiveCryptoGolden.SEALED_HEX,
            sealed.toHex(),
        )
    }

    /**
     * The phone agrees with the golden chain.
     *
     * The chain is the container an incremental backup is written in, and it has
     * three rules the version-1 container does not: the head that counts the
     * changes, the frame around each change, and the change's own authenticated
     * data — the header's prefix plus the change's place. A rule that differs
     * between the platforms reads as a **broken chain**, not as a mismatch, and
     * a broken chain is a refusal to restore.
     *
     * So the phone builds the golden chain and opens it, and the desktop's test
     * does the same with the same bytes.
     */
    @Test
    fun `the phone agrees with the golden chain`() {
        val key = crypto.deriveKey(
            ArchiveCryptoGolden.PASSWORD.toCharArray(),
            ArchiveCryptoGolden.chainSalt,
        )

        assertEquals(
            "the key derivation must not drift between the platforms",
            ArchiveCryptoGolden.CHAIN_KEY_HEX,
            key.toHex(),
        )

        assertEquals(
            "the phone must build the golden chain exactly",
            ArchiveCryptoGolden.CHAIN_CONTAINER_HEX,
            buildGoldenChain(crypto, key).toHex(),
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

        assertArrayEquals(
            "the chain's base must come back as it went in",
            ArchiveCryptoGolden.chainBase,
            base.bytes(),
        )
        assertEquals("the chain carries one change", 1, changes.size)
        assertArrayEquals(ArchiveCryptoGolden.chainEntry, changes[0])
    }

    /**
     * The phone agrees with the golden device-key container.
     *
     * This is the container a **scheduled** export writes: the content is sealed
     * under the device key, and the header carries that key wrapped under the
     * password. The phone has to unwrap the block and read the content, and the
     * desktop has to produce exactly these bytes.
     *
     * A disagreement here reads as "the password does not open it" — the one
     * message that sends a person looking in the wrong place.
     */
    @Test
    fun `the phone agrees with the golden device-key container`() {
        assertEquals(
            "the phone must build the golden device-key container exactly",
            ArchiveCryptoGolden.DEVICE_KEY_CONTAINER_HEX,
            buildGoldenDeviceKeyContainer(crypto).toHex(),
        )

        val base = ByteArraySink()
        archive.openChain(
            source = ByteArraySource(hexToBytes(ArchiveCryptoGolden.DEVICE_KEY_CONTAINER_HEX)),
            sink = base,
            password = ArchiveCryptoGolden.PASSWORD.toCharArray(),
        ) { _, _ -> }

        assertArrayEquals(
            "the container's base must come back as it went in",
            ArchiveCryptoGolden.deviceBase,
            base.bytes(),
        )
    }
}
