package com.singularity.todo.core.ids

import kotlin.uuid.Uuid

/**
 * Generates a unique id using [Uuid.random].
 *
 * UUIDs are 36-char hex strings with dashes. Drop-in replacement for
 * `java.util.UUID.randomUUID().toString()`.
 *
 * Pure commonMain — no platform deps.
 */
fun nextId(): String = Uuid.random().toString()
