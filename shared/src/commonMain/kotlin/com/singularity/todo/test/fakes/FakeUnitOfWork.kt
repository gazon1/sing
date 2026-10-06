package com.singularity.todo.test.fakes

import com.singularity.todo.core.database.UnitOfWork

/**
 * [UnitOfWork] that runs the block and nothing else.
 *
 * ## Why a repository test uses this rather than the Room one
 *
 * `UnitOfWorkIsAtomicTest` is what asserts that a unit of work actually rolls back,
 * against a real database. That test cannot be merged into every repository test
 * without putting a database file and a `slow` tag in front of assertions about tag
 * colours and read isolation.
 *
 * So repository tests get the *shape* — a block that runs — and one file carries the
 * *guarantee*. Splitting them this way keeps the guarantee tested once, in the only
 * place that can actually observe it, instead of diluted across a dozen tests that
 * would each only prove the block was called.
 *
 * ## What these tests therefore do not prove
 *
 * That these particular repositories opened a transaction. They prove the write and
 * the enqueue both happened, in order, and nothing else changed. A repository that
 * dropped its `unitOfWork.write { … }` wrapper would keep every one of these tests
 * green — which is a real limit of this double and the reason the port's own test is
 * a separate class rather than a fixture these tests reuse.
 */
class FakeUnitOfWork : UnitOfWork {
    /** How many transactions have been opened, so a test can assert one was. */
    var opens: Int = 0
        private set

    override suspend fun <R> write(block: suspend () -> R): R {
        opens++
        return block()
    }
}
