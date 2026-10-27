package com.singularity.todo.core.backup

import com.singularity.todo.core.files.ByteArraySink
import com.singularity.todo.core.files.ByteSink
import com.singularity.todo.core.files.ByteSource
import com.singularity.todo.core.security.ArchiveCrypto

/**
 * The chain, written to a sink.
 *
 * ## Why the writing is its own part
 *
 * A container is written in one place whatever the version: the differences
 * between the versions are the version byte, the key the content is sealed
 * under, and the key block in the header — and each of those three is a
 * parameter here, so the framing is written once.
 *
 * ## The bound is enforced here
 *
 * The changes are sealed into memory before the head can say how long they
 * are, so this is where the chain's size bound is enforced: an export that
 * writes a chain past the bound has produced an archive this very build calls
 * broken, and it finds out at restore time instead of at export time.
 */
internal class ArchiveChainWriter(private val crypto: ArchiveCrypto) {

    /**
     * The chain, written to [sink].
     */
    fun write(
        base: ByteSource,
        entries: List<ByteSource>,
        sink: ByteSink,
        version: Int,
        contentKey: () -> ContentKey,
        keyBlock: ArchiveKeyBlock?,
    ) {
        requireChainFits(entries.size, "writes")

        val salt = crypto.randomBytes(ArchiveFormat.SALT_BYTES)
        val nonce = crypto.randomBytes(ArchiveFormat.NONCE_BYTES)
        val prefix = headerPrefix(version, salt, nonce)
        val session = sessionFor(contentKey(), salt)
        try {
            val frames = mutableListOf<ArchiveFrame>()
            var sealedBytes = 0L
            entries.forEachIndexed { index, entry ->
                val frame = sealFrame(session, entry, index, prefix)
                sealedBytes += frame.frameSize()
                if (sealedBytes > ArchiveFormat.MAX_CHAIN_BYTES) {
                    throw ArchiveEnvelopeError.ChainTooLong(
                        "$sealedBytes bytes of changes, more than the ${ArchiveFormat.MAX_CHAIN_BYTES} this build writes",
                    )
                }
                frames.add(frame)
            }
            val head = ChainHead(count = frames.size, entriesLength = frames.sumOf { it.frameSize() })
            val header = prefix + head.toBytes() + (keyBlock?.toBytes() ?: ByteArray(0))

            sink.write(header, 0, header.size)
            for (frame in frames) {
                frame.writeTo(sink)
            }
            // The base goes last and runs to the end of the stream: it is the part
            // whose sealed length nobody knows until it is sealed.
            session.sealBase(base, sink, nonce, header)
        } finally {
            // The derived key is the archive's key in the only form it ever takes.
            // It is not the password — the password is wiped by whoever owns it —
            // but this one is ours, and ours is what we clean up after ourselves.
            session.close()
        }
    }

    /**
     * One change, sealed and framed.
     *
     * The changes are sealed into memory before the head can say how long they
     * are, so this is where the chain's size bound is enforced: an export that
     * writes a chain past the bound has produced an archive this very build calls
     * broken, and it finds out at restore time instead of at export time.
     */
    private fun sealFrame(
        session: ArchiveCryptoSession,
        entry: ByteSource,
        index: Int,
        prefix: ByteArray,
    ): ArchiveFrame {
        val entryNonce = crypto.randomBytes(ArchiveFormat.NONCE_BYTES)
        val body = ByteArraySink()
        session.sealEntry(index, entry, body, prefix, entryNonce)
        val sealed = body.bytes()
        if (sealed.size > ArchiveFormat.MAX_ENTRY_BYTES) {
            throw ArchiveEnvelopeError.BrokenChain(
                "a change is ${sealed.size} bytes, more than this build reads",
            )
        }
        return ArchiveFrame(index = index, length = sealed.size, nonce = entryNonce, body = sealed)
    }

    /** The session for a container this build is writing. */
    private fun sessionFor(key: ContentKey, salt: ByteArray): ArchiveCryptoSession =
        when (key) {
            is ContentKey.Password -> ArchiveCryptoSession.forPassword(crypto, key.password, salt)
            is ContentKey.Device -> ArchiveCryptoSession.forDeviceKey(crypto, key.deviceKey, salt)
        }

    /**
     * The magic, the version, the salt and the nonce.
     */
    private fun headerPrefix(version: Int, salt: ByteArray, nonce: ByteArray): ByteArray =
        ArchiveFormat.MAGIC.encodeToByteArray() +
            byteArrayOf(version.toByte()) +
            salt +
            nonce
}

/** What the content of a container is sealed under. */
internal sealed interface ContentKey {
    /** The person's password — versions 1 and 2. */
    class Password(val password: CharArray) : ContentKey

    /** The device key — version 3. */
    class Device(val deviceKey: ByteArray) : ContentKey
}
