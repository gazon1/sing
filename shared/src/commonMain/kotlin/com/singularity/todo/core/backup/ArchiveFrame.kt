package com.singularity.todo.core.backup

import com.singularity.todo.core.files.ByteSink
import com.singularity.todo.core.files.ByteSource

/**
 * One change in a chain, framed.
 *
 * ## The frame
 *
 * ```
 * index (4) | length (4) | nonce (12) | ciphertext + tag
 * ```
 *
 * - **index** is the change's place in the chain. It is inside the change's
 *   authenticated data, so a change cannot be moved to another place without the
 *   tag failing;
 * - **length** is the length of the sealed change, which the reader cannot know
 *   before it reads it — the base is the streaming part, a change is not;
 * - **nonce** is the change's own GCM nonce: every change is sealed on its own,
 *   because a GCM stream cannot be appended to.
 *
 * The length is the reason a frame is read in two steps: the head says how long
 * the body is, and the body is read afterwards. A length that comes out of a
 * damaged frame is not a length, so it is checked against the bounds before
 * anything is allocated for it.
 */
internal class ArchiveFrame(
    /** The change's place in the chain. */
    val index: Int,
    /** The length of the sealed change. */
    val length: Int,
    /** The change's own nonce. */
    val nonce: ByteArray,
    /** The sealed change: the ciphertext and the tag. */
    val body: ByteArray,
) {

    /** How many bytes this frame takes in the container, head included. */
    fun frameSize(): Long = ArchiveFormat.ENTRY_HEAD_BYTES.toLong() + body.size

    /** The frame as it goes into the container. */
    fun toBytes(): ByteArray = index.toBytes() + body.size.toBytes() + nonce + body

    /** Writes the frame into [sink]. */
    fun writeTo(sink: ByteSink) {
        val bytes = toBytes()
        sink.write(bytes, 0, bytes.size)
    }

    companion object {
        /**
         * The frame's head, or `null` when the source ends there.
         *
         * A source that ends where the head promised a frame is a **dropped
         * tail**, and the caller refuses the archive by name rather than reading
         * what is left as if it were the whole archive.
         */
        fun readHead(source: ByteSource): ArchiveFrameHead? {
            val bytes = source.readOrNull(ArchiveFormat.ENTRY_HEAD_BYTES) ?: return null
            return ArchiveFrameHead(
                index = bytes.intAt(0),
                length = bytes.intAt(4),
                nonce = bytes.copyOfRange(8, 8 + ArchiveFormat.NONCE_BYTES),
            )
        }
    }
}

/**
 * The head of a frame: where the change sits, how long it is, and under which nonce.
 *
 * It is separate from [ArchiveFrame] because the reader meets the head and the
 * body at different moments — and because a head whose length is nonsense has to
 * be refused *before* the body is read, not after.
 */
internal class ArchiveFrameHead(val index: Int, val length: Int, val nonce: ByteArray)

/**
 * Exactly [count] bytes, or `null` when the source ends first.
 *
 * The distinction the container needs is **end-of-stream versus a failure to
 * read at all** — and `readExactly` cannot make it: it answers both with an
 * `IllegalStateException`, so a disk that fell over mid-read would be reported
 * as "this is not an archive". Reading the pieces here keeps the two apart: a
 * short read returns `null`, and a source that throws is left to throw.
 */
internal fun ByteSource.readOrNull(count: Int): ByteArray? {
    val buffer = ByteArray(count)
    var filled = 0
    while (filled < count) {
        val read = read(buffer, filled, count - filled)
        if (read < 0) return null
        filled += read
    }
    return buffer
}
