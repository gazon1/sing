package com.singularity.todo.core.database.contract

import androidx.sqlite.SQLiteDriver

/**
 * Android uses the bundled native SQLite driver compiled into androidx.sqlite.
 * The framework driver ([androidx.sqlite.driver.AndroidSQLiteDriver]) would also work,
 * but Bundled keeps version drift at zero — the same driver runs on JVM too.
 *
 * The returned driver is a wrapper that intercepts every [SQLiteDriver.open] call to apply
 * per-connection PRAGMAs ([PlatformPragmas.applyTo]) — including every connection that
 * Room acquires from its internal pool.
 */
actual fun createSqlDriver(): SQLiteDriver = wrappingDriver
