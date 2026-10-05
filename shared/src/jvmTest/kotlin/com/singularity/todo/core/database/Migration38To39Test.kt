package com.singularity.todo.core.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.singularity.todo.core.database.contract.createSqlDriver
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The v38→v39 upgrade ([Migration38To39]) and what it does to a profile's owner.
 *
 * v39 adds `profiles.user_id`. The DDL is validated by Room against the exported schema
 * when the database is opened, so what is worth testing is the decision the column
 * encodes: an existing profile comes out of the upgrade **unowned**, not attributed to
 * whoever happens to be signed in.
 *
 * ## Why NULL is the thing to assert
 *
 * The alternative — filling the column with the session's user id during the migration —
 * would file one account's profile under another, silently, and that misattribution
 * would then decide whose profile an account-switch erase deletes. A test asserting
 * "the column exists" passes either way; only asserting the value catches it. An empty
 * string is just as wrong as a real id and is the shape a `DEFAULT ''` produces, so
 * asserting NULL distinguishes "unowned" from "owned by nobody in particular".
 *
 * The row itself surviving is asserted too, because the failure mode of the other kind
 * of migration is dropping data the user can see.
 */
@Tag("fast")
class Migration38To39Test {

    private lateinit var tempDir: File
    private lateinit var dbPath: String

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("migration38to39-").toFile()
        dbPath = File(tempDir, "fixture.db").absolutePath
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /** Applies every entity DDL + `setupQueries` from the exported v38 schema. */
    private fun applyV38Fixture(connection: SQLiteConnection) {
        // commonMain.root = <shared>/src/commonMain/kotlin → schemas live at <shared>/schemas/…
        val sharedDir = File(System.getProperty("commonMain.root")!!)
            .parentFile.parentFile.parentFile
        val schemaJson = File(
            sharedDir,
            "schemas/com.singularity.todo.core.database.AppDatabase/38.json",
        )
        assertTrue(schemaJson.exists(), "v38 schema not found at $schemaJson")

        val database = Json.parseToJsonElement(schemaJson.readText()).jsonObject["database"]!!.jsonObject
        for (entity in database["entities"]!!.jsonArray) {
            val obj = entity.jsonObject
            val tableName = obj["tableName"]!!.jsonPrimitive.content
            connection.execSQL(
                obj["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", tableName),
            )
            for (index in obj["indices"]?.jsonArray.orEmpty()) {
                connection.execSQL(
                    index.jsonObject["createSql"]!!.jsonPrimitive.content
                        .replace("\${TABLE_NAME}", tableName),
                )
            }
        }
        for (query in database["setupQueries"]!!.jsonArray) {
            connection.execSQL(query.jsonPrimitive.content)
        }
        connection.execSQL("PRAGMA user_version = 38")
    }

    /** A profile as it existed at v38: no owner, because the column did not exist. */
    private fun seedProfile(connection: SQLiteConnection, id: String, name: String) {
        connection.execSQL(
            "INSERT INTO profiles (id,name,emoji,color_idx,is_default,created_at,updated_at) " +
                "VALUES ('$id','$name','🏠',0,0,100,100)",
        )
    }

    /**
     * The stored owner, or null when the column is SQL NULL.
     *
     * `getText()` is not asked to distinguish the two cases: on this driver it returns
     * `""` for a NULL column, which would make an unowned profile and a profile owned
     * by an empty id read identically — exactly the distinction this test exists to make.
     * `isNull()` is the API for it.
     */
    private fun readUserId(connection: SQLiteConnection, id: String): String? {
        val statement = connection.prepare("SELECT user_id FROM profiles WHERE id = ?")
        return try {
            statement.bindText(1, id)
            when {
                !statement.step() -> null
                statement.isNull(0) -> null
                else -> statement.getText(0)
            }
        } finally {
            statement.close()
        }
    }

    private fun countProfiles(connection: SQLiteConnection): Int {
        val statement = connection.prepare("SELECT COUNT(*) FROM profiles")
        return try {
            statement.step()
            statement.getLong(0).toInt()
        } finally {
            statement.close()
        }
    }

    @Test
    fun `an existing profile survives the upgrade`() = runTest {
        createSqlDriver().open(dbPath).use { connection ->
            applyV38Fixture(connection)
            seedProfile(connection, "p-default", "Personal")
            seedProfile(connection, "p-work", "Work")

            Migration38To39().migrate(connection)

            assertEquals(2, countProfiles(connection), "the upgrade dropped a profile row")
        }
    }

    /** The load-bearing assertion: a pre-existing profile is **not** attributed. */
    @Test
    fun `an existing profile is unowned after the upgrade, not guessed at`() = runTest {
        createSqlDriver().open(dbPath).use { connection ->
            applyV38Fixture(connection)
            seedProfile(connection, "p-default", "Personal")

            Migration38To39().migrate(connection)

            assertNull(
                readUserId(connection, "p-default"),
                "the upgrade attributed an existing profile instead of leaving it unowned",
            )
        }
    }

    @Test
    fun `a profile written after the upgrade keeps the owner it was given`() = runTest {
        createSqlDriver().open(dbPath).use { connection ->
            applyV38Fixture(connection)
            Migration38To39().migrate(connection)

            connection.execSQL(
                "INSERT INTO profiles (id,name,emoji,color_idx,is_default,created_at,updated_at,user_id) " +
                    "VALUES ('p-new','New','🤖',1,0,200,200,'owner-1')",
            )

            assertEquals(
                "owner-1",
                readUserId(connection, "p-new"),
                "the column did not store the owner it was given",
            )
        }
    }

    @Test
    fun `a profile may be created unowned after the upgrade too`() = runTest {
        createSqlDriver().open(dbPath).use { connection ->
            applyV38Fixture(connection)
            Migration38To39().migrate(connection)

            connection.execSQL(
                "INSERT INTO profiles (id,name,emoji,color_idx,is_default,created_at,updated_at) " +
                    "VALUES ('p-anon','Anon','🏠',0,0,300,300)",
            )

            assertNull(
                readUserId(connection, "p-anon"),
                "a profile created without an owner must read as unowned, not as an empty owner",
            )
        }
    }
}
