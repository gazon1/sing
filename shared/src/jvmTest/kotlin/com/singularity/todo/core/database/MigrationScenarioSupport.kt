package com.singularity.todo.core.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.singularity.todo.core.database.contract.createSqlDriver
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals

/**
 * One value the scenario expects to find after the migration.
 *
 * A query and the text its first column must hold. The text is a string
 * because that is what a column comes back as, and a scenario that expects a
 * number says `equals: 0` — which reads as the number it means.
 */
data class MigrationExpect(val sql: String, val equals: String)

/**
 * One thing a scenario plants before the migration runs.
 *
 * A statement is a statement. A task row is planted by the fixture that knows
 * what the older schema demands — a scenario says which task it needs and
 * which one column of it is the point, and the fixture fills the rest.
 */
sealed interface MigrationSeed {

    /** A statement, run as it is written. */
    data class Sql(val statement: String) : MigrationSeed

    /** A task row, planted by the legacy-task fixture. */
    data class LegacyTask(val id: String, val columns: Map<String, String>) : MigrationSeed
}

/**
 * The fixture behind the generated [GeneratedMigrationScenarioTest].
 *
 * ## The property
 *
 * A migration that produces the right schema and loses the data is still a
 * broken upgrade, and a schema-shaped test cannot see it. The scenarios say
 * what the data must look like afterwards: a backfilled column, a row that was
 * deleted because it could not satisfy a NOT NULL constraint, a link that
 * survived a table rebuild.
 *
 * ## How one scenario runs
 *
 * 1. The exported schema of the scenario's version is applied to an empty
 *    file — Room's own DDL, so the fixture is what a user of that version
 *    would have had.
 * 2. The scenario's rows are planted, and its statements are run. This is the
 *    arrangement: the state a user could really have had.
 * 3. The file is opened through Room, which runs the whole chain and then
 *    validates the schema it produced. Room's own check is part of the
 *    assertion; the scenarios only have to hand it a database worth checking.
 * 4. The scenario's queries are run against the upgraded file, and their
 *    first column is compared with the value the scenario declares.
 *
 * ## Why the chain, and not one step
 *
 * Room applies the whole chain from the fixture's version, so a scenario
 * written for 31 → 32 is checked against the head schema. The step's own
 * effect is still what the scenario asserts — a backfill that happened at 31 →
 * 32 is visible at the head — but a scenario cannot claim that the *only*
 * thing that changed between 31 and 32 was its own. A scenario that needs
 * that has to say so in its own name.
 */
internal suspend fun runMigrationScenario(
    from: Int,
    to: Int,
    name: String,
    seed: List<MigrationSeed>,
    expect: List<MigrationExpect>,
) {
    val directory = Files.createTempDirectory("room-scenario-$from-").toFile()
    val dbPath = File(directory, "fixture.db").absolutePath
    try {
        createSqlDriver().open(dbPath).use { connection ->
            applyExportedSchema(from, connection)
            for (item in seed) {
                when (item) {
                    is MigrationSeed.Sql -> connection.execSQL(item.statement)

                    is MigrationSeed.LegacyTask -> insertLegacyTask(
                        connection,
                        id = item.id,
                        columns = item.columns,
                    )
                }
            }
        }

        // Opening through Room runs the chain, and Room validates the schema it
        // finds against the entities the code declares. A migration that
        // produces something else fails here, before the scenario is even read.
        //
        // Building the database object does NOT open the file: Room opens on the
        // first access. A read of the tasks table is the cheapest probe — the
        // table exists in every version, and the row does not, so the read
        // returns nothing and changes nothing. Without it the chain never runs
        // and the scenario reads the fixture as it was planted.
        val database = AppDatabaseFactory.build(createSqlDriver(), dbPath)
        database.taskDao().getById("__scenario_probe__")
        database.close()

        createSqlDriver().open(dbPath).use { connection ->
            for (check in expect) {
                val actual = firstText(connection, check.sql)
                assertEquals(
                    check.equals,
                    actual,
                    "v$from to v$to — $name: the query must return the value the " +
                        "scenario declares — ${check.sql}",
                )
            }
        }
    } finally {
        directory.deleteRecursively()
    }
}

/**
 * The first column of the first row the query returns, as text.
 *
 * A NULL reads as the text `null`, which is what SQLite itself prints for it.
 * A scenario that wants to assert a NULL says `equals: null`, and a scenario
 * that wants to assert the string "null" has to say so in its query.
 */
private fun firstText(connection: SQLiteConnection, sql: String): String {
    val statement = connection.prepare(sql)
    try {
        if (!statement.step()) return "null"
        if (statement.isNull(0)) return "null"
        return statement.getText(0)
    } finally {
        statement.close()
    }
}
