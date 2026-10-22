package com.singularity.todo.core.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * One column of a table, as the fixture database declares it.
 *
 * @property name The column's name.
 * @property type The declared type — `INTEGER`, `TEXT`, `REAL`, ….
 * @property notNull Whether the column refuses a null.
 * @property hasDefault Whether the column carries a default of its own.
 */
private data class FixtureColumn(val name: String, val type: String, val notNull: Boolean, val hasDefault: Boolean)

/**
 * Inserts a task row into a fixture database, filling what the schema demands.
 *
 * ## Why this exists
 *
 * A migration test seeds a database in the shape of an *older* version: the
 * fixture applies the exported schema of that version, and the rows it writes
 * have to satisfy that schema's `NOT NULL` columns. Writing those rows by hand
 * means writing the column list by hand too, and the list changes with every
 * schema change — a column added in v45 makes the v44 fixture fail with
 * `NOT NULL constraint failed: tasks.someday`, which reads like a broken
 * migration rather than a stale fixture.
 *
 * So the fixture asks the database what it wants:
 *
 * - a column with a default of its own is left out — the default applies;
 * - a nullable column without a default is left out — it becomes null;
 * - a `NOT NULL` column is filled with a value that fits it: the ones the
 *   task model gives a meaning (`title`, `priority`, `kind`, …) get that
 *   meaning, and any other gets the zero of its type.
 *
 * A column the caller names in [columns] wins over all of it — that is how a
 * test writes the one thing it cares about (a due date, a series id) without
 * restating the rest.
 *
 * ## The properties that matter
 *
 * 1. **The row is inserted** — the fixture does not fail on a `NOT NULL`
 *    column the test never thought about.
 * 2. **What the caller wrote is what is stored** — the override is not
 *    overwritten by the default.
 * 3. **The zero of the type is a zero** — a `NOT NULL` column the model knows
 *    nothing about gets `0`, `0.0` or `''`, never the word `null`.
 */
fun insertLegacyTask(
    connection: SQLiteConnection,
    id: String,
    title: String = "Task $id",
    userId: String = "u1",
    columns: Map<String, String> = emptyMap(),
) {
    val known: Map<String, String> = mapOf(
        "id" to id,
        "title" to title,
        "user_id" to userId,
        "priority" to "None",
        "kind" to "Task",
        "someday" to "0",
        "is_pinned" to "0",
        "created_at" to "1000",
        "updated_at" to "1000",
        "server_version" to "0",
        "sync_status" to "LOCAL_ONLY",
    )

    val declared = fixtureColumns(connection, "tasks")
    val names = mutableListOf<String>()
    val values = mutableListOf<String>()

    for (column in declared) {
        val override = columns[column.name]
        when {
            override != null -> {
                names += column.name
                values += literal(override)
            }

            column.hasDefault -> Unit

            // the default applies
            column.notNull -> {
                names += column.name
                values += literal(known[column.name] ?: zeroOf(column.type))
            }

            else -> Unit // nullable and without a default: null is right
        }
    }

    require(names.isNotEmpty()) { "the fixture schema declares no column of tasks" }
    connection.execSQL(
        "INSERT INTO tasks (${names.joinToString(", ")}) VALUES (${values.joinToString(", ")})",
    )
}

/**
 * The columns of a table, as the fixture database declares them.
 */
private fun fixtureColumns(connection: SQLiteConnection, table: String): List<FixtureColumn> {
    val statement = connection.prepare("PRAGMA table_info($table)")
    try {
        val columns = mutableListOf<FixtureColumn>()
        while (statement.step()) {
            columns += FixtureColumn(
                name = statement.getText(1),
                type = statement.getText(2),
                notNull = statement.getInt(3) != 0,
                hasDefault = !statement.isNull(4),
            )
        }
        return columns
    } finally {
        statement.close()
    }
}

/**
 * The zero of a type — what a `NOT NULL` column gets when nothing knows better.
 */
private fun zeroOf(type: String): String = when (type.uppercase()) {
    "INTEGER", "INT" -> "0"
    "REAL", "DOUBLE", "FLOAT" -> "0.0"
    else -> "''"
}

/**
 * A value as a SQL literal — a number as itself, anything else as a quoted
 * string with the quotes inside it doubled.
 */
private fun literal(value: String): String =
    if (value.toLongOrNull() != null) value else "'${value.replace("'", "''")}'"
