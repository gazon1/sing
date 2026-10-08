package com.singularity.todo.core.ids

/**
 * Abstraction over ID generation so tests can use deterministic sequences
 * instead of random UUIDs.
 *
 * Production: [UuidIdGenerator] — generates random UUIDs.
 * Tests: `SequenceIdGenerator`, which lives in `test/fakes/` — a test double in a
 * production source set is a double nothing can be stopped from shipping, which is
 * what `find-unwired-surfaces.py` reports it as.
 */
interface IdGenerator {
    fun next(): String
}

/** Production implementation — delegates to the existing [nextId] UUID generator. */
object UuidIdGenerator : IdGenerator {
    override fun next(): String = nextId()
}
