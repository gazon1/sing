@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.test.fakes

import com.singularity.todo.core.sync.BatchPushRequest
import com.singularity.todo.core.sync.BatchPushResponse
import com.singularity.todo.core.sync.DeltaPatch
import com.singularity.todo.core.sync.DocType
import com.singularity.todo.core.sync.FieldChange
import com.singularity.todo.core.sync.FieldOp
import com.singularity.todo.core.sync.Hlc
import com.singularity.todo.core.sync.PatchResult
import com.singularity.todo.core.sync.SyncApiClient
import com.singularity.todo.core.sync.SyncContract
import com.singularity.todo.core.sync.SyncEvent
import com.singularity.todo.core.sync.SyncEventType
import com.singularity.todo.core.sync.SyncOutboxEntity
import com.singularity.todo.core.sync.SyncProtocol
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
 *   the fields some patch happened to mention. A device that pulls this feed
 *   writes `data` as the row (see `PullEventHandler`): it is the row, not a
 *   diff, so two devices that both pull converge on the same document.
 * - **A write is a change, or it is not a write.** An operation whose clock wins
 *   but whose effect on the document is nil — an `unset` of a field that was
 *   never set, a `set` of a null value to a field that is already clear, a
 *   `remove` of an element no list holds — leaves the row's version and the
 *   field's clock exactly as they were, and the patch is answered with
 *   `ok, lost` because the row kept what it had. Otherwise a patch that changed
 *   nothing would move the version and put an event on the feed carrying a
 *   document identical to the one already there.
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

    /**
     * The answers already given, keyed by [PatchKey]: the owner, the profile,
     * the row's type and entity, and the patch id. The row's dimensions are in
     * the key because an answer is an answer about a row; [PatchKey] carries the
     * reason for each one.
     *
     * Bounded: see [MAX_RETAINED_ANSWERS]. Insertion ordered so the oldest
     * answer is the one evicted.
     *
     * Bounded idempotency: an answer older than the window is evicted, and a
     * replay of that patch id is applied again. SET operations are protected by
     * per-field HLC comparison, but APPEND is not intrinsically idempotent, so
     * a replay after eviction can append the same element twice.
     */
    private val answers = LinkedHashMap<PatchKey, PatchResult>()

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
            // No sort: the feed is append-only and [prune] only removes entries,
            // so it is already ordered by `serverLsn` — filtering preserves that
            // order, and the first `limit` of it is the page. Sorting here would
            // be dead work over up to [TARGET_RETAINED_EVENTS] events, per pull,
            // inside the mutex.
            events[account]
                .orEmpty()
                .filter { it.serverLsn > sinceLsn }
                .take(limit)
        }

    internal suspend fun testConnection(): Result<Unit> = Result.success(Unit)

    /**
     * Answers one patch, from the cache when the same patch id was already
     * applied for this account, profile and entity.
     *
     * The cache is the server's idempotency table: a client that retries after
     * a timeout gets the original answer back instead of applying twice. A
     * refused patch is not remembered — the server refuses it again, and the
     * answer costs nothing to recompute.
     *
     * The key carries the profile because the row does: the same entity id in
     * two profiles is two documents, so an answer given in one profile must not
     * be replayed as the answer for the other — that would settle a patch
     * against a row it never touched, silently. It carries the entity too, for
     * the same reason one step further out: a patch id names one patch, and a
     * patch names one entity.
     */
    private fun applyPatch(
        profileId: String,
        patch: DeltaPatch,
        account: String,
    ): PatchResult {
        val key = PatchKey(account, profileId, patch.entityType, patch.entityId, patch.patchId)
        val cached = answers[key]
        if (cached != null) return cached.copy(cached = true)
        val answer =
            if (patch.isDelete) {
                deleteRow(profileId, patch, account)
            } else {
                mergePatch(profileId, patch, account)
            }
        if (answer.ok) remember(key, answer)
        return answer
    }

    /**
     * Records one answer, evicting the oldest once [MAX_RETAINED_ANSWERS] are
     * held.
     *
     * A remembered answer only matters for the retry window, so an answer older
     * than the window is worthless; without the bound a fixture driven in a loop
     * keeps one [PatchResult] per patch for the life of the JVM.
     */
    private fun remember(
        key: PatchKey,
        answer: PatchResult,
    ) {
        answers[key] = answer
        while (answers.size > MAX_RETAINED_ANSWERS) {
            val oldest = answers.keys.first()
            answers.remove(oldest)
        }
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
        // The tombstone is the server's own field: a merge patch may not write it,
        // whatever a type's allowlist happens to list. A deletion is expressed as
        // `isDelete`, and letting an ordinary `set` reach `deletedAt` would make a
        // resurrection look like a field edit.
        val outsideTheAllowlist = patch.ops.any { it.field !in allowed || it.field == DELETED_AT }
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
        prune(account, feed)
    }

    /**
     * Keeps the feed from growing without a bound, without forgetting a row.
     *
     * The bound exists because a fixture driven in a loop would otherwise keep
     * every snapshot it ever emitted. What it must not do is drop the only word
     * on a row: a puller whose cursor is below the floor never asks again for
     * what it missed, [pull] answers it with what is retained, and [SyncEvent]
     * has no field in which to say "something is missing" — so an event dropped
     * while it was the newest event for its row would leave that puller
     * converged on a row that is not there, silently.
     *
     * So an event goes only when a later event for the same row is still on the
     * feed. That later event carries the whole row ([ServerRow.snapshot]), not a
     * diff, so every puller that misses the dropped event receives its successor
     * and applies the row as it stands — which is what makes the retained feed
     * sufficient rather than merely truncated. The newest event for a row is
     * never dropped, so the feed can outgrow the bound only by holding one event
     * per row, which is the same order of memory [rows] already uses.
     *
     * Pruning removes entries without adding or reordering any, so the feed
     * stays ordered by `serverLsn` — the order [pull] relies on.
     */
    private fun prune(
        account: String,
        feed: MutableList<SyncEvent>,
    ) {
        if (feed.size <= TARGET_RETAINED_EVENTS) return
        // The feed is ascending by lsn, so the last write per row wins.
        val newest = mutableMapOf<RowKey, Long>()
        feed.forEach { event -> newest[rowKeyOf(account, event)] = event.serverLsn }
        var index = 0
        while (feed.size > TARGET_RETAINED_EVENTS && index < feed.size) {
            val event = feed[index]
            if (newest[rowKeyOf(account, event)] == event.serverLsn) index += 1 else feed.removeAt(index)
        }
    }

    private fun rowKeyOf(
        account: String,
        event: SyncEvent,
    ): RowKey = RowKey(account, event.profileId, event.entityType, event.entityId)

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
         * field **and** the operation changes the document.
         *
         * A field with no stored clock is written by any patch: there is nothing
         * to beat. A field whose stored clock is newer keeps both its value and
         * its clock, and the patch's answer says the field was lost.
         *
         * ## A clock that wins and changes nothing is not a write
         *
         * The clock moves only when the document moved. An operation that leaves
         * the document exactly as it was — a `remove` of an element the list does
         * not hold, an `unset` of a field that was never set, an `append` to a
         * field the row holds as something other than a list — reports `false`,
         * so it is not counted as applied: the patch changes nothing, the row's
         * version does not move, and no event is emitted. Stamping the field's
         * clock there would claim the field was written at a time nothing was
         * written to it, and would give a patch that changed nothing a version of
         * its own — the one thing the row's version must not do.
         */
        fun write(
            op: FieldChange,
            clock: Hlc,
        ): Boolean {
            val stored = fieldVersions[op.field]
            if (stored != null && clock <= stored) return false
            if (!apply(op)) return false
            fieldVersions[op.field] = clock
            return true
        }

        /**
         * Applies the operation's kind to the document, and says whether the
         * document changed.
         *
         * `set` states the value, `unset` removes the key, `append` extends the
         * list already there and `remove` drops the named element — or empties
         * the list when it names none.
         *
         * Clearing is `doc.remove`, for both spellings of it: an `unset`, and a
         * `set` whose value is null. The two are one claim about the row — "this
         * field holds nothing" — and this fixture stores them as one state so a
         * device cannot clear a field and have a peer read it as a value.
         *
         * Nothing here invents a value the patch did not state. An `append` with
         * no value, and an `append` to a field held as a non-list, are no-ops
         * rather than "the field becomes a list": replacing a scalar with a list
         * the patch never named is a loss dressed up as a change.
         */
        private fun apply(op: FieldChange): Boolean =
            when (op.op) {
                FieldOp.SET -> {
                    val next = op.value
                    if (next == null || next is JsonNull) {
                        // A `set` of a null value is not a value: it is the same
                        // claim `unset` makes — the field holds nothing — and it is
                        // treated as one here so the two spellings of "cleared"
                        // cannot disagree. JSON Merge Patch (RFC 7386) reads a null
                        // member the same way, by removing it, and [snapshot] renders
                        // an absent field as `JsonNull` anyway, so storing one would
                        // be a state the feed cannot distinguish from absent while
                        // the document here could.
                        //
                        // Counting it as a change is what this used to do, and it was
                        // wrong twice over: `doc[field]` is `null` for a field that
                        // was never written while `next` is `JsonNull`, so the two
                        // compared unequal, and a clear with nothing to clear moved
                        // the row's version and put an event on the feed carrying a
                        // document identical to the one already there.
                        doc.remove(op.field) != null
                    } else if (doc[op.field] == next) {
                        false
                    } else {
                        doc[op.field] = next
                        true
                    }
                }

                FieldOp.UNSET -> {
                    doc.remove(op.field) != null
                }

                FieldOp.APPEND -> {
                    append(op)
                }

                FieldOp.REMOVE -> {
                    remove(op)
                }
            }

        /** `append` extends the list already there; it never turns a scalar into one. */
        private fun append(op: FieldChange): Boolean {
            val value = op.value ?: return false
            val current = doc[op.field]
            val list =
                when (current) {
                    null -> emptyList()
                    is JsonArray -> current.toList()
                    else -> return false
                }
            doc[op.field] = JsonArray(list + value)
            return true
        }

        /** `remove` drops the named element, or empties the list when it names none. */
        private fun remove(op: FieldChange): Boolean {
            val current = doc[op.field] as? JsonArray ?: return false
            val value = op.value
            val kept = if (value == null) emptyList() else current.filterNot { it == value }
            if (kept.size == current.size) return false
            doc[op.field] = JsonArray(kept)
            return true
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

    /**
     * The identity of an answer: the row's own dimensions plus the patch id.
     *
     * It is [RowKey] plus the patch id because an idempotency answer is an
     * answer about a row. Keyed by account alone, the same patch id replayed in
     * another profile of the same account would be answered from the cache and
     * never applied to the row that profile names.
     */
    private data class PatchKey(
        val account: String,
        val profileId: String,
        val entityType: DocType,
        val entityId: String,
        val patchId: String,
    )

    private companion object {
        /** The document field the server expresses a deletion as. */
        const val DELETED_AT = "deletedAt"

        /**
         * How many events one account's feed holds before [prune] starts
         * dropping the ones a later event has restated.
         *
         * The real server keeps every event (`sync_events` has no pruning), so
         * this bound is the fixture's own, and it is chosen so that a fixture
         * driven in a loop stays bounded without ever dropping the only event
         * that carries a row: see [prune] for why that is the property that
         * matters and what a puller below the floor is owed.
         */
        const val TARGET_RETAINED_EVENTS = 4096

        /** How many idempotency answers are remembered before the oldest go. */
        const val MAX_RETAINED_ANSWERS = 4096

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
