package com.singularity.todo.core.database.contract

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import co.touchlab.kermit.Logger
import java.io.File

private val log = Logger.withTag("StaleDbCleaner")

/**
 * What the probe found.
 *
 * Three outcomes, not two. The old probe had "the table is there" and "the table is not
 * there", and it treated every error as the second one — which meant a locked file, a
 * corrupted file, an empty file and a database busy with another process all ended in the
 * same place: the user's database deleted.
 *
 * Internal rather than private, for the same reason as [deleteDatabaseFiles]: the reason the
 * probe leaves is the record the startup makes, and a record no test can read is a record
 * no change can be held to.
 */
internal sealed interface ProbeOutcome {

    /** The file carries the application's bookkeeping marker. It is the application's. */
    data object RoomManaged : ProbeOutcome

    /** The file is a valid database and provably does not carry the marker. */
    data object NotOurs : ProbeOutcome

    /**
     * The probe could not tell.
     *
     * [reason] says what it saw. This is the outcome that used to be missing, and its
     * arrival is the whole fix: a doubt is not evidence.
     */
    data class Uncertain(val reason: String) : ProbeOutcome
}

/**
 * The header every SQLite database file starts with.
 *
 * Checked before the file is opened, so that a file that is not a database is recognised as
 * such without being handed to the driver at all.
 */
private val SQLITE_HEADER = "SQLite format 3".toByteArray(Charsets.US_ASCII)

/**
 * What the first bytes of the file say.
 *
 * Three answers, not two: "it is a database", "it is not a database", and "I could not
 * read it". The third one matters because the record the startup leaves says what it
 * saw — and "the file does not start with a database header" is not what it saw when the
 * file could not be opened at all.
 */
private sealed interface HeaderOutcome {

    /** The file starts with the database header. */
    data object Database : HeaderOutcome

    /** The file was read, and it does not start with the header. */
    data object NotADatabase : HeaderOutcome

    /** The file could not be read. [reason] says what stopped the read. */
    data class Unreadable(val reason: String) : HeaderOutcome
}

/**
 * Probes [dbPath] to determine whether it is a Room-managed database.
 *
 * ## The rule
 *
 * The file is deleted **only** when it is provably not the application's: it is readable, it
 * is a valid database, and it does not carry the marker Room writes on first creation.
 *
 * Every other outcome — a locked file, an unreadable file, a corrupted file, an empty file,
 * a database another process is holding — leaves the file and its contents exactly as they
 * were. A corrupted file may be the user's only copy of their data, and a locked file *is*
 * their data, busy. The cost of leaving a stale file behind is one confusing startup; the
 * cost of deleting the user's data is the user's data.
 *
 * ## What changed, and why it matters
 *
 * This used to delete the file on *any* failure, and to delete it together with its `-wal`
 * and `-shm` companions. The consequence was that a transient error — a busy database, a
 * permissions problem, a moment of corruption — destroyed the data the application exists to
 * hold. The old KDoc claimed the deletion was safe because "the caller can safely delete the
 * file"; the caller cannot, because the caller is the one that cannot tell.
 *
 * @return `true` if the file was deleted; `false` if it was left alone.
 */
fun wipeIfNotRoomManaged(dbPath: String): Boolean {
    val file = File(dbPath)
    if (!file.exists()) return false

    return when (val outcome = probe(dbPath)) {
        is ProbeOutcome.RoomManaged -> false

        is ProbeOutcome.NotOurs -> deleteDatabaseFiles(file)

        is ProbeOutcome.Uncertain -> {
            log.w {
                "Left the database at $dbPath alone: ${outcome.reason}. " +
                    "The file is not provably ours, and deleting it could destroy user data."
            }
            false
        }
    }
}

/**
 * What the file is, as far as the probe can tell.
 *
 * The marker is looked up in `sqlite_master` rather than by selecting from the marker table
 * directly. `sqlite_master` exists in every valid SQLite database, so a query against it
 * either answers — and the answer is a definite yes or no — or fails, and a failure means
 * the file could not be read as a database at all. There is no third possibility, and no
 * dependence on how a particular driver words its error messages.
 *
 * Internal rather than private, for the same reason as [deleteDatabaseFiles]: the reason
 * this leaves is the record the startup makes, and a record no test can read is a record
 * no change can be held to.
 */
@Suppress("TooGenericExceptionCaught") // the probe is fail-safe: every way of failing means "uncertain"
internal fun probe(dbPath: String): ProbeOutcome {
    when (val header = readHeader(dbPath)) {
        HeaderOutcome.Database -> Unit

        HeaderOutcome.NotADatabase ->
            return ProbeOutcome.Uncertain("the file does not start with a database header")

        is HeaderOutcome.Unreadable ->
            return ProbeOutcome.Uncertain("the file could not be read: ${header.reason}")
    }

    return try {
        // Read-only: a probe must not be able to change the file it is inspecting. The
        // read-only open is on the bundled driver rather than on the interface, because
        // the interface only knows how to open a database for writing.
        val driver = BundledSQLiteDriver()
        driver.open(dbPath, SQLITE_OPEN_READONLY).use { connection ->
            connection.prepare(MARKER_QUERY).use { statement ->
                if (!statement.step()) {
                    return ProbeOutcome.Uncertain("the database did not answer the marker query")
                }
                if (statement.getLong(0) > 0L) {
                    ProbeOutcome.RoomManaged
                } else {
                    ProbeOutcome.NotOurs
                }
            }
        }
    } catch (e: Exception) {
        ProbeOutcome.Uncertain("the database could not be read: ${e.message ?: e::class.simpleName}")
    }
}

/** Looks the marker up in the schema rather than selecting from it. */
private const val MARKER_QUERY =
    "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'room_master_table'"

/**
 * Reads the first bytes of the file and says what they are.
 *
 * A cheap, exact check: a file that does not start with the header is not a database, and
 * saying so is more useful than opening it and interpreting the driver's error. A file
 * that could not be read is a third thing again, and it says so — the difference between
 * "this is not a database" and "I could not look" is the difference between a leftover
 * from an older layout and a user's data behind a permission the process does not have.
 */
@Suppress("TooGenericExceptionCaught") // the probe is fail-safe: every way of not reading it means "not ours"
private fun readHeader(dbPath: String): HeaderOutcome = try {
    val header = ByteArray(SQLITE_HEADER.size)
    val read = File(dbPath).inputStream().use { stream -> stream.read(header) }
    if (read == SQLITE_HEADER.size && header.contentEquals(SQLITE_HEADER)) {
        HeaderOutcome.Database
    } else {
        HeaderOutcome.NotADatabase
    }
} catch (e: Exception) {
    log.d(e) { "The candidate file could not be read, so the probe cannot say what it is: $dbPath" }
    HeaderOutcome.Unreadable(e.message ?: e::class.simpleName.orEmpty())
}

/**
 * Removes the database and its companions.
 *
 * Only called for a file that is provably not the application's. The companions go with it
 * because a `-wal` or `-shm` file without its database is a file nobody can open, and
 * leaving them behind would make the next start look like a corrupt database.
 *
 * But the companions go **only if the database itself is gone**. A `-wal` holds the
 * transactions that were committed but not yet checkpointed, so removing it while the
 * database survives loses committed data — and the delete can fail while the file
 * remains (a file held open by a running application, a read-only file, a file on a
 * filesystem that says no). The rule the specification states is that the companions
 * must not be removed unless the database was removed, and this is where that rule is
 * kept.
 *
 * @return `true` if the database was deleted.
 *
 * Internal rather than private, for the same reason as the exported tool surfaces: the
 * rule about the companions is a contract, and a contract no test pins is a contract a
 * rename breaks. The public entry point cannot reach this branch with a database that
 * cannot be deleted — the probe has already decided the file is a database — so the
 * test drives this function directly.
 */
internal fun deleteDatabaseFiles(file: File): Boolean {
    if (!file.delete()) return false
    File("${file.path}-wal").delete()
    File("${file.path}-shm").delete()
    return true
}
