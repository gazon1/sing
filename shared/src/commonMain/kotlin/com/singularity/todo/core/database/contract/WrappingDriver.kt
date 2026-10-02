package com.singularity.todo.core.database.contract

import androidx.sqlite.SQLiteDriver

/**
 * A [SQLiteDriver] wrapper that intercepts every [open] call to apply per-connection
 * SQLite PRAGMAs before returning the connection to the caller.
 *
 * ## Why a wrapper?
 *
 * `PlatformPragmas.applyTo()` was previously called **before** `Room.databaseBuilder`,
 * opening a throwaway connection that applied PRAGMAs then closed immediately. Room's
 * own connections from `BundledSQLiteDriver`'s internal pool did **not** inherit those
 * settings. Only `journal_mode=WAL` (file-level/persistent) was effective.
 *
 * `WrappingDriver` solves this by intercepting `open()` on **every** connection Room
 * acquires from the pool, applying per-connection PRAGMAs each time.
 *
 * ## Platform differences
 *
 * - **Android**: `BundledSQLiteDriver` is an open class — [wrappingDriver] extends it.
 * - **JVM**: `BundledSQLiteDriver` is a final class — [wrappingDriver] uses composition
 *   and implements the [SQLiteDriver] interface by delegating to an internal driver.
 *
 * @see PlatformPragmas.PerConnectionCommands
 */
expect val wrappingDriver: SQLiteDriver
