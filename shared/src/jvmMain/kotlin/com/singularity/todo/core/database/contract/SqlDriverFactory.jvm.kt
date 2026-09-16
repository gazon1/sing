package com.singularity.todo.core.database.contract

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/**
 * JVM uses the *same* bundled native SQLite driver as Android. In androidx.sqlite 2.7.0
 * the JVM actual of [androidx.sqlite.SQLiteDriver] is `BundledSQLiteDriver` (no external
 * `org.xerial:sqlite-jdbc` wrapper needed — that was only required by the older
 * hand-rolled JDBC `JvmDatabase` which we are replacing).
 */
actual fun createSqlDriver(): SQLiteDriver = BundledSQLiteDriver()
