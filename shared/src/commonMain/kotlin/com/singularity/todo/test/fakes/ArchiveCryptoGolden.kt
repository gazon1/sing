package com.singularity.todo.test.fakes

import com.singularity.todo.core.backup.ArchiveCryptoSession.Companion.blockKeyAad
import com.singularity.todo.core.backup.ArchiveFormat
import com.singularity.todo.core.backup.ArchiveKeyBlock
import com.singularity.todo.core.backup.ChainHead
import com.singularity.todo.core.backup.asPasswordChars
import com.singularity.todo.core.backup.toBytes
import com.singularity.todo.core.security.ArchiveCrypto

/**
 * The archive both platforms must agree on, byte for byte.
 *
 * ## What this is for
 *
 * The archive is written by one platform and read by the other: pushed from the
 * desktop, restored on the phone. The two cryptography implementations are
 * copies of each other, because `javax.crypto` cannot live in `commonMain`, and
 * a copy is a number waiting to drift.
 *
 * A drift is invisible until it is not: the archive written by the desktop stops
 * opening on the phone, and the message it produces is "the password does not
 * open it". The person then goes looking for the wrong thing — a wrong password,
 * a lost archive — and the real cause, one changed constant, is nowhere in sight.
 *
 * So the archive is pinned here. These bytes were produced by the JDK on the
 * desktop; the desktop's test checks that it still produces them, and the
 * Android test checks that it opens them. When the two platforms disagree, a
 * test is red instead of a backup is lost.
 *
 * The archive is a full container — header, ciphertext and tag — so the pinned
 * bytes cover the format as well as the cipher: the header's layout, the salt's
 * place, the nonce's place, the version byte and the tag are all in them.
 *
 * The password and the salt are fixed, so the derived key is fixed too, and the
 * key derivation is part of what the two platforms have to agree on: PBKDF2 on
 * Android is not the JDK's implementation of PBKDF2, and nothing but this test
 * says the two produce the same bytes.
 *
 * ## Where it lives, and why not in `commonTest`
 *
 * `commonTest` is not visible to `androidHostTest` — the source set that runs
 * the Android implementation's own test — so a golden kept there would pin one
 * platform and leave the other unpinned, which is the half that matters. The
 * shared test fixtures live in `commonMain/test/fakes` for the same reason, and
 * this file is one of them.
 */
object ArchiveCryptoGolden {

    /** The password the golden archive was sealed under. */
    const val PASSWORD = "correct horse battery staple"

    /** The salt the golden archive was sealed with. */
    val salt: ByteArray = hexToBytes("000102030405060708090a0b0c0d0e0f")

    /** The nonce the golden archive was sealed with. */
    val nonce: ByteArray = hexToBytes("101112131415161718191a1b")

    /** What the golden archive holds. */
    val plaintext: ByteArray = "The archive the phone has to open.".encodeToByteArray()

    /** The key the password and the salt derive into. */
    const val KEY_HEX = "18465cade096c185b5f60765891fca3f74477e23fd9b6fc88faf7831a91753ac"

    /** The ciphertext and the tag, without the header. */
    const val SEALED_HEX =
        "4d87d0d9d1e009f1aef589fef168f3ce9327d5c15611ab21842f518d3352198a69f4d27661d7fd02f1f2188149367cb442ec"

    /** The whole archive: header, ciphertext and tag. */
    const val CONTAINER_HEX =
        "5347424b01000102030405060708090a0b0c0d0e0f101112131415161718191a1b" +
            "4d87d0d9d1e009f1aef589fef168f3ce9327d5c15611ab21842f518d3352198a69f4d27661d7fd02f1f2188149367cb442ec"

    // ── The version-2 chain ────────────────────────────────────────────────────
    //
    // The container above is a version-1 archive: one sealed stream, nothing else.
    // The chain below is what an incremental backup is — a base and the changes
    // that follow it — and it is pinned for the same reason, and for one more:
    // the chain's framing is the part that a reader has to get exactly right, and
    // a framing that differs by one byte between the platforms reads as a broken
    // chain rather than as a mismatch.

    /** The salt the golden chain was sealed with. */
    val chainSalt: ByteArray = hexToBytes("1f1e1d1c1b1a19181716151413121110")

    /** The nonce of the golden chain's base. */
    val chainNonce: ByteArray = hexToBytes("202122232425262728292a2b")

    /** The nonce of the golden chain's one change. */
    val chainEntryNonce: ByteArray = hexToBytes("303132333435363738393a3b")

    /** What the golden chain's base holds. */
    val chainBase: ByteArray = "The base the golden chain carries.".encodeToByteArray()

    /** What the golden chain's one change holds. */
    val chainEntry: ByteArray = "The change the golden chain carries.".encodeToByteArray()

    /** The key the password and the chain's salt derive into. */
    const val CHAIN_KEY_HEX = "ae78f23208655cf87f22c7973e02e82644178f32253b5199658d562257d51356"

    /** The whole chain: header, the change's frame and the base. */
    const val CHAIN_CONTAINER_HEX =
        "5347424b021f1e1d1c1b1a19181716151413121110202122232425262728292a2b0000000100000000000000" +
            "480000000000000034303132333435363738393a3b24490ed11e48dfc0ade8683fcec1ad31cb547a1fa54179d4" +
            "eeb7263bd7556fc1021f584c86e8ef50d5deca0f0579f6b44b129c4d30a99e2e2135b51ec47389f22cb7844922a" +
            "693ff0534c0ba221d7978ded975524308694cd7c9b9454050e18a836b101de714"

    // ── The version-3 container ─────────────────────────────────────────────────
    //
    // The container above is sealed under the password. The version-3 container is
    // sealed under the **device key**, and the device key travels in the header
    // wrapped under the password — so this one pins two rules at once: that the two
    // platforms wrap the key the same way, and that they read the block back the
    // same way. A drift in either reads as "the password does not open it", which
    // is the one thing a person cannot debug.

    /** The device key the golden container was sealed under. */
    val deviceKey: ByteArray =
        hexToBytes("404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f")

    /** The salt of the golden container's content. */
    val deviceSalt: ByteArray = hexToBytes("606162636465666768696a6b6c6d6e6f")

    /** The nonce of the golden container's content. */
    val deviceNonce: ByteArray = hexToBytes("707172737475767778797a7b")

    /** The salt of the golden container's key block — the block has one of its own. */
    val blockSalt: ByteArray = hexToBytes("808182838485868788898a8b8c8d8e8f")

    /** The nonce of the golden container's key block. */
    val blockNonce: ByteArray = hexToBytes("909192939495969798999a9b")

    /** What the golden container's base holds. */
    val deviceBase: ByteArray = "The base the golden device-key chain carries.".encodeToByteArray()

    /** The whole version-3 container: header, key block and the sealed base. */
    const val DEVICE_KEY_CONTAINER_HEX =
        "5347424b03606162636465666768696a6b6c6d6e6f707172737475767778797a7b000000000000000000000000808182" +
            "838485868788898a8b8c8d8e8f909192939495969798999a9b061c1fdfa3991ecf6b9c9eb866e1c4c251dcc117ce082d" +
            "2d982e989bd37dadc0bf78721cc9a40d0e24599e19299d867f571139afd39990f64ab62b60a3b7ae0f8bb3e3f4a052c3" +
            "f948f9f9c9438b33c7e993eb233eca14cd207586dc29665b085b9740c36c2facef372a11fde3"
}

/** The bytes of a hex string. */
fun hexToBytes(text: String): ByteArray =
    ByteArray(text.length / 2) { index ->
        val high = Character.digit(text[index * 2], 16)
        val low = Character.digit(text[index * 2 + 1], 16)
        require(high >= 0 && low >= 0) { "not a hex string: $text" }
        ((high shl 4) or low).toByte()
    }

/** The hex of these bytes, lowercase. */
fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

/**
 * The version-2 container, built from the golden's parts.
 *
 * ## Why the container is built here and not in the tests
 *
 * The two platform tests have to build **the same** bytes — that is what makes
 * the comparison a comparison. A copy of this construction in each of them would
 * be two copies of a rule, and a copy is a number waiting to drift: the desktop
 * would build a chain the phone builds differently, the golden would fail on
 * both, and the failure would look like a cipher problem rather than like the
 * drift it is.
 *
 * So the construction lives in the fixture, and the golden bytes are the only
 * thing the platforms are compared on.
 */
fun buildGoldenChain(crypto: ArchiveCrypto, key: ByteArray): ByteArray {
    val prefix = ArchiveFormat.MAGIC.encodeToByteArray() +
        byteArrayOf(ArchiveFormat.CHAIN_VERSION.toByte()) +
        ArchiveCryptoGolden.chainSalt +
        ArchiveCryptoGolden.chainNonce
    // The change is sealed with the prefix and its own place; the base is sealed
    // with the whole header, head included.
    val body = crypto.seal(
        key,
        ArchiveCryptoGolden.chainEntryNonce,
        ArchiveCryptoGolden.chainEntry,
        prefix + 0.toBytes(),
    )
    val head = ChainHead(count = 1, entriesLength = (ArchiveFormat.ENTRY_HEAD_BYTES + body.size).toLong())
    val header = prefix + head.toBytes()
    val sealedBase = crypto.seal(key, ArchiveCryptoGolden.chainNonce, ArchiveCryptoGolden.chainBase, header)
    val frame = 0.toBytes() + body.size.toBytes() + ArchiveCryptoGolden.chainEntryNonce + body
    return header + frame + sealedBase
}

/**
 * The version-3 container, built from the golden's parts.
 *
 * ## What is different from the version-2 chain
 *
 * The content is sealed under the **device key** rather than under the password,
 * and the header carries the device key wrapped under the password — in a block
 * with a salt and a nonce of its own, because the block is wrapped once per device
 * and travels in every archive the device writes.
 *
 * So the container has three rules the version-2 one does not: the key block's own
 * layout, the derivation of the content key from the device key, and the
 * authenticated data of the wrapping. Each of them is a place where the two
 * platforms can disagree, and each disagreement reads as a wrong password.
 */
fun buildGoldenDeviceKeyContainer(crypto: ArchiveCrypto): ByteArray {
    val salt = ArchiveCryptoGolden.deviceSalt
    val nonce = ArchiveCryptoGolden.deviceNonce
    val blockSalt = ArchiveCryptoGolden.blockSalt
    val blockNonce = ArchiveCryptoGolden.blockNonce
    val prefix = ArchiveFormat.MAGIC.encodeToByteArray() +
        byteArrayOf(ArchiveFormat.DEVICE_KEY_VERSION.toByte()) +
        salt +
        nonce
    val head = ChainHead(count = 0, entriesLength = 0L)

    // The key block: the device key wrapped under the password, with the block's
    // own salt and the block's own marker as the authenticated data.
    val wrappingKey = crypto.deriveKey(ArchiveCryptoGolden.PASSWORD.toCharArray(), blockSalt)
    val wrapped = crypto.seal(
        wrappingKey,
        blockNonce,
        ArchiveCryptoGolden.deviceKey,
        blockKeyAad(blockSalt),
    )
    val keyBlock = ArchiveKeyBlock(blockSalt, blockNonce, wrapped)
    val header = prefix + head.toBytes() + keyBlock.toBytes()

    // The content is sealed under the device key, spelled in hex.
    val contentKey = crypto.deriveKey(ArchiveCryptoGolden.deviceKey.asPasswordChars(), salt)
    val sealedBase = crypto.seal(contentKey, nonce, ArchiveCryptoGolden.deviceBase, header)
    return header + sealedBase
}
