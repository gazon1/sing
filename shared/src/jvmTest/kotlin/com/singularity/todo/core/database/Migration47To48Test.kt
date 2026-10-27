package com.singularity.todo.core.database

import com.singularity.todo.core.database.contract.createSqlDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("slow")
class Migration47To48Test {

    private lateinit var tempDir: File
    private lateinit var dbPath: String

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("migration47to48-").toFile()
        dbPath = File(tempDir, "fixture.db").absolutePath
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun openMigrated(): AppDatabase {
        createSqlDriver().open(dbPath).use { connection ->
            applyExportedSchema(47, connection)
            // Two live tags that are the same label apart from case/trim.
            connection.execSQL(
                """
                INSERT INTO tags (id, user_id, name, color, created_at, updated_at, deleted_at, server_version, sync_status, sort_order)
                VALUES ('t1', 'u1', 'Invoice', 1, 1000, 1000, NULL, 0, 'LOCAL_ONLY', 0)
                """,
            )
            connection.execSQL(
                """
                INSERT INTO tags (id, user_id, name, color, created_at, updated_at, deleted_at, server_version, sync_status, sort_order)
                VALUES ('t2', 'u1', '  invoice  ', 2, 1001, 1001, NULL, 0, 'LOCAL_ONLY', 0)
                """,
            )
            // A soft-deleted tag with the same label — must survive untouched.
            connection.execSQL(
                """
                INSERT INTO tags (id, user_id, name, color, created_at, updated_at, deleted_at, server_version, sync_status, sort_order)
                VALUES ('t3', 'u1', 'Invoice', 3, 1002, 1002, 9999, 0, 'LOCAL_ONLY', 0)
                """,
            )
            // task_tags pointing at both live duplicates.
            connection.execSQL("INSERT INTO task_tags (task_id, tag_id) VALUES ('task-a', 't1')")
            connection.execSQL("INSERT INTO task_tags (task_id, tag_id) VALUES ('task-b', 't2')")
        }
        return AppDatabaseFactory.build(createSqlDriver(), dbPath)
    }

    @Test
    fun `the normalized column exists after the upgrade`() = runTest {
        val db = openMigrated()
        try {
            val columns = db.useConnection(isReadOnly = true) { transactor ->
                transactor.usePrepared("PRAGMA table_info(tags)") { statement ->
                    buildList {
                        while (statement.step()) {
                            add(statement.getText(1))
                        }
                    }
                }
            }
            assertTrue("name_normalized" in columns, "the upgrade must add name_normalized: $columns")
        } finally {
            db.close()
        }
    }

    @Test
    fun `live duplicates merge into one tag`() = runTest {
        val db = openMigrated()
        try {
            val liveCount = db.useConnection(isReadOnly = true) { transactor ->
                transactor.usePrepared("SELECT COUNT(*) FROM tags WHERE user_id = 'u1' AND deleted_at IS NULL") { s ->
                    assertTrue(s.step())
                    s.getLong(0)
                }
            }
            assertEquals(1L, liveCount, "the two live duplicates must collapse to one")
            // the soft-deleted one survives
            val deletedCount = db.useConnection(isReadOnly = true) { transactor ->
                transactor.usePrepared(
                    "SELECT COUNT(*) FROM tags WHERE user_id = 'u1' AND deleted_at IS NOT NULL",
                ) { s ->
                    assertTrue(s.step())
                    s.getLong(0)
                }
            }
            assertEquals(1L, deletedCount, "the trashed tag must survive the merge")
        } finally {
            db.close()
        }
    }

    @Test
    fun `task_tags are re-pointed at the survivor`() = runTest {
        val db = openMigrated()
        try {
            val survivors = db.useConnection(isReadOnly = true) { transactor ->
                transactor.usePrepared("SELECT tag_id FROM task_tags ORDER BY task_id") { s ->
                    buildList {
                        while (s.step()) {
                            add(s.getText(0))
                        }
                    }
                }
            }
            assertTrue(survivors.all { it == "t1" }, "all task_tags must point at the survivor t1: $survivors")
            assertEquals(2, survivors.size, "both associations must survive")
        } finally {
            db.close()
        }
    }

    @Test
    fun `the partial unique index exists`() = runTest {
        val db = openMigrated()
        try {
            val indexes = db.useConnection(isReadOnly = true) { transactor ->
                transactor.usePrepared(
                    "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'tags'",
                ) { s ->
                    buildList {
                        while (s.step()) {
                            add(s.getText(0))
                        }
                    }
                }
            }
            assertTrue("index_tags_user_id_name_normalized" in indexes, "partial unique index must exist: $indexes")
        } finally {
            db.close()
        }
    }
}
