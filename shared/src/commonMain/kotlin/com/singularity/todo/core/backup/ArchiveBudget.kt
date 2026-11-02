package com.singularity.todo.core.backup

import okio.Buffer

/**
 * The limits a backup archive is read under.
 *
 * ## Why the limits exist
 *
 * A zip says how big its entries are on disk, not how big they become: an
 * archive whose entries are a few kilobytes can expand to gigabytes. A decoder
 * that reads every entry into memory whole is therefore a decoder that a small
 * file can kill — on Android, by exhausting the heap with no exception and no
 * error state.
 *
 * ## Why the limits live here
 *
 * There are two decoders — the JVM one and the Android one — and they drifted
 * once: only the JVM one had the limits, and the Android one read every entry
 * whole. The budget is one object that both call, rather than two copies of
 * two constants.
 */
object ArchiveBudget {
    /** Maximum number of entries in a backup archive. */
    const val MAX_ENTRIES = 100_000

    /** Maximum total decompressed bytes in a backup archive (64 MB). */
    const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

    /**
     * Throws when the archive has read more than the budget allows.
     *
     * The count and the byte total are checked together: an archive can be
     * small in bytes and large in entries, or the other way round.
     */
    fun check(
        entryCount: Int,
        totalBytes: Long,
    ) {
        if (entryCount > MAX_ENTRIES || totalBytes > MAX_TOTAL_BYTES) {
            throw IllegalStateException("backup archive exceeds limits: entries=$entryCount bytes=$totalBytes")
        }
    }

    /**
     * Reads one entry, refusing to read more than [limit] bytes.
     *
     * [readChunk] fills the buffer it is given and returns how many bytes it
     * wrote, or -1 when the entry is over. The limit is what makes the read
     * bounded: the bytes are counted as they arrive, not after the entry has
     * already been read whole.
     */
    fun readEntryBounded(
        limit: Long,
        readChunk: (buffer: ByteArray) -> Int,
    ): ByteArray {
        if (limit < 0) {
            throw IllegalStateException("backup archive entry exceeds remaining byte budget")
        }
        val out = Buffer()
        val buffer = ByteArray(CHUNK_BYTES)
        var total = 0L
        while (true) {
            val read = readChunk(buffer)
            if (read < 0) break
            total += read
            if (total > limit) {
                throw IllegalStateException("backup archive entry exceeds remaining byte budget")
            }
            out.write(buffer, 0, read)
        }
        return out.readByteArray()
    }

    private const val CHUNK_BYTES = 8192
}
