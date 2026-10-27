package com.singularity.todo.core.backup

import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.serialization.StableJson
import kotlinx.serialization.Serializable

/**
 * Where in the backup's own history an export stands.
 *
 * ## Why the backup has a cursor of its own
 *
 * The sync engine keeps one too — `sync_state.lastLsn`, the position in the
 * server's event feed — and it looks free to read it. It is not: the sync state
 * moves when the profile changes and when it is cleared, and neither of those has
 * anything to do with the backup's position. A backup that follows the sync
 * engine's cursor writes a delta that claims to follow a base it does not follow,
 * and the failure that produces is the worst kind: a restore that succeeds and is
 * wrong.
 *
 * So the backup keeps its own, in its own file, and nobody else's bookkeeping
 * moves it.
 */
@JvmInline
value class BackupCursor(val epochMillis: Long)

/**
 * One change in the chain: the delta that follows the base, and the archive it
 * belongs to.
 *
 * The archive is named because a chain is not a list of anonymous deltas: the
 * delta belongs to the snapshot that was written beside it, and a push has to know
 * which deltas follow which archive.
 */
@Serializable
data class BackupChainEntry(
    /** The snapshot this delta was written beside. */
    val archivePath: String,
    /** The file the delta lives in, next to the snapshot. */
    val deltaPath: String,
    /** How many bytes the delta takes — the chain's size is the sum of these. */
    val byteSize: Long,
)

/**
 * What the backup knows about the last export: where it stands, what its chain
 * looks like, and what its rows held.
 *
 * The rows are the fingerprints, not the rows: the index has to answer "is this
 * row the row the last export wrote", and it answers it with a fingerprint per
 * row (see [BackupRows]).
 */
@Serializable
data class BackupCursorState(
    /** When the last export stood. */
    val cursor: Long = 0L,
    /** The snapshot the current chain starts from. */
    val baseArchivePath: String? = null,
    /** The changes that follow the base, in order. */
    val entries: List<BackupChainEntry> = emptyList(),
    /** What the last export's rows looked like. */
    val rows: Map<BackupEntityKind, Map<String, Long>> = emptyMap(),
)

/**
 * Where the backup keeps its own bookkeeping: one file per account, on this
 * device.
 *
 * ## What is in it
 *
 * The cursor, the chain, and the fingerprints of the last export's rows — the
 * three things an export needs to write the next change and a push needs to seal
 * the chain.
 *
 * ## Where it lives, and where it does not
 *
 * **On this device, and nowhere else.** The file is written next to the archives
 * and is never uploaded: the push names the archive it uploads and the changes
 * that follow it, and the bookkeeping is not among them. The name does not end in
 * `.zip` either, so the file is never listed as one of the person's backups — it
 * is the backup's own bookkeeping, not a backup.
 *
 * ## Why a file and not the database
 *
 * The state belongs to the backup and to nothing else, and it is read once per
 * export and written once per export. A table would make it a row in somebody
 * else's schema, with somebody else's migrations, for a fact that only the backup
 * feature reads.
 *
 * ## What a damaged file costs
 *
 * Nothing but a chain: a file that cannot be read is treated as no state at all,
 * and the next export writes a fresh base. The alternative — refusing to export
 * because the bookkeeping is damaged — would make a corrupt file the reason a
 * person cannot back up their account.
 */
class BackupCursorStore(private val fs: FileSystem, private val dir: String) {
    private val json = StableJson

    /** What the account's last export left behind. */
    suspend fun load(userId: UserId): BackupCursorState {
        val path = pathFor(userId)
        if (!fs.exists(path)) return BackupCursorState()
        val bytes = runCatchingCancellable { fs.readBytes(path) }.getOrNull() ?: return BackupCursorState()
        return runCatchingCancellable {
            json.decodeFromString(BackupCursorState.serializer(), bytes.decodeToString())
        }.getOrElse { BackupCursorState() }
    }

    /**
     * Records what the account's last export left behind.
     *
     * The write is **atomic**: the state goes to a temporary file, and that file
     * is moved over the real one. A process that dies between the two leaves the
     * old state intact rather than half of the new one — and half a state is a
     * state that cannot be read, which costs the chain and makes the next export
     * write a fresh base.
     */
    suspend fun save(userId: UserId, state: BackupCursorState) {
        fs.ensureDir(dir)
        val path = pathFor(userId)
        val temporary = "$path$TEMPORARY_SUFFIX"
        try {
            fs.writeBytes(
                temporary,
                json.encodeToString(BackupCursorState.serializer(), state).encodeToByteArray(),
            )
            fs.move(temporary, path)
        } finally {
            // A temporary file that did not become the cursor is litter, and
            // litter in the backup's own directory is a file the person may see.
            if (fs.exists(temporary)) {
                runCatchingCancellable { fs.delete(temporary) }
            }
        }
    }

    /** The file the account's bookkeeping lives in. */
    fun pathFor(userId: UserId): String = "$dir/$FILE_PREFIX${sanitize(userId.value)}$FILE_SUFFIX"

    /** The name of the file, without the directory. */
    private fun sanitize(userId: String): String =
        userId.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")

    private companion object {
        /** The prefix of the bookkeeping file's name. */
        const val FILE_PREFIX = "backup-cursor-"

        /** The suffix of the bookkeeping file's name — and deliberately not `.zip`. */
        const val FILE_SUFFIX = ".json"

        /** The suffix of the file the state is written to before it is moved. */
        const val TEMPORARY_SUFFIX = ".tmp"
    }
}
