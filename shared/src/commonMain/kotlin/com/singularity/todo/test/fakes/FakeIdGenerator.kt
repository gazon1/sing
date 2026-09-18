package com.singularity.todo.test.fakes

import com.singularity.todo.core.ids.IdGenerator

/**
 * Alias for [com.singularity.todo.core.ids.SequenceIdGenerator].
 * Use this in tests for predictable, sequential IDs.
 *
 * Example:
 * ```
 * val idGen = FakeIdGenerator("task")
 * assertEquals("task-1", idGen.next())
 * assertEquals("task-2", idGen.next())
 * ```
 */
typealias FakeIdGenerator = com.singularity.todo.core.ids.SequenceIdGenerator
