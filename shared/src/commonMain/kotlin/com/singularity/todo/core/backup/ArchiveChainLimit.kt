package com.singularity.todo.core.backup

/**
 * The chain has to fit its bound.
 *
 * The count is what a restore reads: a chain of a thousand changes is a
 * restore that is slower than the snapshot it replaced. The size is what the
 * writer holds: the changes are sealed into memory before the head can say
 * how long they are, so an unbounded chain is an unbounded allocation.
 *
 * Both are refused **by name**. At export time, because the export re-seals a
 * fresh base instead. At restore time, because the count in the head is read
 * before the password is proven — a count nobody has proven yet must not
 * become a loop that allocates.
 *
 * @param count how many changes the chain claims to carry.
 * @param direction what this build does with that many — `writes` at export
 *   time, `reads` at restore time. The message says it, because "more than
 *   this build writes" would be a lie to a person reading a restore.
 */
internal fun requireChainFits(count: Int, direction: String) {
    if (count < 0 || count > ArchiveFormat.MAX_CHAIN_ENTRIES) {
        throw ArchiveEnvelopeError.ChainTooLong(
            "it promises $count changes, and this build $direction at most " +
                ArchiveFormat.MAX_CHAIN_ENTRIES,
        )
    }
}
