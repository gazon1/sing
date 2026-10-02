package com.singularity.todo.core.database.contract

import androidx.sqlite.SQLiteDriver

/**
 * JVM uses the *same* bundled native SQLite driver as Android. In androidx.sqlite 2.7.0
 * the JVM actual of [androidx.sqlite.SQLiteDriver] is [androidx.sqlite.driver.bundled.BundledSQLiteDriver]
 * (no external `org.xerial:sqlite-jdbc` wrapper needed — that was only required by the older
 * hand-rolled JDBC `JvmDatabase` which we are replacing).
 *
 * The returned driver is a wrapper that intercepts every [SQLiteDriver.open] call to apply
 * per-connection PRAGMAs ([PlatformPragmas.applyTo]) — including every connection that
 * Room acquires from its internal pool.
 */
actual fun createSqlDriver(): SQLiteDriver = wrappingDriver
