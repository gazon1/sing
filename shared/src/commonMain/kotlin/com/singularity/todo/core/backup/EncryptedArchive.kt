package com.singularity.todo.core.backup

import com.singularity.todo.core.files.ByteArraySink
import com.singularity.todo.core.files.ByteArraySource
import com.singularity.todo.core.files.ByteSink
import com.singularity.todo.core.files.ByteSource
import com.singularity.todo.core.security.ArchiveCrypto
import com.singularity.todo.core.security.ArchiveCryptoParams
import com.singularity.todo.core.security.ArchiveTagMismatchException
import kotlinx.coroutines.CancellationException

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
 * - **magic** says "this is an encrypted archive" — so an archive from before the
 *   encryption is refused with a sentence that names the problem, instead of
 *   failing as a checksum mismatch;
 * - **version** says how to read the rest. A version is never reused, and every
 *   later build reads every version it can: [READABLE_VERSIONS] is a set, and
 *   [VERSION] is only the one this build writes;
 * - **salt** feeds the key derivation, so two archives under one password do not
 *   share a key;
 * - **nonce** is the GCM nonce of the base, and it is fresh for every archive;
 * - **head** is the number of entries and their total length, and it is inside the
 *   authenticated data of the base — which is what makes a **truncated chain**
 *   detectable. Dropping the last entries still leaves every survivor openable, so
 *   without the head a reader would restore an older state and call it current;
 * - **the base is last** because it is the part that is streamed, and a stream
 *   cannot say its own sealed length before it is sealed. Everything else is small
 *   enough to know its length first, so everything else is length-framed.
 *
 * The header is the authenticated data: it is covered by the tag, so neither the
 * salt nor the nonce nor the version nor the head can be changed without the
 * archive ceasing to open. That is why the header is passed to the cipher as AAD
 * rather than being trusted.
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
 */
class EncryptedArchive(private val crypto: ArchiveCrypto) {

    /**
     * The encrypted form of [plain].
     *
     * @throws BackupError.MissingPassword when the password is empty.
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
     * @throws BackupError.MissingPassword when the password is empty.
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
     * how long it is.
     *
     * @throws BackupError.MissingPassword when the password is empty.
     */
    fun sealChain(base: ByteSource, entries: List<ByteSource>, sink: ByteSink, password: CharArray) {
        requirePassword(password)
        val salt = crypto.randomBytes(SALT_BYTES)
        val nonce = crypto.randomBytes(NONCE_BYTES)
        val prefix = headerPrefix(salt, nonce)
        val key = crypto.deriveKey(password, salt)
        try {
            val sealedEntries = entries.mapIndexed { index, entry ->
                val entryNonce = crypto.randomBytes(NONCE_BYTES)
                val body = ByteArraySink()
                crypto.seal(entry, body, key, entryNonce, entryAad(prefix, index))
                val sealed = body.bytes()
                if (sealed.size > MAX_ENTRY_BYTES) {
                    // The reader refuses a change bigger than this. Writing one anyway
                    // would produce an archive this very build calls broken — a
                    // failure at restore time instead of at export time.
                    throw BackupError.BrokenChain("a change is ${sealed.size} bytes, more than this build reads")
                }
                SealedEntry(index = index, nonce = entryNonce, body = sealed)
            }
            val head = ChainHead(count = sealedEntries.size, entriesLength = sealedEntries.sumOf { it.frameSize() })
            val header = prefix + head.toBytes()

            sink.write(header, 0, header.size)
            for (entry in sealedEntries) {
                val frame = entry.frame()
                sink.write(frame, 0, frame.size)
            }
            // The base goes last and runs to the end of the stream: it is the part
            // whose sealed length nobody knows until it is sealed.
            crypto.seal(base, sink, key, nonce, header)
        } finally {
            // The derived key is the archive's key in the only form it ever takes.
            // It is not the password — the password is wiped by whoever owns it —
            // but this one is ours, and ours is what we clean up after ourselves.
            key.fill(0)
        }
    }

    /**
     * The archive inside [container].
     *
     * A version-1 archive opens as it always did. A version-2 archive opens its
     * **base**, and refuses one that carries entries: this call cannot apply them,
     * and returning the base alone would silently restore an older state.
     *
     * @throws BackupError.NotEncrypted when the bytes are not an encrypted archive.
     * @throws BackupError.UnsupportedArchiveVersion when no version this build reads
     *   is the container's version.
     * @throws BackupError.WrongPassword when the password does not open it.
     * @throws BackupError.BrokenChain when the archive carries entries, which this
     *   call cannot apply — see [openChain].
     */
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    fun open(container: ByteArray, password: CharArray): ByteArray {
        val sink = ByteArraySink()
        openChain(ByteArraySource(container), sink, password) { _, _ ->
            throw BackupError.BrokenChain(
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
     * @throws BackupError.NotEncrypted when the bytes are not an encrypted archive.
     * @throws BackupError.UnsupportedArchiveVersion when no version this build reads
     *   is the container's version.
     * @throws BackupError.WrongPassword when the password does not open it.
     * @throws BackupError.BrokenChain when the archive carries entries, which this
     *   call cannot apply.
     */
    fun open(source: ByteSource, sink: ByteSink, password: CharArray) {
        openChain(source, sink, password) { _, _ ->
            throw BackupError.BrokenChain(
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
     * **The sink is written before the password is proven.** The cipher can only
     * check the tag when it has read everything, so what arrives in the sink may be
     * garbage when the password was wrong. A caller that cares writes into a
     * temporary file and moves it into place only after this call returned
     * without throwing.
     *
     * @throws BackupError.NotEncrypted when the bytes are not an encrypted archive.
     * @throws BackupError.UnsupportedArchiveVersion when no version this build reads
     *   is the container's version.
     * @throws BackupError.WrongPassword when the password does not open it.
     * @throws BackupError.BrokenChain when the chain does not hold together: an
     *   entry out of place, or a chain shorter than its own head says — a dropped
     *   tail.
     */
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    fun openChain(
        source: ByteSource,
        sink: ByteSink,
        password: CharArray,
        onEntry: (index: Int, plain: ByteArray) -> Unit,
    ) {
        requirePassword(password)
        // Every read of the source goes through `said`: a source that fails to read
        // at all is `Unusable` wherever it happens, a tag that does not match is the
        // password, and the chain's own refusals pass through untouched.
        val container = said { readContainer(source) }
        val key = crypto.deriveKey(password, container.salt)
        try {
            var consumed = 0L
            for (index in 0 until container.head.count) {
                val frame = said { readFrame(source) }
                val entryIndex = frame.intAt(0)
                if (entryIndex != index) {
                    throw BackupError.BrokenChain("an entry that should be number $index says it is $entryIndex")
                }
                val length = frame.intAt(4)
                if (length <= 0 || length > MAX_ENTRY_BYTES) {
                    // A length read out of a corrupt frame is not a length: allocating
                    // it would be the corruption, not the reading of it.
                    throw BackupError.BrokenChain("a change claims to be $length bytes long")
                }
                val entryNonce = frame.copyOfRange(8, 8 + NONCE_BYTES)
                val body = said { readFrameBody(source, length) }
                consumed += ENTRY_HEAD_BYTES + length

                val plain = ByteArraySink()
                said {
                    crypto.open(ByteArraySource(body), plain, key, entryNonce, entryAad(container.prefix, index))
                }
                onEntry(index, plain.bytes())
            }

            if (consumed != container.head.entriesLength) {
                throw BackupError.BrokenChain(
                    "its head says ${container.head.entriesLength} bytes of changes and $consumed were there",
                )
            }

            said { crypto.open(source, sink, key, container.nonce, container.header) }
        } finally {
            key.fill(0)
        }
    }

    /**
     * Runs [block], saying in the project's words what the cipher's failure means.
     *
     * The same rules as before the chain: a tag mismatch is the password, anything
     * else is the bytes not moving. A project failure passes through untouched, and
     * a cancellation is not a failure at all.
     */
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private inline fun <T> said(block: () -> T): T =
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: BackupError) {
            throw error
        } catch (error: ArchiveTagMismatchException) {
            // The cipher says the tag does not match: the password is wrong.
            throw BackupError.WrongPassword(error)
        } catch (error: Exception) {
            // Anything else is not about the password: the bytes could not be moved
            // at all. Saying "wrong password" here sends the person to retype a
            // password that was never the problem.
            throw BackupError.Unusable(error)
        }

    /**
     * An entry's frame header, or "the chain is shorter than its head says".
     *
     * A source that ends here was not a short entry — the head promised a frame, and
     * the bytes are not there. That is a dropped tail, and it is refused by name
     * rather than read as the end of the archive.
     */
    private fun readFrame(source: ByteSource): ByteArray =
        readOrNull(source, ENTRY_HEAD_BYTES)
            ?: throw BackupError.BrokenChain("it ends before the changes its head promised")

    /** An entry's body, with the same mapping: a missing body is a dropped tail. */
    private fun readFrameBody(source: ByteSource, length: Int): ByteArray =
        readOrNull(source, length)
            ?: throw BackupError.BrokenChain("a change ends before its own length says")

    /**
     * The container's header, whichever readable version it is.
     *
     * **The magic and the version are read first, and everything after them depends
     * on the version** — a version-2 header is longer than a version-1 one, so a
     * reader that read a fixed length would cut a v2 header in half and reject the
     * archive it was written by.
     *
     * @throws BackupError.NotEncrypted when the bytes do not say what they are.
     * @throws BackupError.UnsupportedArchiveVersion when the version is not one this
     *   build reads; the message names the version and the one this build writes.
     */
    private fun readContainer(source: ByteSource): Container {
        val prefix = readPrefix(source)
        val salt = prefix.copyOfRange(MAGIC.length + 1, MAGIC.length + 1 + SALT_BYTES)
        val nonce = prefix.copyOfRange(MAGIC.length + 1 + SALT_BYTES, prefix.size)
        val version = prefix[MAGIC.length].toInt() and 0xFF
        if (version == 1) {
            return Container(prefix, salt, nonce, ChainHead(count = 0, entriesLength = 0))
        }
        val headBytes = readOrNull(source, HEAD_BYTES)
            ?: throw BackupError.NotEncrypted(IllegalStateException("a version-2 header ends early"))
        return Container(prefix, salt, nonce, ChainHead.fromBytes(headBytes), header = prefix + headBytes)
    }

    /**
     * The magic, the version, the salt and the nonce.
     *
     * @throws BackupError.NotEncrypted when the bytes are too short or are not an
     *   encrypted archive.
     * @throws BackupError.UnsupportedArchiveVersion when the version is not read.
     */
    @Suppress("ThrowsCount")
    private fun readPrefix(source: ByteSource): ByteArray {
        val header = readOrNull(source, PREFIX_BYTES) ?: throw BackupError.NotEncrypted()
        if (header.copyOfRange(0, MAGIC.length).decodeToString() != MAGIC) throw BackupError.NotEncrypted()
        val version = header[MAGIC.length].toInt() and 0xFF
        if (version !in READABLE_VERSIONS) throw BackupError.UnsupportedArchiveVersion(version, READABLE_VERSIONS)
        return header
    }

    /**
     * Exactly [count] bytes, or `null` when the source ends first.
     *
     * The distinction the container needs is **end-of-stream versus a failure to
     * read at all** — and `readExactly` cannot make it: it answers both with an
     * `IllegalStateException`, so a disk that fell over mid-read would be reported
     * as "this is not an archive". Reading the pieces here keeps the two apart: a
     * short read returns `null`, and a source that throws is left to throw.
     */
    private fun readOrNull(source: ByteSource, count: Int): ByteArray? {
        val buffer = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val read = source.read(buffer, filled, count - filled)
            if (read < 0) return null
            filled += read
        }
        return buffer
    }

    /**
     * A password is not optional.
     *
     * An empty password would derive a key from nothing, and an archive sealed
     * with it would be readable by anyone who has the bucket. Both directions
     * refuse it by name, rather than the opening one failing with a wrong
     * password it never had.
     *
     * @throws BackupError.MissingPassword when the password is empty.
     */
    private fun requirePassword(password: CharArray) {
        if (password.isEmpty()) throw BackupError.MissingPassword()
    }

    private fun headerPrefix(salt: ByteArray, nonce: ByteArray): ByteArray =
        MAGIC.encodeToByteArray() +
            byteArrayOf(VERSION.toByte()) +
            salt +
            nonce

    /** What an entry is sealed with: this archive's prefix, then the entry's place. */
    private fun entryAad(prefix: ByteArray, index: Int): ByteArray = prefix + index.toBytes()

    /** The container, as the reader sees it. */
    private class Container(
        val prefix: ByteArray,
        val salt: ByteArray,
        val nonce: ByteArray,
        val head: ChainHead,
        val header: ByteArray = prefix,
    )

    /**
     * The chain's own count.
     *
     * `count` entries, together `entriesLength` bytes — each frame including its
     * index, length and nonce. Both numbers are authenticated as part of the base's
     * data, which is what makes a dropped tail visible.
     */
    private class ChainHead(val count: Int, val entriesLength: Long) {
        fun toBytes(): ByteArray = count.toBytes() + entriesLength.toBytes()

        companion object {
            fun fromBytes(bytes: ByteArray): ChainHead =
                ChainHead(count = bytes.intAt(0), entriesLength = bytes.longAt(4))
        }
    }

    /** One entry, sealed and framed. */
    private class SealedEntry(val index: Int, val nonce: ByteArray, val body: ByteArray) {
        fun frameSize(): Long = ENTRY_HEAD_BYTES.toLong() + body.size

        fun frame(): ByteArray = index.toBytes() + body.size.toBytes() + nonce + body
    }

    companion object {
        /** The bytes that say "an encrypted archive follows". */
        const val MAGIC = "SGBK"

        /** The version this build **writes**. */
        const val VERSION = 2

        /**
         * The versions this build **reads**.
         *
         * A version is never reused, and a build keeps reading the ones it shipped
         * with: an archive written before the chain existed still has to open. A
         * bump to [VERSION] is not a bump to this set.
         */
        val READABLE_VERSIONS = setOf(1, 2)

        /** The salt's length: 16 bytes, as PBKDF2 expects. */
        const val SALT_BYTES = ArchiveCryptoParams.SALT_BYTES

        /** The GCM nonce's length: 12 bytes, as GCM expects. */
        const val NONCE_BYTES = ArchiveCryptoParams.NONCE_BYTES

        /** Magic, version, salt and nonce — the data every version starts with. */
        const val PREFIX_BYTES = MAGIC.length + 1 + SALT_BYTES + NONCE_BYTES

        /** The head's length: the entry count (4) and the entries' total length (8). */
        const val HEAD_BYTES = 4 + 8

        /** A version-1 header: magic, version, salt and nonce, and nothing else. */
        const val HEADER_BYTES = PREFIX_BYTES

        /** A version-2 header: the prefix and the chain's head — the base's AAD. */
        const val HEADER_BYTES_V2 = PREFIX_BYTES + HEAD_BYTES

        /** An entry's frame before its body: index (4), length (4) and nonce (12). */
        const val ENTRY_HEAD_BYTES = 4 + 4 + NONCE_BYTES

        /**
         * The largest change this build will read, in bytes.
         *
         * A change is a delta — kilobytes. The bound exists because the length comes
         * out of the container, and a corrupt length must not become an allocation:
         * refusing it is what keeps a damaged archive from taking the process down.
         */
        const val MAX_ENTRY_BYTES = 64 * 1024 * 1024
    }
}

/** The four bytes of [this], most significant first. */
private fun Int.toBytes(): ByteArray = byteArrayOf(
    (this ushr 24).toByte(),
    (this ushr 16).toByte(),
    (this ushr 8).toByte(),
    toByte(),
)

/** The eight bytes of [this], most significant first. */
private fun Long.toBytes(): ByteArray = ByteArray(8) { index ->
    (this ushr (56 - 8 * index)).toByte()
}

/** The big-endian int at [offset]. */
private fun ByteArray.intAt(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

/** The big-endian long at [offset]. */
private fun ByteArray.longAt(offset: Int): Long {
    var value = 0L
    for (index in 0 until 8) {
        value = (value shl 8) or (this[offset + index].toLong() and 0xFF)
    }
    return value
}
