@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * In-memory sync server shared by several [SyncApiClient] instances.
 *
 * Each instance wraps the same [SharedSyncServer] but is authenticated as a
 * different account, so a push on one device reaches the other devices of that
 * account — and nobody else's.
 *
 * ## What it mirrors
 *
 * The server the app really talks to is the Postgres schema captured in
 * `supabase/migrations/2026-10-07-sync_schema.sql`, and the part of it a test
 * can observe is the merge. This fixture reproduces that merge:
 *
 * - **Per-field last-writer-wins.** A field is written only when the patch's
 *   clock beats the clock stored for *that field* — [Hlc] orders by physical
 *   time, then counter, then node id. Two devices that edit different fields
 *   both keep their edit; two devices that edit the same field keep the later
 *   one, and the loser is told so (`lost`) rather than being told it landed.
 * - **A version in every answer.** The row's version moves only when a patch
 *   changed something, and the answer carries the row's version as it stands
 *   afterwards. It is the row's number, not the patch's, so the same field is
 *   filled whether the patch won, lost, or was refused: the losing device is
 *   still told where the row stands, which is what its next diff needs.
 * - **A refusal.** A patch that names a field the server does not accept is
 *   refused whole with `field_not_writable` and writes nothing. Whole, not in
 *   part: applying the operations the allowlist accepts and dropping the rest
 *   would answer `ok` for a patch the server half threw away, and the device
 *   would settle the outbox row that carried the dropped operation.
 * - **Idempotency.** A patch id already applied for that account is answered
 *   from the cache and is not applied twice.
 * - **Scoping.** The account is the owner: another account's rows and events
 *   are invisible. A profile is a dimension inside an account — the same entity
 *   id in two profiles is two documents.
 * - **The kind of each operation.** A field's clock decides whether the
 *   operation is applied at all; once it is, the kind decides what the document
 *   does. `set` states a value, `unset` clears the field, `append` extends a
 *   list and `remove` shrinks it.
 * - **A whole row on the wire.** A pull event carries the whole row, not the
 *   operations that changed it, so the receiving device applies the merged
 *   state rather than replaying somebody else's diff. "The whole row" means
 *   every field the type has — null for the ones no patch ever wrote — not only
 *   the fields some patch happened to mention.
 * - **A deletion that is part of the row.** A delete patch writes the server's
 *   own tombstone, the `deletedAt` document field, with the patch's clock. The
 *   deletion therefore takes part in the per-field comparison like any other
 *   write: a later edit to another field changes that field but cannot bring
 *   the row back, and a deletion whose clock is older than the stored one loses.
 * - **A deletion that is delivered even for a row the server never held.** A
 *   delete creates the row it names when the server has none, writes the
 *   tombstone into it and puts it on the feed. The deleting device holds a copy
 *   of that row, which is proof the row exists on some device — answering `ok`
 *   with no event would leave every other device's copy alive and settle the
 *   sender's outbox row, so nothing would ever retry it.
 *
 * ## What it deliberately does not do
 *
 * - **It does not check the patch's base version.** The server does not either:
 *   the merge is per field, and the base version is the client's own bookkeeping.
 * - **It does not reproduce the server's hardcoded version.** The SQL answers
 *   `newVersion` with the number of operations that were applied, which the
 *   schema file itself records as a defect (#207). This fixture answers with
 *   the row's version, because the client records that number as the row's
 *   version and would otherwise be told a fiction. The number is the row's in
 *   every answer, refused and lost included, so a device is never told that a
 *   patch which changed nothing produced a version of its own.
 * - **It does not reproduce the server's silent half-apply.** The SQL filters
 *   out the operations the allowlist refuses and answers `ok` for the rest, so
 *   the dropped operation is settled along with the outbox row that carried it.
 *   This fixture refuses the patch instead, and the difference is the one a
 *   carrier test can see: a device that names a field the server will not take
 *   is told so, rather than being told that its patch landed.
 *
 * ## Why it is not a replacement for Supabase
 *
 * It is a fixture. It has no rows table, no field allowlist table read from the
 * seed, no RLS, no `owner_id` derived from a session — the account is the
 * transport's identity and nothing else. It is enough for a test to assert that
 * two devices really merge, and it is not enough to be the server.
 */
class SharedSyncServer {
    private val mutex = Mutex()

    /** The rows the server holds, keyed by owner, profile, type and entity. */
    private val rows = mutableMapOf<RowKey, ServerRow>()

    /** The event feed, one list per owner. The lsn counter is global. */
    private val events = mutableMapOf<String, MutableList<SyncEvent>>()

    /** The answers already given, keyed by owner and patch id. */
    private val answers = mutableMapOf<Pair<String, String>, PatchResult>()

    private var lsn: Long = 0L

    /** One client transport authenticated as [account]. */
    fun client(account: String): SyncApiClient = SharedSyncClient(this, account)

    internal suspend fun push(
        request: BatchPushRequest,
        account: String,
    ): BatchPushResponse =
        mutex.withLock {
            BatchPushResponse(
                request.patches.map { patch -> applyPatch(request.profileId, patch, account) },
            )
        }

    internal suspend fun pull(
        sinceLsn: Long,
        limit: Int,
        account: String,
    ): List<SyncEvent> =
        mutex.withLock {
            events[account]
                .orEmpty()
                .filter { it.serverLsn > sinceLsn }
                .sortedBy { it.serverLsn }
                .take(limit)
        }

    internal suspend fun testConnection(): Result<Unit> = Result.success(Unit)

    /**
     * Answers one patch, from the cache when the same patch id was already
     * applied for this account.
     *
     * The cache is the server's idempotency table: a client that retries after
     * a timeout gets the original answer back instead of applying twice. A
     * refused patch is not remembered — the server refuses it again, and the
     * answer costs nothing to recompute.
     */
    private fun applyPatch(
        profileId: String,
        patch: DeltaPatch,
        account: String,
    ): PatchResult {
        val cached = answers[account to patch.patchId]
        if (cached != null) return cached.copy(cached = true)
        val answer =
            if (patch.isDelete) {
                deleteRow(profileId, patch, account)
            } else {
                mergePatch(profileId, patch, account)
            }
        if (answer.ok) answers[account to patch.patchId] = answer
        return answer
    }

    /**
     * Merges one patch into the row it names, field by field.
     *
     * The merge is the server's own: a field is written when the patch's clock
     * beats the clock stored for that field, the operation's kind decides what
     * the write does to the document, and the clocks and the values are read and
     * written from the same row — there is no window between them.
     *
     * The allowlist is applied to the patch as a whole, before any field is
     * written, so a patch the server cannot take in full writes nothing. A patch
     * that names no clock is refused for the same reason: it is the legacy shape,
     * whose absent document the server expands into zero operations.
     *
     * A patch that carries a clock and no operations is *not* a refusal. Nothing
     * changed, so there is nothing to refuse, and the answer is the one a patch
     * whose every field lost gets: `ok`, `lost`, and the row's version. Refusing
     * it would land in [SyncProtocol.TERMINAL_ERRORS] and dead-letter a patch
     * that says only "nothing happened".
     */
    private fun mergePatch(
        profileId: String,
        patch: DeltaPatch,
        account: String,
    ): PatchResult {
        val key = RowKey(account, profileId, patch.entityType, patch.entityId)
        val clock = patch.hlc
        val allowed = SyncContract.serverFieldsFor(patch.entityType)
        val outsideTheAllowlist = patch.ops.any { it.field !in allowed }
        if (clock == null || outsideTheAllowlist) {
            return PatchResult(
                patchId = patch.patchId,
                ok = false,
                error = SyncProtocol.FIELD_NOT_WRITABLE,
                newVersion = rows[key]?.serverVersion,
            )
        }
        val existing = rows[key]
        val row = existing ?: ServerRow(patch.entityType)
        var applied = 0
        patch.ops.forEach { op ->
            if (row.write(op, clock)) applied += 1
        }
        if (applied == 0) {
            // Nothing this patch carried beat what the row already held — or it
            // carried nothing at all. The server took the patch, understood it,
            // and kept what it had — which is what `lost` says, and it is why the
            // answer is still ok. A row that does not exist has no version to
            // report, so a no-op against one answers null rather than a zero.
            return PatchResult(
                patchId = patch.patchId,
                ok = true,
                lost = true,
                newVersion = existing?.serverVersion,
            )
        }
        row.serverVersion += 1
        rows[key] = row
        recordEvent(account, profileId, patch, row.snapshot(), SyncEventType.UPDATED, clock)
        return PatchResult(
            patchId = patch.patchId,
            ok = true,
            newVersion = row.serverVersion,
        )
    }

    /**
     * Writes the deletion into the row and puts it on the feed.
     *
     * A deletion is not a flag on the feed: it is the server's own tombstone
     * field, `deletedAt`, written into the document with the patch's clock. That
     * is what makes it survive a later edit — the edit changes its own field, the
     * document still carries `deletedAt`, and the row stays deleted. It also
     * makes a deletion subject to the same per-field comparison as any other
     * write: a deletion whose clock is older than the stored one loses.
     *
     * The tombstone is written whichever type it is. A delete is not a patch
     * operation, so the field allowlist does not govern it; the tombstone is the
     * server's own record that the row is gone.
     *
     * The row is created when the server has none. The deleting device holds a
     * copy of it, so other devices of the account hold one too, and a deletion
     * that produced no event would leave those copies alive while settling the
     * sender's outbox row — nothing would ever retry it.
     *
     * The clock is the patch's own. A delete that carries none is ordered by its
     * timestamp under the server's legacy node, and the tombstone records that
     * same timestamp — never a zero, because `deletedAt: 0` is a value no real
     * push produces and later snapshots would carry it as if it were one. A
     * delete that carries neither a clock nor a timestamp cannot be ordered at
     * all and is refused rather than given an invented place in the merge.
     */
    private fun deleteRow(
        profileId: String,
        patch: DeltaPatch,
        account: String,
    ): PatchResult {
        val key = RowKey(account, profileId, patch.entityType, patch.entityId)
        val clock = patch.hlc ?: patch.timestampMs?.let { Hlc.of(it, 0, LEGACY_NODE) }
        if (clock == null) {
            return PatchResult(
                patchId = patch.patchId,
                ok = false,
                error = SyncProtocol.FIELD_NOT_WRITABLE,
                newVersion = rows[key]?.serverVersion,
            )
        }
        val existing = rows[key]
        val row = existing ?: ServerRow(patch.entityType)
        val tombstone =
            FieldChange(
                field = DELETED_AT,
                op = FieldOp.SET,
                value = JsonPrimitive(patch.timestampMs ?: clock.physical),
            )
        if (!row.write(tombstone, clock)) {
            // The stored deletion is newer than this one; nothing changed.
            return PatchResult(
                patchId = patch.patchId,
                ok = true,
                lost = true,
                newVersion = existing?.serverVersion,
            )
        }
        row.serverVersion += 1
        rows[key] = row
        recordEvent(account, profileId, patch, row.snapshot(), SyncEventType.DELETED, clock)
        return PatchResult(
            patchId = patch.patchId,
            ok = true,
            newVersion = row.serverVersion,
        )
    }

    /**
     * Appends one event to the owner's feed.
     *
     * The event carries the merged document, not the operations: the receiving
     * device applies the row as it stands on the server, which is what makes
     * two devices converge rather than replay each other's diffs.
     *
     * The type is `updated` for a merge, because the client never sends one and
     * the server defaults it. A device that creates a row sees the same event
     * as a device that edits it, and applies it the same way.
     */
    private fun recordEvent(
        account: String,
        profileId: String,
        patch: DeltaPatch,
        data: JsonObject,
        eventType: SyncEventType,
        clock: Hlc?,
    ) {
        lsn += 1
        val feed = events.getOrPut(account) { mutableListOf() }
        feed +=
            SyncEvent(
                serverLsn = lsn,
                entityId = patch.entityId,
                entityType = patch.entityType,
                eventType = eventType,
                data = data,
                createdAt = System.currentTimeMillis(),
                hlc = clock,
                protocolVersion = patch.protocolVersion,
                profileId = profileId,
            )
    }

    /**
     * The row one entity is stored as.
     *
     * The document is the merged state; the field versions are the clock of
     * every field in it, and they are what the next patch is compared against.
     */
    private class ServerRow(private val entityType: DocType) {
        val doc = mutableMapOf<String, JsonElement>()
        val fieldVersions = mutableMapOf<String, Hlc>()
        var serverVersion: Long = 0L

        /**
         * Writes one operation, when its clock beats the stored clock for that
         * field, and lets the operation's kind decide what the document does.
         *
         * A field with no stored clock is written by any patch: there is nothing
         * to beat. A field whose stored clock is newer keeps both its value and
         * its clock, and the patch's answer says the field was lost.
         */
        fun write(
            op: FieldChange,
            clock: Hlc,
        ): Boolean {
            val stored = fieldVersions[op.field]
            if (stored != null && clock <= stored) return false
            apply(op)
            fieldVersions[op.field] = clock
            return true
        }

        /**
         * `set` states the value, `unset` removes the key, `append` extends the
         * list already there and `remove` drops the named element — or empties
         * the list when it names none.
         *
         * An `unset` removes the key rather than storing a null: "this field is
         * clear" and "this field's value is null" are different claims about the
         * row, and the merge would take the second literally.
         */
        private fun apply(op: FieldChange) {
            when (op.op) {
                FieldOp.SET -> doc[op.field] = op.value ?: JsonNull
                FieldOp.UNSET -> doc.remove(op.field)
                FieldOp.APPEND -> {
                    val current = (doc[op.field] as? JsonArray)?.toList().orEmpty()
                    doc[op.field] = JsonArray(op.value?.let { current + it } ?: current)
                }
                FieldOp.REMOVE -> {
                    val current = (doc[op.field] as? JsonArray)?.toMutableList() ?: return
                    if (op.value == null) current.clear() else current.removeAll { it == op.value }
                    doc[op.field] = JsonArray(current)
                }
            }
        }

        /**
         * The row as it stands, as a document the feed can carry.
         *
         * Every field the type has is in the document — null where no patch ever
         * set it — because the receiving device applies the event as a whole
         * row. A sparse document would read as "the server says nothing about
         * the other fields", which is not what a server row means.
         *
         * The document's own keys are included too. The type's field list does
         * not mention `deletedAt` for every type, and a tombstone must not be
         * dropped from the event that carries it.
         */
        fun snapshot(): JsonObject {
            val fields = (SyncContract.serverFieldsFor(entityType) + doc.keys).toSet()
            return JsonObject(fields.associateWith { field -> doc[field] ?: JsonNull })
        }
    }

    /** The identity of a row: the owner, the profile, the type and the entity. */
    private data class RowKey(
        val account: String,
        val profileId: String,
        val entityType: DocType,
        val entityId: String,
    )

    private companion object {
        /** The document field the server expresses a deletion as. */
        const val DELETED_AT = "deletedAt"

        /**
         * The node id the server's legacy path orders a clockless patch under.
         *
         * A patch that carries no clock is not ordered by a node, so the server
         * uses this one name for all of them — which is why two clockless
         * deletions that carry the same timestamp compare equal, and the second
         * loses.
         */
        const val LEGACY_NODE = "legacy"
    }
}

private class SharedSyncClient(private val server: SharedSyncServer, val authenticatedAs: String) : SyncApiClient {
    override var onBeforeResponseLoop: (MutableList<SyncOutboxEntity>, BatchPushResponse) -> Unit = { _, _ -> }

    override suspend fun batchPush(request: BatchPushRequest): BatchPushResponse = server.push(request, authenticatedAs)

    override suspend fun getEventsSince(
        sinceLsn: Long,
        limit: Int,
    ): List<SyncEvent> = server.pull(sinceLsn, limit, authenticatedAs)

    override suspend fun testConnection(): Result<Unit> = server.testConnection()

    override suspend fun getRemoteConfig(): kotlinx.serialization.json.JsonObject? = null
}
