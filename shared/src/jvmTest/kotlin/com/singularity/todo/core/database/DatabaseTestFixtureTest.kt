@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.database

import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The fixture's own contract: both modes, and the lifecycle it owns.
 *
 * A fresh database never runs a migration, so "the in-memory database applies the same
 * migration set" cannot be observed as a migration. It is true by construction — both
 * modes register migrations through the seam's one `applyAppMigrations`, which
 * `scripts/check-room-schema-integrity.py` already holds to the full manual set — and it
 * is observed here as "the in-memory database is not behind the file-backed one": same
 * schema version, same `sqlite_master` dump, identity table included.
 */
@Tag("slow")
@Tag("slow:data")
class DatabaseTestFixtureTest {
    @Test
    fun `file-backed mode keeps a real database file until the fixture closes`() {
        val fixture = DatabaseTestFixture.onDisk()
        val databasePath = fixture.path()
        val directory = fixture.directory()

        try {
            fixture.database()
            assertTrue(File(databasePath).isFile, "expected a database file at $databasePath")
            assertTrue(directory.isDirectory, "expected the fixture's directory at $directory")
        } finally {
            fixture.close()
        }

        assertFalse(File(databasePath).exists(), "the database file survived close()")
        assertFalse(directory.exists(), "the temp directory survived close()")
    }

    @Test
    fun `a file written through connect is the file Room opens afterwards`() =
        runTest {
            DatabaseTestFixture.onDisk().use { fixture ->
                fixture.connect().use { connection ->
                    connection.execSQL("CREATE TABLE seeded (id INTEGER PRIMARY KEY, value TEXT)")
                    connection.execSQL("INSERT INTO seeded VALUES (1, 'kept')")
                }

                assertEquals(
                    listOf("kept"),
                    fixture.database().textColumn("SELECT value FROM seeded"),
                    "a migration seeds the file before Room opens it; the same file must come back",
                )
            }
        }

    @Test
    fun `in-memory mode creates no database file and no WAL sidecar`() =
        runTest {
            DatabaseTestFixture.inMemory().use { fixture ->
                assertEquals(
                    listOf(""),
                    fixture.database().textColumn(
                        "SELECT file FROM pragma_database_list WHERE name = 'main'",
                    ),
                    "an in-memory database must not be backed by a file",
                )
                assertEquals(
                    listOf("memory"),
                    fixture.database().textColumn("SELECT journal_mode FROM pragma_journal_mode"),
                    "an in-memory database must not use the WAL journal that leaves a -wal sidecar",
                )
            }
        }

    @Test
    fun `in-memory mode is not behind the file-backed one - same version, same schema, usable DAOs`() =
        runTest {
            val fileBacked = DatabaseTestFixture.onDisk()
            val inMemory = DatabaseTestFixture.inMemory()

            try {
                assertEquals(
                    listOf(SCHEMA_VERSION.toLong()),
                    fileBacked.database().longColumn("PRAGMA user_version"),
                )
                assertEquals(
                    fileBacked.database().longColumn("PRAGMA user_version"),
                    inMemory.database().longColumn("PRAGMA user_version"),
                )
                assertEquals(
                    fileBacked.database().schemaSnapshot(),
                    inMemory.database().schemaSnapshot(),
                    "the in-memory database must carry the full current schema",
                )

                val notes = inMemory.database().noteDao()
                notes.upsert(note())
                assertEquals(1, notes.watchAll("u1").first().size)
            } finally {
                inMemory.close()
                fileBacked.close()
            }
        }

    // The whole jvmTest suite runs in one JVM, so two in-memory fixtures that shared a
    // database would let a test see another test's rows. Each fixture owns its driver.
    @Test
    fun `two in-memory fixtures do not share state`() =
        runTest {
            DatabaseTestFixture.inMemory().use { first ->
                DatabaseTestFixture.inMemory().use { second ->
                    first.database().noteDao().upsert(note())

                    assertEquals(
                        1,
                        first
                            .database()
                            .noteDao()
                            .watchAll("u1")
                            .first()
                            .size,
                    )
                    assertEquals(
                        0,
                        second
                            .database()
                            .noteDao()
                            .watchAll("u1")
                            .first()
                            .size,
                        "an in-memory database must not be shared with another fixture",
                    )
                }
            }
        }

    // Reopening has to replace the cache: otherwise `database()` keeps handing back the
    // first, already-closed instance — the footgun the restart tests hit.
    @Test
    fun `reopen replaces the cached database so database() never returns a closed one`() =
        runTest {
            DatabaseTestFixture.onDisk().use { fixture ->
                fixture.database().noteDao().upsert(note())

                val reopened = fixture.reopen()

                assertSame(reopened, fixture.database())
                assertEquals(
                    1,
                    reopened
                        .noteDao()
                        .watchAll("u1")
                        .first()
                        .size,
                )
            }
        }

    private fun note(): NoteEntity {
        val now = System.currentTimeMillis()
        return NoteEntity(
            id = "n1",
            userId = "u1",
            title = "Test",
            bodyMarkdown = "# Hi",
            bodyHtml = null,
            isFolder = false,
            parentNoteId = null,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            archivedAt = null,
        )
    }
}

private suspend fun AppDatabase.textColumn(sql: String): List<String> =
    useConnection(isReadOnly = true) { connection ->
        connection.usePrepared(sql) { statement ->
            buildList {
                while (statement.step()) {
                    add(statement.getText(0))
                }
            }
        }
    }

private suspend fun AppDatabase.longColumn(sql: String): List<Long> =
    useConnection(isReadOnly = true) { connection ->
        connection.usePrepared(sql) { statement ->
            buildList {
                while (statement.step()) {
                    add(statement.getLong(0))
                }
            }
        }
    }

/** Every schema object Room created, identity table included, in a stable order. */
private suspend fun AppDatabase.schemaSnapshot(): List<String> =
    textColumn(
        "SELECT type || '|' || name || '|' || COALESCE(sql, '') FROM sqlite_master " +
            "WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name",
    )
