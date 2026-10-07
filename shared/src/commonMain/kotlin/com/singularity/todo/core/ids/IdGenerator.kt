package com.singularity.todo.core.ids

/**
 * Abstraction over ID generation so tests can use deterministic sequences
 * instead of random ULIDs.
 *
 * Production: [UlidIdGenerator] — generates lexicographically sortable ULIDs.
 * Tests: `SequenceIdGenerator`, which lives in `test/fakes/` — a test double in a
 * production source set is a double nothing can be stopped from shipping, which is
 * what `find-unwired-surfaces.py` reports it as.
 */
interface IdGenerator {
    fun next(): String
}

/** Production implementation — delegates to the existing [nextId] ULID generator. */
object UlidIdGenerator : IdGenerator {
    override fun next(): String = nextId()
}
