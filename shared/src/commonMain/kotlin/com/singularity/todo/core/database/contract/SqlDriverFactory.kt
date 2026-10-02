@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package com.singularity.todo.core.database.contract

import androidx.sqlite.SQLiteDriver

/**
 * Platform-specific [SQLiteDriver] factory.
 *
 * Mirrors [com.singularity.todo.core.network.createHttpClient]: a tiny expect/actual seam
 * that resolves to a [WrappingDriver] wrapping the bundled native SQLite on both Android
 * and JVM (no external native deps, no `org.xerial:sqlite-jdbc` wrapper).
 *
 * [WrappingDriver] intercepts every [SQLiteDriver.open] call and applies
 * per-connection SQLite PRAGMAs (synchronous, cache_size, temp_store, foreign_keys,
 * busy_timeout) so that every connection Room acquires is properly tuned.
 *
 * The returned driver is *not* yet bound to a file path — call sites pass an absolute path
 * via [AppDatabaseFactory.build] and let Room wire it through.
 */
expect fun createSqlDriver(): SQLiteDriver
