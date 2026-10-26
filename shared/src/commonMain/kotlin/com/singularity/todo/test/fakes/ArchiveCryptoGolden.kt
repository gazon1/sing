package com.singularity.todo.test.fakes

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
