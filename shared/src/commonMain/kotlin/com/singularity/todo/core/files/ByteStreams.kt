package com.singularity.todo.core.files

/**
 * A source of bytes, read in pieces.
 *
 * ## Why the project has its own
 *
 * The archive used to travel as one `ByteArray`: read from the disk in full,
 * encrypted in full, uploaded in full. For a small archive that is nothing, and
 * for a large one — the archive of an account with attachments — it is the whole
 * account twice over in the app's memory, on a phone that may not have that much
 * to spare.
 *
 * The platform already has the right shape for this: `InputStream` and
 * `OutputStream` read and write in pieces. They cannot be named here, because
 * `commonMain` may not use `java.*` — and the archive's own code lives there.
 *
 * So the project has two small interfaces with the same shape, and the platform
 * halves adapt to them.
 *
 * ## The contract
 *
 * A source hands out what it has and says when it has nothing left: a read
 * returns the number of bytes it put into the buffer, and **-1** means the end.
 * It may return fewer bytes than the buffer could hold — that is not the end, and
 * a reader that treats a short read as the end loses the rest of the archive.
 *
 * A sink takes what it is given and keeps it.
 */
interface ByteSource {

    /**
     * Reads up to [length] bytes into [buffer], starting at [offset].
     *
     * @return the number of bytes read, or -1 when there is nothing left.
     */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    /**
     * Releases the source.
     *
     * A file that was opened for reading holds a handle until it is closed, and
     * a source that is not closed is a file nobody can delete — which matters
     * here, because the archive is written next to the file it came from.
     */
    fun close()
}

/**
 * A sink of bytes, written in pieces.
 *
 * The counterpart of [ByteSource]: whatever is written is kept, in the order it
 * was written.
 */
interface ByteSink {

    /** Writes [length] bytes of [buffer], starting at [offset]. */
    fun write(buffer: ByteArray, offset: Int, length: Int)

    /**
     * Releases the sink.
     *
     * **Closing is what makes the bytes real.** A file's stream buffers what it
     * was given, and the last piece of an archive is still in the buffer until
     * the stream is closed — so a caller that writes and forgets to close
     * produces a truncated file and no complaint.
     */
    fun close()
}

/**
 * Reads exactly [count] bytes, or fails.
 *
 * Some things have to be read whole — a container's header, for one — and a
 * source that hands out a piece at a time has to be read until it has enough.
 * Reading fewer bytes than were asked for is not an error here; it is the source
 * saying "not yet".
 *
 * @throws IllegalStateException when the source ends before [count] bytes are
 *   read. The caller decides what that means — for the archive's header it means
 *   "these bytes are not an encrypted archive".
 */
fun ByteSource.readExactly(count: Int): ByteArray {
    val buffer = ByteArray(count)
    var filled = 0
    while (filled < count) {
        val read = read(buffer, filled, count - filled)
        if (read < 0) throw IllegalStateException("the source ended after $filled of $count bytes")
        filled += read
    }
    return buffer
}

/** A source that hands out the bytes it was given, in order. */
class ByteArraySource(private val bytes: ByteArray) : ByteSource {

    private var position = 0

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= bytes.size) return -1
        val count = minOf(length, bytes.size - position)
        bytes.copyInto(buffer, offset, position, position + count)
        position += count
        return count
    }

    override fun close() = Unit
}

/** A sink that keeps everything it is given. */
class ByteArraySink : ByteSink {

    private var buffer = ByteArray(0)

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        this.buffer += buffer.copyOfRange(offset, offset + length)
    }

    override fun close() = Unit

    /** What has been written so far. */
    fun bytes(): ByteArray = buffer
}
