package com.singularity.todo.core.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.singularity.todo.core.database.contract.createSqlDriver
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The fixture behind the generated [MigrationRoundTripTest].
 *
 * ## The property
 *
 * Room validates the schema it finds after a migration against the entities the
 * code declares: a migration that produces something else is a crash on the
 * first query of an upgraded database, and a fresh install never sees it. This
 * fixture is what makes that failure visible in a test — it starts from a
 * *real exported schema* rather than from a database the test suite built
 * itself.
 *
 * ## How one round trip runs
 *
 * 1. The exported schema of one version is applied to an empty file — the DDL
 *    Room itself exported, plus the queries Room itself runs to register the
 *    identity hash. `user_version` is set to that version, so the file claims
 *    to be what a user of that version would have.
 * 2. One row is inserted into `tasks`, with the values the column types
 *    demand. The row is the point: a schema that survives the chain while the
 *    data in it does not is not an upgrade.
 * 3. The file is opened through Room, which runs the whole chain and then
 *    validates the schema it produced. Room's own check is the assertion; this
 *    fixture only has to hand it a database worth checking.
 * 4. The anchor row is read back. It must still be there.
 *
 * ## Why the values are curated
 *
 * `tasks` carries three columns that Room stores through a type converter —
 * `priority`, `kind` and `sync_status`. Their stored form is the enum's name,
 * so `None`, `Task` and `LOCAL_ONLY` are the values a real row holds. A value
 * invented by the fixture (`'rt'`) would be a string no enum can parse, and a
 * migration that reads the column would fail on the fixture rather than on the
 * defect it is meant to expose.
 */
internal const val ROUND_TRIP_ANCHOR_ID = "round-trip-anchor"

/** The stored form of the columns the anchor row cannot leave empty. */
private val ANCHOR_VALUES: Map<String, String> = mapOf(
    "id" to "'$ROUND_TRIP_ANCHOR_ID'",
    "title" to "'Round trip anchor'",
    "priority" to "'None'",
    "kind" to "'Task'",
    "sync_status" to "'LOCAL_ONLY'",
    "user_id" to "'round-trip-user'",
    "someday" to "0",
    "is_pinned" to "0",
    "created_at" to "1",
    "updated_at" to "1",
    "server_version" to "0",
)

/** The placeholder Room writes into the exported DDL. */
private const val TABLE_NAME = "\${TABLE_NAME}"

/**
 * Take the database of [version] through the whole chain and back.
 *
 * The database that comes out has to be the schema the head declares, and the
 * row that went in has to still be there.
 */
internal fun roundTripFrom(version: Int) = runTest {
    val directory = Files.createTempDirectory("room-round-trip-$version-").toFile()
    val dbPath = File(directory, "fixture.db").absolutePath
    try {
        seedFixture(version, dbPath)
        val database = AppDatabaseFactory.build(createSqlDriver(), dbPath)
        try {
            assertEquals(
                1L,
                anchorCount(database),
                "the anchor row must survive the chain from v$version — a migration that " +
                    "produces the right schema and loses the data is still a broken upgrade",
            )
        } finally {
            database.close()
        }
    } finally {
        directory.deleteRecursively()
    }
}

/**
 * The exported schema of [version] — the one place that knows where they live.
 *
 * The path was written out in every migration test that needed it, and each copy
 * counted the same three `parentFile`s to get there: a copy that counted one too
 * many looked for the schema outside the module and reported it as missing, which
 * reads like a missing export rather than like a wrong path.
 */
internal fun schemaFile(version: Int): File {
    val root = System.getProperty("commonMain.root")
        ?: error("the commonMain.root system property is not set — see shared/build.gradle.kts")
    val module = File(root).parentFile.parentFile.parentFile
    return File(module, "schemas/com.singularity.todo.core.database.AppDatabase/$version.json")
}

/**
 * Build the database a user of [version] would have had.
 *
 * The DDL is Room's own export, not something written here: a fixture that
 * approximated the schema would test the approximation.
 *
 * This is the one place that does it. Every migration test used to carry its own
 * copy of this function — twelve copies of the same thirty lines, each of which
 * could drift from the export and from the other copies, and each of which had to
 * be found again by whoever read the next migration.
 */
internal fun applyExportedSchema(version: Int, connection: SQLiteConnection) {
    val database = exportedSchema(version)
    for (entity in database["entities"]!!.jsonArray) {
        val table = entity.jsonObject
        val tableName = table["tableName"]!!.jsonPrimitive.content
        connection.execSQL(
            table["createSql"]!!.jsonPrimitive.content.replace(TABLE_NAME, tableName),
        )
        for (index in table["indices"]?.jsonArray.orEmpty()) {
            connection.execSQL(
                index.jsonObject["createSql"]!!.jsonPrimitive.content.replace(TABLE_NAME, tableName),
            )
        }
    }
    for (query in database["setupQueries"]!!.jsonArray) {
        connection.execSQL(query.jsonPrimitive.content)
    }
    connection.execSQL("PRAGMA user_version = $version")
}

/** The `database` section of the exported schema of [version]. */
private fun exportedSchema(version: Int): JsonObject {
    val schema = schemaFile(version)
    assertTrue(schema.isFile, "the exported schema of v$version is missing at $schema")
    return Json.parseToJsonElement(schema.readText())
        .jsonObject["database"]!!
        .jsonObject
}

/**
 * Build the database a user of [version] would have had, with one row in it.
 *
 * The schema comes from [applyExportedSchema]; the row is the point: a schema
 * that survives the chain while the data in it does not is not an upgrade.
 */
private fun seedFixture(version: Int, dbPath: String) {
    val database = exportedSchema(version)
    createSqlDriver().open(dbPath).use { connection ->
        applyExportedSchema(version, connection)
        insertAnchor(connection, database)
    }
}

/**
 * Insert the one row the round trip watches.
 *
 * The columns are the ones the schema says cannot be empty; the values come
 * from [ANCHOR_VALUES] where the column is one the anchor knows, and from the
 * column's affinity where it is not.
 */
private fun insertAnchor(connection: SQLiteConnection, database: JsonObject) {
    val tasks = database["entities"]!!.jsonArray
        .map { it.jsonObject }
        .first { it["tableName"]!!.jsonPrimitive.content == "tasks" }
    val required = tasks["fields"]!!.jsonArray
        .map { it.jsonObject }
        .filter { it.flag("notNull") && "defaultValue" !in it }
    val columns = required.joinToString(", ") { it["columnName"]!!.jsonPrimitive.content }
    val values = required.joinToString(", ") { column ->
        val name = column["columnName"]!!.jsonPrimitive.content
        ANCHOR_VALUES[name] ?: fallbackValue(column["affinity"]!!.jsonPrimitive.content)
    }
    connection.execSQL("INSERT INTO tasks ($columns) VALUES ($values)")
}

/** A value of the shape the column's affinity asks for. */
private fun fallbackValue(affinity: String): String = when (affinity.uppercase()) {
    "INTEGER" -> "0"
    "REAL" -> "0.0"
    "BLOB" -> "X'00'"
    else -> "'round-trip'"
}

/** Whether a JSON object carries a true flag. */
private fun JsonObject.flag(name: String): Boolean =
    (this[name] as? JsonPrimitive)?.booleanOrNull == true

/** How many anchor rows the upgraded database holds. */
private suspend fun anchorCount(database: AppDatabase): Long =
    database.useConnection(isReadOnly = true) { transactor ->
        transactor.usePrepared(
            "SELECT COUNT(*) FROM tasks WHERE id = '$ROUND_TRIP_ANCHOR_ID'",
        ) { statement ->
            statement.step()
            statement.getLong(0)
        }
    }
