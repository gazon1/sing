package com.singularity.todo.core.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.singularity.todo.core.database.contract.ProbeOutcome
import com.singularity.todo.core.database.contract.deleteDatabaseFiles
import com.singularity.todo.core.database.contract.probe
import com.singularity.todo.core.database.contract.wipeIfNotRoomManaged
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import kotlin.io.path.readBytes
import kotlin.io.path.toPath
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The startup probe, and what it is allowed to delete.
 *
 * The guarantee under test is about **contents**, not about the file's presence. The old
 * probe deleted the file on any error, so a locked database, a corrupted file and an empty
 * file all ended the same way: the user's data, gone. A test that checked only "the file
 * is still there" would have passed against a probe that emptied the file first.
 */
@Tag("slow")
@Tag("slow:data")
class StaleDbCleanerTest {

    @TempDir
    lateinit var dir: File

    private fun dbPath(name: String = "singularity.db"): String = File(dir, name).path

    private fun writeValidDatabase(path: String, withMarker: Boolean) {
        val driver = BundledSQLiteDriver()
        driver.open(path).use { connection ->
            if (withMarker) {
                connection.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY)")
            } else {
                connection.execSQL("CREATE TABLE something_else (id INTEGER PRIMARY KEY)")
            }
            connection.execSQL(
                "INSERT INTO ${if (withMarker) "room_master_table" else "something_else"} (id) VALUES (1)",
            )
        }
    }

    private fun bytesOf(path: String): ByteArray = File(path).readBytes()

    @Test
    fun `a database that is not the application's is deleted`() {
        val path = dbPath()
        writeValidDatabase(path, withMarker = false)

        val deleted = wipeIfNotRoomManaged(path)

        assertTrue(deleted, "A file that is provably not ours is cleaned up")
        assertFalse(File(path).exists(), "And it is gone")
    }

    @Test
    fun `the application's own database is left alone`() {
        val path = dbPath()
        writeValidDatabase(path, withMarker = true)
        val before = bytesOf(path)

        val deleted = wipeIfNotRoomManaged(path)

        assertFalse(deleted, "The application's database is not deleted")
        assertContentEquals(before, bytesOf(path), "And not a byte of it changed")
    }

    @Test
    fun `an empty file is kept`() {
        val path = dbPath()
        File(path).writeBytes(ByteArray(0))

        val deleted = wipeIfNotRoomManaged(path)

        assertFalse(deleted, "An empty file is not proof of anything")
        assertTrue(File(path).exists(), "So it stays")
    }

    @Test
    fun `a file that is not a database is kept`() {
        val path = dbPath()
        val content = "this is a text file that happens to sit where a database would".toByteArray()
        File(path).writeBytes(content)

        val deleted = wipeIfNotRoomManaged(path)

        assertFalse(deleted, "A file that is not a database is not ours to delete")
        assertContentEquals(content, bytesOf(path), "And its content is untouched")

        // The record for a file that was read and is not a database says so, and says
        // something different from the record for a file that could not be read.
        val outcome = probe(path)
        assertIs<ProbeOutcome.Uncertain>(outcome, "The probe was uncertain")
        assertEquals("the file does not start with a database header", outcome.reason)
    }

    @Test
    fun `a corrupted file is kept byte for byte`() {
        val path = dbPath()
        // A real SQLite header, and then nonsense. The file looks like a database and is
        // not one any more — which is exactly the case that used to delete the user's data.
        val header = "SQLite format 3".toByteArray(Charsets.US_ASCII)
        val content = header + ByteArray(4096) { (it % 251).toByte() }
        File(path).writeBytes(content)

        val deleted = wipeIfNotRoomManaged(path)

        assertFalse(deleted, "A corrupted file might be the user's only copy")
        assertContentEquals(content, bytesOf(path), "And it survives byte for byte")
    }

    @Test
    fun `a truncated database is kept`() {
        val path = dbPath()
        writeValidDatabase(path, withMarker = false)
        // Cut the file in half: a valid header, a broken body.
        val whole = bytesOf(path)
        File(path).writeBytes(whole.copyOf(whole.size / 2))

        val deleted = wipeIfNotRoomManaged(path)

        assertFalse(deleted, "A truncated database is not proof that it is not ours")
        assertEquals(whole.size / 2L, File(path).length(), "And it is not touched")
    }

    @Test
    fun `a locked database is kept`() {
        val path = dbPath()
        writeValidDatabase(path, withMarker = false)
        val before = bytesOf(path)

        // The same file, unlocked, is a file this probe deletes: it is a valid database
        // and it does not carry the marker. The lock is the only difference, so the
        // difference has to be enough — which is why the control is at the end of this
        // test and not in another one.
        val driver = BundledSQLiteDriver()
        driver.open(path).use { connection ->
            connection.execSQL("BEGIN EXCLUSIVE")

            val deleted = wipeIfNotRoomManaged(path)

            assertFalse(deleted, "A database another process is holding is not proof of anything")
            assertContentEquals(before, bytesOf(path), "And not a byte of it changed")

            connection.execSQL("COMMIT")
        }

        val deletedOnceUnlocked = wipeIfNotRoomManaged(path)

        assertTrue(deletedOnceUnlocked, "Without the lock, the very same file is provably not ours")
        assertFalse(File(path).exists(), "And it goes")
    }

    @Test
    fun `a file the probe cannot read is kept`() {
        // A path that cannot be read as a file, and that could have been deleted: a
        // symlink to a directory. `inputStream()` on it fails ("Is a directory"), so the
        // probe cannot read it — and a symlink is removed by unlinking it, whatever it
        // points at, so the path was deletable all along. That matters: a path that could
        // not have been deleted would keep the probe's decision and the file system's
        // refusal indistinguishable.
        //
        // A permissions trick would be the obvious way to make a file unreadable, and it
        // is the wrong way — a process that runs as root can read a file whose mode says
        // otherwise, so the test would quietly stop testing anything on exactly the
        // machines where a mistake hides.
        val target = File(dbPath() + "-target")
        target.mkdirs()
        File(target, "the-users-data").writeBytes("the user's only copy".toByteArray())
        val path = dbPath()
        Files.createSymbolicLink(File(path).toPath(), target.toPath())

        val deleted = wipeIfNotRoomManaged(path)

        assertFalse(deleted, "A file the probe could not read is not proof of anything")
        assertTrue(File(path).exists(), "So it stays")
        assertContentEquals(
            "the user's only copy".toByteArray(),
            File(target, "the-users-data").readBytes(),
            "And so does what it points at",
        )

        // The record says what it saw, and what it saw was that it could not read the
        // file. This is the assertion that fails if the probe goes back to calling every
        // failure "not a database".
        val outcome = probe(path)
        assertIs<ProbeOutcome.Uncertain>(outcome, "The probe was uncertain")
        assertTrue(
            outcome.reason.contains("could not be read"),
            "And it said that it could not read the file: ${outcome.reason}",
        )

        assertTrue(File(path).delete(), "And the path was deletable all along")
    }

    @Test
    fun `the companion files survive when the database does`() {
        val path = dbPath()
        writeValidDatabase(path, withMarker = true)
        val wal = File("$path-wal")
        val shm = File("$path-shm")
        wal.writeBytes(byteArrayOf(1, 2, 3))
        shm.writeBytes(byteArrayOf(4, 5, 6))

        wipeIfNotRoomManaged(path)

        assertTrue(wal.exists(), "The write-ahead log is still there")
        assertTrue(shm.exists(), "And the shared-memory file")
    }

    @Test
    fun `the companion files survive when the database cannot be deleted`() {
        // A database that cannot be deleted: a directory that is not empty. A file
        // delete cannot remove it — whatever rights the process has, and whoever is
        // running the test — so the rule is exercised as a fact about the rule and not
        // as a fact about the machine. The rule is that the companions go only when
        // the database has gone: a write-ahead log removed while its database
        // survives holds the transactions that lived only in the log.
        val path = dbPath()
        File(path).mkdirs()
        File(path, "not-empty").writeBytes(byteArrayOf(1))
        val wal = File("$path-wal")
        val shm = File("$path-shm")
        wal.writeBytes(byteArrayOf(1, 2, 3))
        shm.writeBytes(byteArrayOf(4, 5, 6))

        val deleted = deleteDatabaseFiles(File(path))

        assertFalse(deleted, "A database that could not be deleted was not deleted")
        assertTrue(File(path).exists(), "And it is still there")
        assertTrue(wal.exists(), "So its write-ahead log is still there")
        assertTrue(shm.exists(), "And so is its shared-memory file")
    }

    @Test
    fun `a missing file is not an error`() {
        assertFalse(wipeIfNotRoomManaged(dbPath("never-existed.db")), "There was nothing to do")
    }
}
