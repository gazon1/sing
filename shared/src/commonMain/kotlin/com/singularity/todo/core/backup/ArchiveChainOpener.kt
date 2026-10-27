package com.singularity.todo.core.backup

import com.singularity.todo.core.files.ByteSink
import com.singularity.todo.core.files.ByteSource
import com.singularity.todo.core.security.ArchiveCrypto

/**
 * The chain, read from a source.
 *
 * ## Why the reading is its own part
 *
 * Reading a container is the one path that has to refuse what it cannot prove:
 * the count in the head is read before the password is proven, so it is
 * refused before it can become a loop that allocates.
 *
 * ## The sink is written before the password is proven
 *
 * The cipher can only check the tag when it has read everything, so what
 * arrives in the sink may be garbage when the password was wrong. A caller
 * that cares writes into a temporary file and moves it into place only after
 * this part returned without throwing.
 */
internal class ArchiveChainOpener(private val crypto: ArchiveCrypto) {

    /**
     * The archive inside what [source] yields: every entry in order to [onEntry],
     * then the base into [sink].
     *
     * The base is written to [sink] as it is read; each entry's plaintext is handed
     * over as a `ByteArray`, because a delta is small and the caller has to apply it
     * whole.
     *
     * A version-3 container is opened with the **password** too: the password
     * unwraps the device key that the content was sealed with, and the content is
     * then read under the device key.
     *
     * @throws ArchiveEnvelopeError.NotEncrypted when the bytes are not an encrypted archive.
     * @throws ArchiveEnvelopeError.UnsupportedArchiveVersion when no version this build reads
     *   is the container's version.
     * @throws ArchiveEnvelopeError.WrongPassword when the password does not open it.
     * @throws ArchiveEnvelopeError.BrokenChain when the chain does not hold together: an
     *   entry out of place, or a chain shorter than its own head says — a dropped
     *   tail.
     * @throws ArchiveEnvelopeError.ChainTooLong when the head promises more changes
     *   than this build reads. The count is read before the password is proven, so
     *   it is refused before it can become a loop.
     */
    fun open(
        source: ByteSource,
        sink: ByteSink,
        password: CharArray,
        onEntry: (index: Int, plain: ByteArray) -> Unit,
    ) {
        val container = archiveErrors { ArchiveContainerReader.read(source) }
        requireChainFits(container.head.count, "reads")
        val session = contentSession(container, password)
        try {
            val consumed = ArchiveChainReader.read(source, container, session, onEntry)

            if (consumed != container.head.entriesLength) {
                throw ArchiveEnvelopeError.BrokenChain(
                    "its head says ${container.head.entriesLength} bytes of changes and $consumed were there",
                )
            }

            archiveErrors { session.openBase(source, sink, container.nonce, container.header) }
        } finally {
            session.close()
        }
    }

    /**
     * The session that reads the container's content.
     *
     * A version-1 or version-2 container is sealed under the password. A version-3
     * one is sealed under the device key, which the password unwraps out of the
     * key block the header carries.
     */
    private fun contentSession(container: ArchiveContainer, password: CharArray): ArchiveCryptoSession =
        when (container.version) {
            ArchiveFormat.DEVICE_KEY_VERSION -> {
                val block = container.keyBlock
                    ?: throw ArchiveEnvelopeError.BrokenChain("it says it carries a key block, and it does not")
                val wrapping = ArchiveCryptoSession.forPassword(crypto, password, block.salt)
                val deviceKey = try {
                    archiveErrors { wrapping.unwrapDeviceKey(block) }
                } finally {
                    wrapping.close()
                }
                ArchiveCryptoSession.forDeviceKey(crypto, deviceKey, container.salt)
            }

            else -> ArchiveCryptoSession.forPassword(crypto, password, container.salt)
        }
}
