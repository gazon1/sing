package com.singularity.todo.core.backup

import com.singularity.todo.core.security.ArchiveCryptoParams

/**
 * The container's format: the numbers its bytes are made of.
 *
 * ## Why the numbers live here
 *
 * The format used to be a set of constants on the class that read and wrote the
 * container, which made three things true at once: the *shape* of a container, the
 * *rules* about which shapes exist, and the *cipher calls* that produce them were
 * one file. A change to any of the three had to be reasoned about against the
 * other two, and a reader who wanted to know "what is a version-2 header" had to
 * read the class that sealed archives to find out.
 *
 * So the format is its own file now: this one. It answers three questions and
 * holds nothing else:
 *
 * - **what does a container look like** — the sizes and the order of the fields;
 * - **which shapes exist** — [READABLE_VERSIONS] and [VERSION];
 * - **what are the bounds** — how large a change may be, how long a chain may be.
 *
 * The cipher calls live in [ArchiveCryptoSession], the framing lives in
 * [ArchiveFrame] and [ArchiveHead], and the version routing lives in
 * [ArchiveContainer].
 *
 * ## The versions
 *
 * A version is a **meaning**, not a format revision: a version is never reused,
 * and every build reads every version it shipped with. That is why this is a set
 * and not a number — the reader routes on the version it finds, and refuses
 * anything outside the set by name.
 *
 * | Version | Meaning |
 * |---|---|
 * | 1 | the full snapshot: one sealed stream, nothing else |
 * | 2 | a chain: a base and the sealed deltas that follow it |
 * | 3 | a chain sealed under a device key, with that key wrapped under the password |
 */
object ArchiveFormat {

    /** The bytes that say "an encrypted archive follows". */
    const val MAGIC = "SGBK"

    /** A chain sealed under the password: version 2. */
    const val CHAIN_VERSION = 2

    /** A chain sealed under the device key: version 3. */
    const val DEVICE_KEY_VERSION = 3

    /**
     * The newest version this build knows how to write.
     *
     * A version is a meaning, and the meaning is chosen by **how** an archive is
     * sealed, not by which build writes it:
     *
     * - [CHAIN_VERSION] — a chain whose content is sealed under the password.
     *   This is what a person pushing an archive from the app produces;
     * - [DEVICE_KEY_VERSION] — a chain whose content is sealed under the device
     *   key, which travels in the container wrapped under the password. This is
     *   what a scheduled export produces: it has no password to hand, and the
     *   person who restores the archive still needs theirs to unwrap the key.
     */
    const val LATEST_VERSION = DEVICE_KEY_VERSION

    /**
     * The versions this build **reads**.
     *
     * A version is never reused, and a build keeps reading the ones it shipped
     * with: an archive written before the chain existed still has to open. A
     * bump to [LATEST_VERSION] is not a bump to this set.
     */
    val READABLE_VERSIONS = setOf(1, CHAIN_VERSION, DEVICE_KEY_VERSION)

    /** The salt's length: 16 bytes, as PBKDF2 expects. */
    const val SALT_BYTES = ArchiveCryptoParams.SALT_BYTES

    /** The GCM nonce's length: 12 bytes, as GCM expects. */
    const val NONCE_BYTES = ArchiveCryptoParams.NONCE_BYTES

    /** The device key's length: 32 bytes, as AES-256 expects. */
    const val DEVICE_KEY_BYTES = 32

    /** Magic, version, salt and nonce — the data every version starts with. */
    const val PREFIX_BYTES = MAGIC.length + 1 + SALT_BYTES + NONCE_BYTES

    /** The head's length: the entry count (4) and the entries' total length (8). */
    const val HEAD_BYTES = 4 + 8

    /** A version-1 header: magic, version, salt and nonce, and nothing else. */
    const val HEADER_BYTES = PREFIX_BYTES

    /** A version-2 header: the prefix and the chain's head — the base's AAD. */
    const val HEADER_BYTES_V2 = PREFIX_BYTES + HEAD_BYTES

    /**
     * A version-3 header: the prefix, the head and the key block.
     *
     * The key block is fixed-length — a salt, a nonce, the wrapped device key and
     * the tag — so a reader knows where the entries start without reading
     * anything.
     */
    const val KEY_BLOCK_BYTES =
        SALT_BYTES + NONCE_BYTES + DEVICE_KEY_BYTES + ArchiveCryptoParams.TAG_BITS / 8

    /** A version-3 header: the prefix, the head and the key block. */
    const val HEADER_BYTES_V3 = PREFIX_BYTES + HEAD_BYTES + KEY_BLOCK_BYTES

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

    /**
     * The largest number of changes a chain may carry.
     *
     * A chain that only ever grows is a chain that never shrinks: a restore reads
     * the base and every delta, and a year of deltas is a restore that is slower
     * than the full snapshot it replaced. So the export starts a fresh base when
     * the chain passes this bound, and the old chain is the previous archive.
     *
     * The bound is a constant of the feature, not a knob: a person who can raise
     * it can make a restore unusable, and the number is worth more as a number
     * than as a setting. Thirty-two changes is a chain that still restores fast
     * and that a daily export fills in a month.
     */
    const val MAX_CHAIN_ENTRIES = 32

    /**
     * The largest total size of a chain's changes, in bytes.
     *
     * The count bound alone does not bound the memory: thirty-two changes of a
     * megabyte each is thirty-two megabytes the writer holds at once, because a
     * change has to be sealed before the head can say how long it is. This bound
     * is what makes [MAX_CHAIN_ENTRIES] mean something.
     */
    const val MAX_CHAIN_BYTES = 16L * 1024 * 1024
}
