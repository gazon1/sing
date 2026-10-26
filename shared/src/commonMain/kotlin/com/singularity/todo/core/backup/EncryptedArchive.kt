package com.singularity.todo.core.backup

import com.singularity.todo.core.files.ByteArraySink
import com.singularity.todo.core.files.ByteArraySource
import com.singularity.todo.core.files.ByteSink
import com.singularity.todo.core.files.ByteSource
import com.singularity.todo.core.security.ArchiveCrypto

/**
 * The encrypted archive: what sits in the bucket, and what comes back out of it.
 *
 * ## What it protects
 *
 * The bucket's policies keep one account's archives out of another account's reach,
 * but a service-role key reads the whole bucket, and a zip read that way is a
 * database dump in plain text. The RLS of the tables does not apply to the object
 * store, so the archive has to be opaque to anyone who holds the bucket's keys —
 * including the operator of the project.
 *
 * So the archive is encrypted **on the device**, before it leaves: the bucket holds
 * ciphertext, and the key is the person's password. The server never sees it.
 *
 * ## The containers
 *
 * Version 1 — the full snapshot this feature shipped with:
 *
 * ```
 * magic (4) | version (1) | salt (16) | nonce (12) | ciphertext + GCM tag
 * ```
 *
 * Version 2 — the changes, then the base they apply to:
 *
 * ```
 * magic (4) | version=2 (1) | salt (16) | nonce (12) | head (12)
 *           | entry*: index (4) | length (4) | nonce (12) | ciphertext + tag
 *           | base ciphertext + tag            (to the end of the stream)
 * ```
 *
 * Version 3 — the same chain, sealed under a device key instead of the password,
 * with that key carried in the header wrapped under the password:
 *
 * ```
 * magic (4) | version=3 (1) | salt (16) | nonce (12) | head (12)
 *           | key block: salt (16) | nonce (12) | wrapped key + tag
 *           | entry*: …                     (as in version 2)
 *           | base ciphertext + tag          (to the end of the stream)
 * ```
 *
 * - **magic** says "this is an encrypted archive" — so an archive from before the
 *   encryption is refused with a sentence that names the problem, instead of
 *   failing as a checksum mismatch;
 * - **version** says how to read the rest. A version is never reused, and every
 *   later build reads every version it can: [ArchiveFormat.READABLE_VERSIONS] is a
 *   set, and [ArchiveFormat.LATEST_VERSION] is only the one this build writes;
 * - **salt** feeds the key derivation, so two archives under one password do not
 *   share a key;
 * - **nonce** is the GCM nonce of the base, and it is fresh for every archive;
 * - **head** is the number of entries and their total length, and it is inside the
 *   authenticated data of the base — which is what makes a **truncated chain**
 *   detectable. Dropping the last entries still leaves every survivor openable, so
 *   without the head a reader would restore an older state and call it current;
 * - **the base is last** because it is the part that is streamed, and a stream
 *   cannot say its own sealed length before it is sealed. Everything else is small
 *   enough to know its length first, so everything else is length-framed;
 * - **the key block** is version 3's own field: the device key, wrapped under the
 *   password, so that a scheduled export can seal an archive without a person and
 *   the person can still open it with the password they know.
 *
 * The header is the authenticated data: it is covered by the tag, so neither the
 * salt nor the nonce nor the version nor the head nor the key block can be changed
 * without the archive ceasing to open. That is why the header is passed to the
 * cipher as AAD rather than being trusted.
 *
 * Each entry is sealed with `header-prefix ‖ index` as its authenticated data. The
 * index is what makes a swap detectable — an entry sealed as number one does not
 * open as number zero — and the prefix binds the entry to this archive's salt and
 * nonce. The head is not in that data because it cannot be: it is computed from the
 * entries' own sealed lengths.
 *
 * ## What it does not protect
 *
 * The **name** of the object is not encrypted. A bucket listing shows the file name
 * and its timestamp, and it always will — the name is what the list is sorted by
 * and what the download addresses. What is protected is the content: the archive's
 * rows, notes and attachments.
 *
 * The password is not stored anywhere. Not in the settings, not in the keychain,
 * not in the archive. The person who pushed it is the one who can open it, and a
 * forgotten password means a lost archive — the honest price of a key that the
 * server never has.
 *
 * ## Where the parts live
 *
 * This class is the **shape** of the archive — what it seals and what it opens —
 * and nothing else:
 *
 * - [ArchiveFormat] — the numbers the bytes are made of, and the bounds;
 * - [ArchiveFrame] and [ArchiveHead] — the framing;
 * - [ArchiveContainer] — the version routing;
 * - [ArchiveChainReader] — the reading of a chain's changes;
 * - [ArchiveCryptoSession] — the key and the cipher calls;
 * - [ArchiveErrors] — what a failure means.
 */
class EncryptedArchive(private val crypto: ArchiveCrypto) {

    /**
     * The encrypted form of [plain].
     *
     * @throws ArchiveEnvelopeError.MissingPassword when the password is empty.
     */
    fun seal(plain: ByteArray, password: CharArray): ByteArray {
        val sink = ByteArraySink()
        seal(ByteArraySource(plain), sink, password)
        return sink.bytes()
    }

    /**
     * The encrypted form of what [source] yields, written to [sink].
     *
     * The archive is never held in memory whole: it is read in pieces and
     * encrypted in pieces, so the app's memory carries a chunk of the account
     * rather than all of it.
     *
     * This writes a version-2 container whose chain has **no entries** — a full
     * snapshot in the newer envelope. [sealChain] is what writes a chain.
     *
     * @throws ArchiveEnvelopeError.MissingPassword when the password is empty.
     */
    fun seal(source: ByteSource, sink: ByteSink, password: CharArray) {
        sealChain(base = source, entries = emptyList(), sink = sink, password = password)
    }

    /**
     * The encrypted form of [entries] followed by [base], written to [sink].
     *
     * ## Why a chain rather than one sealed stream
     *
     * AES-GCM authenticates the ciphertext as one stream: a delta cannot be
     * appended to a sealed archive without re-sealing everything, which is the full
     * snapshot an incremental backup exists to avoid. So each part is sealed on its
     * own — the base with the archive's nonce, every entry with a nonce of its
     * own — and the container carries enough to tell whether the chain is the one
     * that was written.
     *
     * ## Memory
     *
     * The base is streamed and never buffered. The entries are: they are deltas,
     * measured in kilobytes, and each one has to be sealed before the head can say
     * how long it is. How much they may take is not left to chance — the chain is
     * bounded by [ArchiveFormat.MAX_CHAIN_ENTRIES] and [ArchiveFormat.MAX_CHAIN_BYTES],
     * and a chain past the bound is refused **here**, at export time, rather than
     * being written and refused at restore time.
     *
     * @throws ArchiveEnvelopeError.MissingPassword when the password is empty.
     * @throws ArchiveEnvelopeError.ChainTooLong when the chain is past its bound.
     */
    fun sealChain(base: ByteSource, entries: List<ByteSource>, sink: ByteSink, password: CharArray) {
        requirePassword(password)
        sealChainWith(
            base = base,
            entries = entries,
            sink = sink,
            version = ArchiveFormat.CHAIN_VERSION,
            contentKey = { ContentKey.Password(password) },
            keyBlock = null,
        )
    }

    /**
     * The encrypted form of [entries] followed by [base], sealed under a **device
     * key** and written to [sink].
     *
     * ## What this is for
     *
     * A scheduled export has no person, and therefore no password — but the archive
     * still has to be openable by the person who owns the account. So the archive
     * carries the key it was sealed with, in a form only the password can open: the
     * [keyBlock], which is the device key wrapped under the password.
     *
     * The block is made once per device ([wrapDeviceKey]) and travels in every
     * archive, so the wrapping is not repeated per export.
     *
     * @throws ArchiveEnvelopeError.ChainTooLong when the chain is past its bound.
     */
    fun sealChain(
        base: ByteSource,
        entries: List<ByteSource>,
        sink: ByteSink,
        deviceKey: ByteArray,
        keyBlock: ArchiveKeyBlock,
    ) {
        sealChainWith(
            base = base,
            entries = entries,
            sink = sink,
            version = ArchiveFormat.DEVICE_KEY_VERSION,
            contentKey = { ContentKey.Device(deviceKey) },
            keyBlock = keyBlock,
        )
    }

    /**
     * The device key, wrapped under [password] so that it can travel in a container.
     *
     * The wrapping is done **once per device**: the block has a salt of its own, so
     * it does not have to be re-wrapped when the archive's salt changes.
     */
    fun wrapDeviceKey(deviceKey: ByteArray, password: CharArray): ArchiveKeyBlock {
        requirePassword(password)
        val blockSalt = crypto.randomBytes(ArchiveFormat.SALT_BYTES)
        val blockNonce = crypto.randomBytes(ArchiveFormat.NONCE_BYTES)
        val session = ArchiveCryptoSession.forPassword(crypto, password, blockSalt)
        return try {
            ArchiveKeyBlock(
                salt = blockSalt,
                nonce = blockNonce,
                ciphertext = session.wrapDeviceKey(deviceKey, blockNonce, blockSalt),
            )
        } finally {
            session.close()
        }
    }

    /**
     * The archive inside [container].
     *
     * A version-1 archive opens as it always did. A version-2 archive opens its
     * **base**, and refuses one that carries entries: this call cannot apply them,
     * and returning the base alone would silently restore an older state.
     *
     * @throws ArchiveEnvelopeError.NotEncrypted when the bytes are not an encrypted archive.
     * @throws ArchiveEnvelopeError.UnsupportedArchiveVersion when no version this build reads
     *   is the container's version.
     * @throws ArchiveEnvelopeError.WrongPassword when the password does not open it.
     * @throws ArchiveEnvelopeError.BrokenChain when the archive carries entries, which this
     *   call cannot apply — see [openChain].
     */
    fun open(container: ByteArray, password: CharArray): ByteArray {
        val sink = ByteArraySink()
        openChain(ByteArraySource(container), sink, password) { _, _ ->
            throw ArchiveEnvelopeError.BrokenChain(
                "it carries changes, and reading an archive whole cannot apply them",
            )
        }
        return sink.bytes()
    }

    /**
     * The archive inside what [source] yields, written to [sink].
     *
     * The streaming shape of [open]: the base is copied in pieces rather than held
     * in memory. Like [open], this cannot apply a chain's entries, and refuses an
     * archive that carries them instead of returning the base alone — see
     * [openChain].
     *
     * **The sink is written before the password is proven.** The cipher can only
     * check the tag when it has read everything, so what arrives in the sink may be
     * garbage when the password was wrong. A caller that cares writes into a
     * temporary file and moves it into place only after this call returned without
     * throwing.
     *
     * @throws ArchiveEnvelopeError.NotEncrypted when the bytes are not an encrypted archive.
     * @throws ArchiveEnvelopeError.UnsupportedArchiveVersion when no version this build reads
     *   is the container's version.
     * @throws ArchiveEnvelopeError.WrongPassword when the password does not open it.
     * @throws ArchiveEnvelopeError.BrokenChain when the archive carries entries, which this
     *   call cannot apply.
     */
    fun open(source: ByteSource, sink: ByteSink, password: CharArray) {
        openChain(source, sink, password) { _, _ ->
            throw ArchiveEnvelopeError.BrokenChain(
                "it carries changes, and reading an archive whole cannot apply them",
            )
        }
    }

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
     * **The sink is written before the password is proven.** The cipher can only
     * check the tag when it has read everything, so what arrives in the sink may be
     * garbage when the password was wrong. A caller that cares writes into a
     * temporary file and moves it into place only after this call returned
     * without throwing.
     *
     * @throws ArchiveEnvelopeError.NotEncrypted when the bytes are not an encrypted archive.
     * @throws ArchiveEnvelopeError.UnsupportedArchiveVersion when no version this build reads
     *   is the container's version.
     * @throws ArchiveEnvelopeError.WrongPassword when the password does not open it.
     * @throws ArchiveEnvelopeError.BrokenChain when the chain does not hold together: an
     *   entry out of place, or a chain shorter than its own head says — a dropped
     *   tail.
     */
    fun openChain(
        source: ByteSource,
        sink: ByteSink,
        password: CharArray,
        onEntry: (index: Int, plain: ByteArray) -> Unit,
    ) {
        requirePassword(password)
        val container = archiveErrors { ArchiveContainerReader.read(source) }
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
     * The chain, written to [sink].
     *
     * The one place that writes a container, whatever the version: the differences
     * between the versions are the version byte, the key the content is sealed
     * under, and the key block in the header — and each of those three is a
     * parameter here, so the framing is written once.
     */
    private fun sealChainWith(
        base: ByteSource,
        entries: List<ByteSource>,
        sink: ByteSink,
        version: Int,
        contentKey: () -> ContentKey,
        keyBlock: ArchiveKeyBlock?,
    ) {
        requireChainFits(entries.size)

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

    /**
     * The chain has to fit its bound.
     *
     * The count is what a restore reads: a chain of a thousand changes is a
     * restore that is slower than the snapshot it replaced. The size is what the
     * writer holds: the changes are sealed into memory before the head can say
     * how long they are, so an unbounded chain is an unbounded allocation.
     *
     * Both are refused **by name**, at export time — the export re-seals a fresh
     * base instead.
     */
    private fun requireChainFits(count: Int) {
        if (count > ArchiveFormat.MAX_CHAIN_ENTRIES) {
            throw ArchiveEnvelopeError.ChainTooLong(
                "$count changes, more than the ${ArchiveFormat.MAX_CHAIN_ENTRIES} this build writes",
            )
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

    /**
     * A password is not optional.
     *
     * An empty password would derive a key from nothing, and an archive sealed
     * with it would be readable by anyone who has the bucket. Both directions
     * refuse it by name, rather than the opening one failing with a wrong
     * password it never had.
     *
     * @throws ArchiveEnvelopeError.MissingPassword when the password is empty.
     */
    private fun requirePassword(password: CharArray) {
        if (password.isEmpty()) throw ArchiveEnvelopeError.MissingPassword()
    }

    /** What the content of a container is sealed under. */
    private sealed interface ContentKey {
        /** The person's password — versions 1 and 2. */
        class Password(val password: CharArray) : ContentKey

        /** The device key — version 3. */
        class Device(val deviceKey: ByteArray) : ContentKey
    }
}
