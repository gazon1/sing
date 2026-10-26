package com.singularity.todo.core.backup

/**
 * The chain's own count: how many changes it carries, and how many bytes they take.
 *
 * ## Why the head is a field and not a number the reader can infer
 *
 * Every change in a chain is sealed on its own, with its own nonce and its own
 * tag, and each one says which place it occupies. That catches a **reordered** or
 * **inserted** change — a change sealed as number one does not open as number
 * zero — but it does not catch a **dropped** one: a chain with its last two
 * changes cut out is a shorter chain whose every survivor still opens, and a
 * reader that believed it would restore an older state and call it current.
 *
 * So the length of the chain is written down, and written down inside the data
 * the base's tag authenticates. The chain then has to agree with its own head,
 * and a chain that does not is refused by name.
 */
internal class ChainHead(
    /** How many changes the chain carries. */
    val count: Int,
    /** How many bytes those changes take, each frame including its own head. */
    val entriesLength: Long,
) {

    /** The head as the bytes that go into the header. */
    fun toBytes(): ByteArray = count.toBytes() + entriesLength.toBytes()

    companion object {
        /** The head as it is read out of a container. */
        fun fromBytes(bytes: ByteArray): ChainHead =
            ChainHead(count = bytes.intAt(0), entriesLength = bytes.longAt(4))
    }
}
