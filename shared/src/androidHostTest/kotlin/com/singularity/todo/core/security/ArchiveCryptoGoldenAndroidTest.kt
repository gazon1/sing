package com.singularity.todo.core.security

import com.singularity.todo.core.backup.EncryptedArchive
import com.singularity.todo.test.fakes.ArchiveCryptoGolden
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
        val header = container.copyOfRange(0, EncryptedArchive.HEADER_BYTES)
        val sealed = crypto.seal(key, ArchiveCryptoGolden.nonce, ArchiveCryptoGolden.plaintext, header)

        assertEquals(
            "the phone must seal the golden bytes exactly",
            ArchiveCryptoGolden.SEALED_HEX,
            sealed.toHex(),
        )
    }
}
