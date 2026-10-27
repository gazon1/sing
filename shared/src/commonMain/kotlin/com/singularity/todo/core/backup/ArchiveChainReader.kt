package com.singularity.todo.core.backup

import com.singularity.todo.core.files.ByteSource

/**
 * The changes of a chain, read in order.
 *
 * ## Why this is its own file
 *
 * [EncryptedArchive] is the *shape* of the archive — what it seals and what it
 * opens. The reading of a chain is one of those things' innards: the frames, the
 * place each change sits in, the length it claims. It is a part, not a shape, and
 * the archive already has its parts in their own files ([ArchiveFormat],
 * [ArchiveFrame], [ArchiveContainer], [ArchiveCryptoSession]).
 *
 * ## What it refuses
 *
 * A chain that breaks in the middle is a chain that must not be applied halfway.
 * Applying the changes that came before the break and calling the result restored
 * would hand a person yesterday's rows and say nothing — that failure is the one
 * this whole family exists to prevent.
 */
internal object ArchiveChainReader {

    /**
     * Reads the changes of [container]'s chain and hands each one to [onEntry].
     *
     * @return how many bytes the changes took, so the caller can compare them with
     *   the length the chain's own head promised.
     *
     * @throws ArchiveEnvelopeError.BrokenChain when a change is missing, out of
     *   place, or claims a length that is not one.
     */
    fun read(
        source: ByteSource,
        container: ArchiveContainer,
        session: ArchiveCryptoSession,
        onEntry: (index: Int, plain: ByteArray) -> Unit,
    ): Long {
        var consumed = 0L
        for (index in 0 until container.head.count) {
            val frameHead = archiveErrors { ArchiveFrame.readHead(source) }
                ?: throw ArchiveEnvelopeError.BrokenChain("it ends before the changes its head promised")
            checkFrameIndex(index, frameHead.index)
            val body = readFrameBody(source, frameHead)
            consumed += ArchiveFormat.ENTRY_HEAD_BYTES + frameHead.length

            val plain = archiveErrors {
                session.openEntry(index, body, container.prefix, frameHead.nonce)
            }
            onEntry(index, plain)
        }
        return consumed
    }

    /** The change is where the chain says it is, or the chain does not hold together. */
    private fun checkFrameIndex(expected: Int, actual: Int) {
        if (actual != expected) {
            throw ArchiveEnvelopeError.BrokenChain(
                "an entry that should be number $expected says it is $actual",
            )
        }
    }

    /**
     * The body of a change, if the frame's own length can be trusted.
     *
     * A length read out of a corrupt frame is not a length: allocating it would be
     * the corruption, not the reading of it.
     */
    private fun readFrameBody(source: ByteSource, head: ArchiveFrameHead): ByteArray {
        if (head.length <= 0 || head.length > ArchiveFormat.MAX_ENTRY_BYTES) {
            throw ArchiveEnvelopeError.BrokenChain("a change claims to be ${head.length} bytes long")
        }
        return source.readOrNull(head.length)
            ?: throw ArchiveEnvelopeError.BrokenChain("a change ends before its own length says")
    }
}
