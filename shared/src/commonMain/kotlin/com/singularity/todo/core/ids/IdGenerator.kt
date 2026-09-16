package com.singularity.todo.core.ids

/**
 * Abstraction over ID generation so tests can use deterministic sequences
 * instead of random ULIDs.
 *
 * Production: [UlidIdGenerator] — generates lexicographically sortable ULIDs.
 * Tests: [SequenceIdGenerator] — produces "prefix-1", "prefix-2", … for stable assertions.
 */
interface IdGenerator {
    fun next(): String
}

/** Production implementation — delegates to the existing [nextId] ULID generator. */
object UlidIdGenerator : IdGenerator {
    override fun next(): String = nextId()
}

/**
 * Test-only generator that yields predictable IDs.
 * Use in widget/integration tests when you need stable, assertable IDs.
 *
 * Example: `val gen = SequenceIdGenerator("msg")` → "msg-1", "msg-2", …
 */
class SequenceIdGenerator(private val prefix: String = "id") : IdGenerator {
    private var counter = 0
    override fun next(): String = "$prefix-${++counter}"
}
