@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.test.helpers

import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.NoteEntity
import com.singularity.todo.core.database.SCHEMA_VERSION
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        val fixture = DatabaseTestFixture.fileBacked()
        val databasePath = assertNotNull(fixture.databasePath)
        val directory = assertNotNull(fixture.directory)

        try {
            assertTrue(File(databasePath).isFile, "expected a database file at $databasePath")
            assertTrue(directory.isDirectory, "expected the fixture's directory at $directory")
        } finally {
            fixture.close()
        }

        assertFalse(File(databasePath).exists(), "the database file survived close()")
        assertFalse(directory.exists(), "the temp directory survived close()")
    }

    @Test
    fun `file-backed mode runs the prepare hook before Room opens the file`() {
        var preparedPath: String? = null
        var existedWhenPrepared = true

        val fixture =
            DatabaseTestFixture.fileBacked { path ->
                preparedPath = path
                existedWhenPrepared = File(path).exists()
            }

        try {
            assertEquals(fixture.databasePath, preparedPath)
            assertFalse(existedWhenPrepared, "the hook ran after Room created the file")
            assertTrue(File(assertNotNull(preparedPath)).isFile)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `in-memory mode creates no database file and no WAL sidecar`() =
        runTest {
            DatabaseTestFixture.inMemory().use { fixture ->
                assertNull(fixture.databasePath)
                assertNull(fixture.directory)

                assertEquals(
                    listOf(""),
                    fixture.database.textColumn(
                        "SELECT file FROM pragma_database_list WHERE name = 'main'",
                    ),
                    "an in-memory database must not be backed by a file",
                )
                assertEquals(
                    listOf("memory"),
                    fixture.database.textColumn("SELECT journal_mode FROM pragma_journal_mode"),
                    "an in-memory database must not use the WAL journal that leaves a -wal sidecar",
                )
            }
        }

    @Test
    fun `in-memory mode is not behind the file-backed one - same version, same schema, usable DAOs`() =
        runTest {
            val fileBacked = DatabaseTestFixture.fileBacked()
            val inMemory = DatabaseTestFixture.inMemory()

            try {
                assertEquals(
                    listOf(SCHEMA_VERSION.toLong()),
                    fileBacked.database.longColumn("PRAGMA user_version"),
                )
                assertEquals(
                    fileBacked.database.longColumn("PRAGMA user_version"),
                    inMemory.database.longColumn("PRAGMA user_version"),
                )
                assertEquals(
                    fileBacked.database.schemaSnapshot(),
                    inMemory.database.schemaSnapshot(),
                    "the in-memory database must carry the full current schema",
                )

                val notes = inMemory.database.noteDao()
                notes.upsert(note())
                assertEquals(1, notes.watchAll("u1").first().size)
            } finally {
                inMemory.close()
                fileBacked.close()
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
