package com.singularity.todo.test.fakes

import com.singularity.todo.core.ids.IdGenerator
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * Alias for [SequenceIdGenerator].
 * Use this in tests for predictable, sequential IDs.
 *
 * Example:
 * ```
 * val idGen = FakeIdGenerator("task")
 * assertEquals("task-1", idGen.next())
 * assertEquals("task-2", idGen.next())
 * ```
 */
typealias FakeIdGenerator = SequenceIdGenerator

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
 *
 * ## Why this file and not `core/ids/`
 *
 * It used to live in `core/ids/IdGenerator.kt`, beside the interface it implements,
 * and only tests ever constructed it. `find-unwired-surfaces.py` reports exactly
 * that shape — 19 test references, no production caller — and its finding says a
 * double belongs in `test/fakes/`, where the other 13 already live. Beside the
 * interface is where a double is hardest to notice and easiest to ship.
 */
@OptIn(ExperimentalAtomicApi::class)
class SequenceIdGenerator(private val prefix: String = "id") : IdGenerator {
    private val counter = AtomicInt(0)
    override fun next(): String = "$prefix-${counter.incrementAndFetch()}"
}
