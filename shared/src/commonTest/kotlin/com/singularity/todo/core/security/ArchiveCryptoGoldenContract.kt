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
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * What each platform's archive cryptography must agree with, once.
 *
 * ## Why one contract and not two copies
 *
 * The archive is written on one platform and read on the other: pushed from the
 * desktop, restored on the phone. `JvmArchiveCrypto` and `AndroidArchiveCrypto`
 * are copies of each other, because `javax.crypto` cannot live in `commonMain` —
 * so the only thing that says the two agree is a test that runs the *same*
 * questions against both.
 *
 * That test used to exist twice: `ArchiveCryptoGoldenJvmTest` and
 * `ArchiveCryptoGoldenAndroidTest` were the same four cases with the platform's
 * crypto substituted and the assertion library swapped. A copy of a test is a
 * copy of a rule, and a copy is a rule that drifts: the next case could be added
 * to one half, or a message corrected in one half, and nothing would say so —
 * which is exactly the failure the golden exists to catch, moved up a level.
 *
 * So the questions live here, in `commonTest`, which both platform test source
 * sets compile against, and each platform supplies only the thing that is
 * genuinely its own: its `ArchiveCrypto`. A platform that cannot answer one of
 * these cases is a red test on that platform, not a sentence missing from a copy.
 *
 * ## What a subclass has to add
 *
 * Two subclasses exist, and they are deliberately thin:
 *
 * - `ArchiveCryptoGoldenJvmTest` supplies `JvmArchiveCrypto` and carries the
 *   `fast` tag the JVM lane selects on;
 * - `ArchiveCryptoGoldenAndroidTest` supplies `AndroidArchiveCrypto` and lives in
 *   `androidHostTest`, which is exempt from the tag filter and lists the class by
 *   name in `shared/build.gradle.kts`.
 *
 * ## Why this file is not called `…ContractTest.kt`
 *
 * `TestTagCoverageTest` requires every class in a tag-filtered test source set
 * that declares a test method to declare a tag — and a tag on this class would
 * be a lie, because it never runs on its own. The gate scans files named
 * `*Test.kt`, an abstract contract is not a test class, and the name keeps the
 * two facts in step. Adding a `Test` suffix to this file's name
 * turns that gate red, which is the correct outcome but a confusing one.
 *
 * ## What is pinned
 *
 * [ArchiveCryptoGolden] holds bytes the JDK produced. Each case below opens
 * them, and — where the container is what a platform *writes* — seals them
 * again and compares the bytes. Opening alone would let a platform that reads
 * correctly and writes differently pass, and the desktop is the writer as often
 * as the phone is.
 *
 * The key derivation is deliberately slow (210 000 iterations of PBKDF2), so
 * this holds four cases and no more.
 */
abstract class ArchiveCryptoGoldenContract {
    /** The platform's cryptography: the one thing these cases cannot be shared over. */
    protected abstract val crypto: ArchiveCrypto

    /** The container above [crypto], which is shared code and therefore not per-platform. */
    private fun archive(): EncryptedArchive = EncryptedArchive(crypto)

    /** The platform derives the same key the golden archive was sealed under. */
    @Test
    fun `the golden key is derived`() {
        val key =
            crypto.deriveKey(
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
     * The platform opens the golden archive, and seals it again to the same bytes.
     *
     * Opening proves the container and the cipher; sealing proves that this
     * platform is not merely able to read the golden archive but produces it —
     * which is what makes it worth pinning at all.
     */
    @Test
    fun `the golden archive is opened and sealed`() {
        val container = hexToBytes(ArchiveCryptoGolden.CONTAINER_HEX)

        assertContentEquals(
            ArchiveCryptoGolden.plaintext,
            archive().open(container, ArchiveCryptoGolden.PASSWORD.toCharArray()),
            "the archive the golden bytes describe must open on every platform",
        )

        val key =
            crypto.deriveKey(
                ArchiveCryptoGolden.PASSWORD.toCharArray(),
                ArchiveCryptoGolden.salt,
            )
        val header = container.copyOfRange(0, ArchiveFormat.HEADER_BYTES)
        val sealed = crypto.seal(key, ArchiveCryptoGolden.nonce, ArchiveCryptoGolden.plaintext, header)

        assertEquals(
            ArchiveCryptoGolden.SEALED_HEX,
            sealed.toHex(),
            "the platform must seal the golden bytes exactly",
        )
    }

    /**
     * The platform builds the golden chain, and opens it.
     *
     * The chain is the container an incremental backup is written in, and it has
     * three things the version-1 container does not: a **head** that says how many
     * changes there are, a **frame** around every change, and the changes
     * themselves. Each of those is a rule the two platforms have to share, and a
     * rule that differs by one byte between them reads as a broken chain — not as
     * a mismatch — which sends the person looking for damage that is not there.
     */
    @Test
    fun `the golden chain is built and opened`() {
        val key =
            crypto.deriveKey(
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
            "the platform must build the golden chain exactly",
        )

        val base = ByteArraySink()
        val changes = mutableListOf<ByteArray>()
        archive().openChain(
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

    /**
     * The platform builds the golden device-key container, and opens it.
     *
     * This is the container a **scheduled** export writes: the content is sealed
     * under the device key, and the header carries that key wrapped under the
     * password. So the container has three rules the version-2 one does not — the
     * key block's layout, the derivation of the content key from the device key,
     * and the authenticated data of the wrapping — and each of them is a place
     * where the two platforms can disagree.
     *
     * A disagreement here reads as "the password does not open it", and sends the
     * person to retype a password that was never the problem.
     */
    @Test
    fun `the golden device-key container is built and opened`() {
        assertEquals(
            ArchiveCryptoGolden.DEVICE_KEY_CONTAINER_HEX,
            buildGoldenDeviceKeyContainer(crypto).toHex(),
            "the platform must build the golden device-key container exactly",
        )

        val base = ByteArraySink()
        archive().openChain(
            source = ByteArraySource(hexToBytes(ArchiveCryptoGolden.DEVICE_KEY_CONTAINER_HEX)),
            sink = base,
            password = ArchiveCryptoGolden.PASSWORD.toCharArray(),
        ) { _, _ -> }

        assertContentEquals(
            ArchiveCryptoGolden.deviceBase,
            base.bytes(),
            "the container's base must come back as it went in",
        )
    }
}
