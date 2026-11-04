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
 * - **A version that counts what landed.** The row's version moves only when a
 *   patch changed something, and the push answer carries it as the new version.
 * - **A refusal.** A patch whose every operation names a field the server does
 *   not accept is refused with `field_not_writable` and writes nothing.
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
 *
 * ## What it deliberately does not do
 *
 * - **It does not check the patch's base version.** The server does not either:
 *   the merge is per field, and the base version is the client's own bookkeeping.
 * - **It does not reproduce the server's hardcoded version.** The SQL answers
 *   `newVersion` with the number of operations that were applied, which the
 *   schema file itself records as a defect (#207). This fixture answers with
 *   the row's version, because the client records that number as the row's
 *   version and would otherwise be told a fiction.
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
     */
    private fun mergePatch(
        profileId: String,
        patch: DeltaPatch,
        account: String,
    ): PatchResult {
        val clock = patch.hlc
        val writable = patch.ops.filter { it.field in SyncContract.serverFieldsFor(patch.entityType) }
        if (clock == null || writable.isEmpty()) {
            // A patch with no clock is the legacy shape: the server expands the
            // absent document into zero operations and refuses it. A patch whose
            // operations all name a field the server does not accept is refused
            // the same way.
            return PatchResult(
                patchId = patch.patchId,
                ok = false,
                error = SyncProtocol.FIELD_NOT_WRITABLE,
            )
        }
        val key = RowKey(account, profileId, patch.entityType, patch.entityId)
        val row = rows[key] ?: ServerRow(patch.entityType)
        var applied = 0
        writable.forEach { op ->
            if (row.write(op, clock)) applied += 1
        }
        if (applied == 0) {
            // Every field this patch carried is already held in a newer version.
            // The server took the patch, understood it, and kept what it had —
            // which is what `lost` says, and it is why the answer is still ok.
            return PatchResult(
                patchId = patch.patchId,
                ok = true,
                lost = true,
                newVersion = row.serverVersion,
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
     * A row the server never held is not created by a deletion: there would be
     * nothing to restore, and a row that exists only to be deleted is a row the
     * feed carries forever. The answer still says ok — there was nothing to do —
     * but no tombstone and no event are produced.
     */
    private fun deleteRow(
        profileId: String,
        patch: DeltaPatch,
        account: String,
    ): PatchResult {
        // A delete patch need not carry a clock — the protocol's own
        // `deltaPatchDelete` does not — so order it the way the server's legacy
        // path does: by the patch's timestamp, under the node id `legacy`.
        val clock = patch.hlc ?: Hlc.of(patch.timestampMs ?: 0L, 0, "legacy")
        val key = RowKey(account, profileId, patch.entityType, patch.entityId)
        val row = rows[key]
            ?: return PatchResult(patchId = patch.patchId, ok = true, newVersion = null)
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
                newVersion = row.serverVersion,
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
