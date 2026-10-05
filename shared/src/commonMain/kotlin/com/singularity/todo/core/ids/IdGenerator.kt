package com.singularity.todo.core.ids

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

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
 * Thread-safe via [AtomicInt] — can be used in parallel test execution where
 * multiple coroutines may call [next] concurrently. [AtomicInt] is the stdlib's
 * multiplatform name for what is `java.util.concurrent.atomic.AtomicInteger` on
 * JVM and Android, so the counter is the same object it was before with a name
 * that also means something on a target without `java.util.concurrent`.
 *
 * `incrementAndFetch` is the stdlib's spelling of `AtomicInteger.incrementAndGet`;
 * the names differ because the API had to cover targets whose atomics do not use
 * the JVM's get/update vocabulary.
 *
 * The stdlib's atomic types are still marked experimental, so the opt-in is here
 * rather than pushed onto every test that constructs one of these.
 */
@OptIn(ExperimentalAtomicApi::class)
class SequenceIdGenerator(private val prefix: String = "id") : IdGenerator {
    private val counter = AtomicInt(0)
    override fun next(): String = "$prefix-${counter.incrementAndFetch()}"
}
