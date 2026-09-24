package com.singularity.todo.core.ids

import java.util.concurrent.atomic.AtomicInteger

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
 *
 * Thread-safe via [AtomicInteger] — can be used in parallel test execution
 * where multiple coroutines may call [next] concurrently.
 */
class SequenceIdGenerator(private val prefix: String = "id") : IdGenerator {
    private val counter = AtomicInteger(0)
    override fun next(): String = "$prefix-${counter.incrementAndGet()}"
}
