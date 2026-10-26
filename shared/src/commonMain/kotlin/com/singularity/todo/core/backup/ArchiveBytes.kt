package com.singularity.todo.core.backup

/**
 * The container's own little arithmetic: big-endian integers, in and out of bytes.
 *
 * The container writes its counts, lengths and indices as big-endian numbers, and
 * it reads them back the same way. The helpers live in their own file because
 * three files need them and a private top-level function in one of them would
 * have to be duplicated in the other two.
 *
 * Big-endian because that is the order network protocols use, and because a hex
 * dump of a container reads the same way as the header does — which matters when
 * a person is looking at a damaged archive and counting bytes by hand.
 */

/** The four bytes of [this], most significant first. */
internal fun Int.toBytes(): ByteArray = byteArrayOf(
    (this ushr 24).toByte(),
    (this ushr 16).toByte(),
    (this ushr 8).toByte(),
    toByte(),
)

/** The eight bytes of [this], most significant first. */
internal fun Long.toBytes(): ByteArray = ByteArray(8) { index ->
    (this ushr (56 - 8 * index)).toByte()
}

/** The big-endian int at [offset]. */
internal fun ByteArray.intAt(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

/** The big-endian long at [offset]. */
internal fun ByteArray.longAt(offset: Int): Long {
    var value = 0L
    for (index in 0 until 8) {
        value = (value shl 8) or (this[offset + index].toLong() and 0xFF)
    }
    return value
}

/**
 * These bytes as the characters of a password.
 *
 * Only used for the device key, which is random bytes rather than text: the key
 * derivation takes a `CharArray`, and the honest way to feed it random bytes is
 * as their hexadecimal spelling — every byte survives the trip, and the same
 * bytes always spell the same way.
 */
internal fun ByteArray.asPasswordChars(): CharArray =
    CharArray(size * 2) { index ->
        val byte = this[index / 2].toInt() and 0xFF
        val nibble = if (index % 2 == 0) byte shr 4 else byte and 0x0F
        HEX[nibble]
    }

/** The characters of a hexadecimal spelling. */
private val HEX = "0123456789abcdef".toCharArray()

/**
 * These bytes as a hexadecimal string.
 *
 * The device key lives in the platform keystore as text, and a keystore that only
 * holds text is the one every platform has. Hex is the spelling that loses
 * nothing: the same bytes always spell the same way, and the same spelling always
 * means the same bytes.
 */
internal fun ByteArray.toHexString(): String =
    buildString(size * 2) {
        for (byte in this@toHexString) {
            val value = byte.toInt() and 0xFF
            append(HEX[value shr 4])
            append(HEX[value and 0x0F])
        }
    }

/**
 * The bytes of a hexadecimal string.
 *
 * @throws IllegalArgumentException when the text is not a hexadecimal string of
 *   whole bytes.
 */
internal fun decodeHex(text: String): ByteArray {
    require(text.length % 2 == 0) { "not a hexadecimal string: $text" }
    return ByteArray(text.length / 2) { index ->
        val high = HEX.indexOf(text[index * 2])
        val low = HEX.indexOf(text[index * 2 + 1])
        require(high >= 0 && low >= 0) { "not a hexadecimal string: $text" }
        ((high shl 4) or low).toByte()
    }
}
