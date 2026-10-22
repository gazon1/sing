package com.singularity.todo.core.database

import androidx.sqlite.execSQL
import com.singularity.todo.core.database.contract.createSqlDriver
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The fixture helper — a row that fits the schema it is written into.
 *
 * The helper exists because a migration test seeds a database in the shape of
 * an older version, and the columns that version demands change with every
 * schema change. These tests pin the three things it promises:
 *
 * 1. **A `NOT NULL` column the caller never mentioned is still filled** — with
 *    the zero of its type, so the insert cannot fail on it.
 * 2. **What the caller wrote is what is stored** — an override is not replaced
 *    by a default.
 * 3. **A column with a default is left to its default** — the helper does not
 *    write a value the schema already has one for.
 */
@Tag("slow")
class LegacyTaskFixtureTest {

    private lateinit var tempDir: File
    private lateinit var dbPath: String

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("legacy-task-fixture-").toFile()
        dbPath = File(tempDir, "fixture.db").absolutePath
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /** A schema with the shapes that matter: a plain column, a default, a NOT NULL. */
    private fun applyFixture() {
        createSqlDriver().open(dbPath).use { connection ->
            connection.execSQL(
                """
                CREATE TABLE tasks (
                    id TEXT NOT NULL,
                    title TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    someday INTEGER NOT NULL,
                    is_pinned INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    priority TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    server_version INTEGER NOT NULL,
                    sync_status TEXT NOT NULL,
                    outgoing_links TEXT NOT NULL DEFAULT '[]',
                    due_date TEXT,
                    series_id TEXT
                )
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `a task that names nothing is still inserted`() {
        applyFixture()

        createSqlDriver().open(dbPath).use { connection ->
            insertLegacyTask(connection, id = "t1")

            val row = connection.prepare("SELECT id, title, user_id, someday FROM tasks").use { statement ->
                assertEquals(true, statement.step(), "the row must be there")
                listOf(
                    statement.getText(0),
                    statement.getText(1),
                    statement.getText(2),
                    statement.getInt(3),
                )
            }
            assertEquals(listOf("t1", "Task t1", "u1", 0), row)
        }
    }

    @Test
    fun `the value the caller wrote is the value that is stored`() {
        applyFixture()

        createSqlDriver().open(dbPath).use { connection ->
            insertLegacyTask(
                connection,
                id = "t2",
                title = "Buy milk",
                columns = mapOf("due_date" to "2026-10-22", "series_id" to "series-1"),
            )

            val row = connection.prepare("SELECT title, due_date, series_id FROM tasks").use { statement ->
                assertEquals(true, statement.step(), "the row must be there")
                listOf(statement.getText(0), statement.getText(1), statement.getText(2))
            }
            assertEquals(listOf("Buy milk", "2026-10-22", "series-1"), row)
        }
    }

    @Test
    fun `a column with a default keeps the default`() {
        applyFixture()

        createSqlDriver().open(dbPath).use { connection ->
            insertLegacyTask(connection, id = "t3")

            val links = connection.prepare("SELECT outgoing_links FROM tasks").use { statement ->
                assertEquals(true, statement.step(), "the row must be there")
                statement.getText(0)
            }
            assertEquals(
                "[]",
                links,
                "the helper must not write over a default it knows nothing about",
            )
        }
    }

    @Test
    fun `a not null column the model knows nothing about gets the zero of its type`() {
        createSqlDriver().open(dbPath).use { connection ->
            connection.execSQL(
                "CREATE TABLE tasks (id TEXT NOT NULL, mystery INTEGER NOT NULL, note TEXT NOT NULL)",
            )

            insertLegacyTask(connection, id = "t4")

            val row = connection.prepare("SELECT mystery, note FROM tasks").use { statement ->
                assertEquals(true, statement.step(), "the row must be there")
                listOf(statement.getInt(0), statement.getText(1))
            }
            assertEquals(listOf(0, ""), row)
        }
    }
}
