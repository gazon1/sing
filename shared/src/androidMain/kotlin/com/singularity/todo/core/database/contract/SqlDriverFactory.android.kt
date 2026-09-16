package com.singularity.todo.core.database.contract

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/**
 * Android uses the bundled native SQLite driver compiled into androidx.sqlite.
 * The framework driver ([androidx.sqlite.driver.AndroidSQLiteDriver]) would also work,
 * but Bundled keeps version drift at zero — the same driver runs on JVM too.
 */
actual fun createSqlDriver(): SQLiteDriver = BundledSQLiteDriver()
