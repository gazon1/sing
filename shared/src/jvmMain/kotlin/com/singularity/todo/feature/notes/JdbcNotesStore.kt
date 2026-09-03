package com.singularity.todo.feature.notes

import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.tasks.UserId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.sql.Connection
import java.sql.DriverManager

/**
 * Production [NotesStore] for JVM desktop using raw JDBC.
 *
 * No Room annotation processing required on JVM — all queries are raw SQL.
 * Uses [Clock] for timestamps and [PlatformContext]-derived path for the DB file.
 *
 * Key design decisions:
 * - [watchAll] and [watch] use a polling [callbackFlow] (500 ms interval).
 *   This is simpler than DB triggers and sufficient for desktop usage patterns.
 * - All mutating operations set updated_at to the current clock time.
 * - The notes table is created automatically on first connection.
 */
class JdbcNotesStore : NotesStore {

    private val dbPath: String by lazy {
        val base = java.io.File(System.getProperty("user.home"), ".singularity-todo").also { it.mkdirs() }
        java.io.File(base, "singularity-todo.db").absolutePath
    }

    private fun connect(): Connection {
        val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.createStatement().use { s ->
            s.execute("PRAGMA journal_mode = WAL")
            s.execute("PRAGMA cache_size = -2000")
            s.execute("PRAGMA temp_store = MEMORY")
            s.execute("PRAGMA synchronous = NORMAL")
        }
        return conn
    }

    init {
        connect().use { conn ->
            conn.createStatement().use { s ->
                s.execute(
                    """
                    CREATE TABLE IF NOT EXISTS notes (
                        id TEXT PRIMARY KEY,
                        user_id TEXT NOT NULL,
                        title TEXT NOT NULL DEFAULT '',
                        body_markdown TEXT,
                        body_html TEXT,
                        is_folder INTEGER NOT NULL DEFAULT 0,
                        parent_note_id TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        deleted_at INTEGER,
                        archived_at INTEGER
                    )
                    """.trimIndent()
                )
            }
        }
    }

    // ─── Row mapper ────────────────────────────────────────────────────────────

    private fun rsToNote(rs: java.sql.ResultSet): Note {
        fun long0(col: String): Long? = rs.getLong(col).takeIf { !rs.wasNull() }
        fun inst(col: String): kotlin.time.Instant? = long0(col)?.let { kotlin.time.Instant.fromEpochMilliseconds(it) }

        return Note(
            id = NoteId.fromString(rs.getString("id")),
            userId = UserId.fromString(rs.getString("user_id")),
            title = rs.getString("title") ?: "",
            bodyMarkdown = rs.getString("body_markdown"),
            bodyHtml = rs.getString("body_html"),
            isFolder = rs.getInt("is_folder") == 1,
            parentNoteId = rs.getString("parent_note_id")?.let { NoteId.fromString(it) },
            createdAt = kotlin.time.Instant.fromEpochMilliseconds(rs.getLong("created_at")),
            updatedAt = kotlin.time.Instant.fromEpochMilliseconds(rs.getLong("updated_at")),
            deletedAt = inst("deleted_at"),
            archivedAt = inst("archived_at")
        )
    }

    // ─── NotesStore implementation ─────────────────────────────────────────────

    override fun watchAll(userId: UserId): Flow<List<Note>> = callbackFlow {
        var lastHash = -1
        while (true) {
            connect().use { conn ->
                conn.prepareStatement(
                    "SELECT * FROM notes WHERE user_id = ? AND deleted_at IS NULL ORDER BY updated_at DESC"
                ).use { ps ->
                    ps.setString(1, userId.value)
                    ps.executeQuery().use { rs ->
                        val notes = mutableListOf<Note>()
                        while (rs.next()) notes.add(rsToNote(rs))
                        // Cheap structural check — List content equality is deep enough for this use case
                        if (notes.hashCode() != lastHash) {
                            lastHash = notes.hashCode()
                            send(notes.toList())
                        }
                    }
                }
            }
            delay(500L)
        }
    }

    override fun watch(id: String): Flow<Note?> = callbackFlow {
        var lastHash = -1
        while (true) {
            connect().use { conn ->
                conn.prepareStatement("SELECT * FROM notes WHERE id = ?").use { ps ->
                    ps.setString(1, id)
                    ps.executeQuery().use { rs ->
                        val note = if (rs.next()) rsToNote(rs) else null
                        if ((note?.hashCode() ?: 0) != lastHash) {
                            lastHash = note?.hashCode() ?: 0
                            send(note)
                        }
                    }
                }
            }
            delay(500L)
        }
    }

    override suspend fun create(userId: UserId, id: NoteId, title: String, bodyMarkdown: String): String {
        val now = Clock.now()
        connect().use { conn ->
            conn.prepareStatement(
                """INSERT INTO notes (id, user_id, title, body_markdown, is_folder, parent_note_id,
                   created_at, updated_at, deleted_at, archived_at)
                   VALUES (?, ?, ?, ?, 0, NULL, ?, ?, NULL, NULL)"""
            ).use { ps ->
                ps.setString(1, id.value)
                ps.setString(2, userId.value)
                ps.setString(3, title)
                ps.setString(4, bodyMarkdown)
                ps.setLong(5, now.toEpochMilliseconds())
                ps.setLong(6, now.toEpochMilliseconds())
                ps.executeUpdate()
            }
        }
        return id.value
    }

    override suspend fun update(id: String, title: String, bodyMarkdown: String) {
        val now = Clock.now()
        connect().use { conn ->
            conn.prepareStatement(
                "UPDATE notes SET title = ?, body_markdown = ?, updated_at = ? WHERE id = ?"
            ).use { ps ->
                ps.setString(1, title)
                ps.setString(2, bodyMarkdown)
                ps.setLong(3, now.toEpochMilliseconds())
                ps.setString(4, id)
                ps.executeUpdate()
            }
        }
    }

    override suspend fun softDelete(id: String) {
        val ts = Clock.now().toEpochMilliseconds()
        connect().use { conn ->
            conn.prepareStatement(
                "UPDATE notes SET deleted_at = ?, updated_at = ? WHERE id = ?"
            ).use { ps ->
                ps.setLong(1, ts)
                ps.setLong(2, ts)
                ps.setString(3, id)
                ps.executeUpdate()
            }
        }
    }
}
