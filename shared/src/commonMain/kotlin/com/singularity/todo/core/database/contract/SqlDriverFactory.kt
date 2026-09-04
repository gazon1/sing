@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package com.singularity.todo.core.database.contract

import androidx.sqlite.SQLiteDriver

/**
 * Platform-specific [SQLiteDriver] factory.
 *
 * Mirrors [com.singularity.todo.core.network.createHttpClient]: a tiny expect/actual seam
 * that resolves to a bundled native SQLite on both Android and JVM (no external native deps,
 * no `org.xerial:sqlite-jdbc` wrapper).
 *
 * The returned driver is *not* yet bound to a file path — call sites pass an absolute path
 * via [AppDatabaseFactory.build] and let Room wire it through.
 */
expect fun createSqlDriver(): SQLiteDriver