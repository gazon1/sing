package com.singularity.todo.core.ids

import com.github.f4b6a3.ulid.UlidCreator

/**
 * Generates a sortable unique id (ULID).
 *
 * ULIDs are 26-char Crockford base32 strings, lexicographically sortable by
 * creation time. Drop-in replacement for `java.util.UUID.randomUUID().toString()`.
 *
 * Pure commonMain — no platform deps.
 */
fun nextId(): String = UlidCreator.getUlid().toString()
