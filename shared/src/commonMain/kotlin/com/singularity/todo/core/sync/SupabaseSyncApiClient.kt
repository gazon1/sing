package com.singularity.todo.core.sync

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * [SyncApiClient] over the project's Postgres RPC functions.
 *
 * ## The failure contract
 *
 * Every method reports a transport failure as an [AppError] rather than letting
 * the vendor's exception escape. A raw `RestException` says "HTTP 401" and
 * nothing about what the app should do, whereas the sync engine's caller — and
 * the crash reporter above it — groups failures by [AppError.code]. An error type
 * that changes shape with the SDK's version is not one a dashboard can group by.
 * The original exception is kept as the cause, so a stack trace survives.
 *
 * This is also the one place in the project entitled to catch something it cannot
 * name. A transport boundary is exactly where an unknown throwable is expected —
 * the SDK, Ktor and the platform socket layer each have their own — and the catch
 * does not recover: it converts and re-throws as a typed error. A narrower catch
 * here would let a timeout escape as a timeout and reach the crash reporter as
 * whatever the SDK happened to call it this release.
 *
 * [testConnection] is the exception, and deliberately: it answers a question and
 * returns `Result` rather than throwing, because "can we reach the server" is
 * asked as a question.
 *
 * ## Reading, not deciding
 *
 * What the bytes mean lives in `SyncWire.kt`. This class decides what a failure
 * means and which function to call; the other file decides what a field is called
 * and what an absent one defaults to.
 */
class SupabaseSyncApiClient(private val rpc: SyncRpc) : SyncApiClient {

    // Test hook: a real client has no use for pendingRef, so null is correct.
    override var pendingRef: MutableList<SyncOutboxEntity>? = null

    override suspend fun batchPush(request: BatchPushRequest): BatchPushResponse {
        val body = callRpc("sync_batch_apply") { function ->
            rpc.call(function, buildJsonObject { put("p_patches", request.buildPatches()) })
        }
        return BatchPushResponse(
            results = body.readObject("the batch push response")
                .requiredArray("results", "the batch push response")
                .map { it.jsonObject.toPatchResult() },
        )
    }

    override suspend fun getEventsSince(sinceLsn: Long, limit: Int): List<SyncEvent> {
        val body = callRpc("sync_events_since") { function ->
            rpc.call(
                function,
                buildJsonObject {
                    put("p_since_lsn", JsonPrimitive(sinceLsn))
                    put("p_limit", JsonPrimitive(limit))
                },
            )
        }
        val array = body.readJson("the event feed") as? JsonArray
            ?: throw malformed("the event feed was not a list; the cursor cannot advance past it")
        return array.map { it.jsonObject.toSyncEvent() }
    }

    override suspend fun testConnection(): Result<Unit> = runCatchingCancellable {
        callRpc("sync_health") { function -> rpc.call(function, JsonObject(emptyMap())) }
    }

    /**
     * Remote configuration is not served by this schema.
     *
     * The AI feature's model and tool flags are read through this method, and the
     * contract has always allowed `null` for "nothing is configured" — the cache
     * then keeps its last-known-good snapshot, or its defaults. The sync schema
     * has no configuration table and no function that would return one, so `null`
     * is the accurate answer rather than a placeholder for forgotten work: there
     * is no server-side source to read, and inventing a table here would be the
     * AI feature's decision to make in its own change.
     */
    override suspend fun getRemoteConfig(): JsonObject? = null

    /**
     * Runs the call, converting anything thrown by the transport into [AppError].
     *
     * Cancellation passes through untouched: a coroutine cancelled mid-request is
     * not a failed request, and reporting it as one puts a spurious
     * `sync.rpc_failed` into the crash reporter every time a sync is superseded.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun callRpc(function: String, call: suspend (String) -> String): String = try {
        call(function)
    } catch (e: CancellationException) {
        throw e
    } catch (e: AppError) {
        throw e
    } catch (e: Exception) {
        throw AppError.Network(
            "$function failed: ${e.message ?: e::class.simpleName}",
            code = "sync.rpc_failed",
            cause = e,
        )
    }

    /**
     * Builds the patch array the batch function consumes.
     *
     * Hand-built rather than serialised from [DeltaPatch], because two things on
     * the wire are not the shape the domain model has them in.
     *
     * The profile is stamped per request, not per patch: it names which of the
     * account's profiles this cycle belongs to. It is *not* an authorisation
     * input — the server derives the owner from the session, and no value here can
     * widen that — which is why it does not trip `SyncTransportIdentityTest`, a
     * rule about owner identity only.
     *
     * A missing clock is refused rather than sent. Omitting the key is not
     * neutral: the server reads an absent `hlc` as an older client sending a full
     * snapshot, expands the also-absent `doc` into zero operations, and rejects
     * the patch as `field_not_writable`. That is a correct refusal of a patch that
     * means nothing, and the server is right to make it — but the local cause was
     * a builder that forgot a clock, and refusing here says so.
     */
    private fun BatchPushRequest.buildPatches(): JsonArray = buildJsonArray {
        patches.forEach { patch ->
            val clock = patch.hlc
                ?: throw AppError.Validation(
                    "Patch ${patch.patchId} carries no logical clock and cannot be merged.",
                    code = "sync.patch_without_clock",
                )
            add(
                buildJsonObject {
                    put("patchId", JsonPrimitive(patch.patchId))
                    put("entityId", JsonPrimitive(patch.entityId))
                    put("entityType", JsonPrimitive(patch.entityType.key))
                    put("profileId", JsonPrimitive(profileId))
                    put("protocolVersion", JsonPrimitive(patch.protocolVersion))
                    put("isDelete", JsonPrimitive(patch.isDelete))
                    put("ops", patch.buildOps())
                    put("hlc", clock.toWire())
                },
            )
        }
    }

    private fun DeltaPatch.buildOps(): JsonArray = buildJsonArray {
        ops.forEach { op ->
            add(
                buildJsonObject {
                    put("field", JsonPrimitive(op.field))
                    put("op", JsonPrimitive(op.op.wireName))
                    if (op.op.carriesValue) put("value", op.value ?: JsonPrimitive(null))
                },
            )
        }
    }
}
