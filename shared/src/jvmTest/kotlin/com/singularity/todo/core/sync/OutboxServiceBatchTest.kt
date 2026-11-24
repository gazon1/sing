@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.core.sync.work.FakeHlcFactory
import com.singularity.todo.test.helpers.MutableClock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The batched enqueue: the same contract as the per-entity one, one pass instead of N.
 *
 * ## Why this exists
 *
 * A profile wipe used to enqueue its rows one at a time, and each of those wrote the
 * sync clock to disk — a DataStore file write per removed row. A wipe of ten thousand
 * rows paid ten thousand file writes, and that is what made it take minutes.
 *
 * The batch is only allowed to be *cheaper*, never *different*. So the tests here ask
 * two questions of it:
 *
 * 1. **Is it the same work?** One patch per entity, each carrying that entity's own
 *    state, each diffed against the same confirmed state — compared against what the
 *    per-entity path produces, field by field.
 * 2. **Is it bounded?** The statements the batch issues must grow with the number of
 *    chunks, not with the number of rows.
 *
 * ## What it does not prove
 *
 * That the wipe is fast on a phone. The clock write is the cost this removes, and the
 * number of statements is what this measures; the wall clock on a device is a
 * measurement of the device, and it is recorded in
 * `docs/architecture/large-restore-results.md` rather than asserted here.
 */
@Tag("fast")
class OutboxServiceBatchTest {
    private val clock = MutableClock()
    private val log = Logger.withTag("OutboxServiceBatchTest")

    /** Minimal [SyncableEntity] whose JSON the test controls field by field. */
    private class TestEntity(override val syncId: String) : SyncableEntity {
        override val docType: DocType = DocType.Task
        override val syncServerVersion: Long = 0
        override val syncHlc: Hlc? = null

        override fun toJson() = buildJsonObject { put("title", JsonPrimitive("title $syncId")) }
    }

    /**
     * The outbox DAO, counting the statements the batch issues.
     *
     * [insertAll] does its own row bookkeeping rather than calling the fake's
     * [insert]: the fake's list form is written in terms of the single form, and the
     * single form is one of the statements this test says the batch does not issue.
     */
    private class CountingOutboxDao : FakeSyncOutboxDao() {
        var insertAllCalls = 0
        var insertCalls = 0
        var deleteByEntitiesCalls = 0
        var deleteByEntityCalls = 0

        /** How many rows each insert statement carried. */
        val insertAllSizes = mutableListOf<Int>()

        override suspend fun insert(entity: SyncOutboxEntity) {
            insertCalls++
            super.insert(entity)
        }

        override suspend fun insertAll(entities: List<SyncOutboxEntity>) {
            insertAllCalls++
            insertAllSizes += entities.size
            entities.forEach { entity ->
                rows.removeAll { row -> row.patchId == entity.patchId }
                rows += entity
            }
        }

        override suspend fun deleteByEntity(
            ownerId: String,
            profileId: String,
            entityId: String,
        ) {
            deleteByEntityCalls++
            super.deleteByEntity(ownerId, profileId, entityId)
        }

        override suspend fun deleteByEntities(
            ownerId: String,
            profileId: String,
            entityIds: List<String>,
        ): Int {
            deleteByEntitiesCalls++
            return super.deleteByEntities(ownerId, profileId, entityIds)
        }
    }

    /**
     * The shadow DAO, counting how many ids each read carried.
     *
     * A read of the whole batch's shadows at once would hold every row's state in
     * memory at the same time — the same thing the chunking of the patches avoids.
     */
    private class CountingShadowDao : FakeSyncShadowDao() {
        val readSizes = mutableListOf<Int>()

        override suspend fun getAll(
            ownerId: String,
            profileId: String,
            entityIds: List<String>,
        ): List<SyncShadowEntity> {
            readSizes += entityIds.size
            return super.getAll(ownerId, profileId, entityIds)
        }
    }

    /**
     * The service under test.
     *
     * The patch builder is the **production** [SyncPatchBuilder], constructed here
     * rather than taken from a helper, so that a reader of this file can see what
     * builds the patches: the batch's chunking, its shadow reads and its diffs are
     * the production code, and only the two DAOs below it are test doubles. A
     * test double builder would make the equivalence test below compare two
     * executions of the double and prove nothing about the batch.
     */
    private fun TestScope.service(
        outbox: FakeSyncOutboxDao = FakeSyncOutboxDao(),
        shadow: FakeSyncShadowDao = FakeSyncShadowDao(),
        scopeProvider: SyncScopeProvider = FakeSyncScopeProvider(SyncScope("owner-1", "profile-1")),
    ): OutboxService =
        OutboxService(
            outboxDao = outbox,
            patchBuilder =
                SyncPatchBuilder(
                    shadowDao = shadow,
                    hlcFactory = FakeHlcFactory(),
                    idGenerator = SequentialIdGenerator(),
                ),
            scopeProvider = scopeProvider,
            engineState =
                SyncEngineState(
                    log = log,
                    crashReporter = NoOpCrashReportingPort(),
                ),
            monoNow = { clock.now().toEpochMilliseconds() },
        )

    private fun SyncOutboxEntity.patch(): DeltaPatch = StableJson.decodeFromString(DeltaPatch.serializer(), payload)

    // ─── the contract is the same ───────────────────────────────────────────────

    @Test
    fun `the batch writes one patch per entity`() =
        runTest {
            val outbox = FakeSyncOutboxDao()
            val svc = service(outbox = outbox)

            val result = svc.enqueueAll((1..5).map { TestEntity("t-$it") })

            assertTrue(result.isSuccess, "the batch must succeed: $result")
            assertEquals(5, outbox.rows.size, "one patch per entity — nothing is summarised away")
            assertEquals(
                listOf("t-1", "t-2", "t-3", "t-4", "t-5"),
                outbox.rows.map { it.entityId },
                "every entity of the batch gets its own row",
            )
            assertEquals(
                outbox.rows.size,
                outbox.rows
                    .map { it.patchId }
                    .distinct()
                    .size,
                "every entity of the batch gets its own patch",
            )
        }

    @Test
    fun `the batch writes the same patches the per-entity path writes`() =
        runTest {
            val entities = (1..3).map { TestEntity("t-$it") }

            val oneByOne = FakeSyncOutboxDao()
            val singleService = service(outbox = oneByOne)
            entities.forEach { singleService.enqueue(it) }

            val batched = FakeSyncOutboxDao()
            val batchService = service(outbox = batched)
            batchService.enqueueAll(entities)

            assertEquals(
                oneByOne.rows.map { it.entityId },
                batched.rows.map { it.entityId },
                "the same entities, in the same order",
            )
            oneByOne.rows.zip(batched.rows).forEach { (single, batch) ->
                assertEquals(
                    single.patch().ops,
                    batch.patch().ops,
                    "the same field changes — the batch diffs against the same base",
                )
                assertEquals(
                    single.patch().entityType,
                    batch.patch().entityType,
                    "the same document type",
                )
                assertEquals(
                    single.patch().baseVersion,
                    batch.patch().baseVersion,
                    "the same base version",
                )
            }
        }

    /**
     * The state the server is known to hold, for every entity of the batch.
     *
     * Three things are in it, and each of them is a way for the batch to be wrong:
     *
     * * a **confirmed** state that differs from the local one, so the diff has
     *   something to diff against;
     * * an **in-flight** state that differs from the confirmed one, so a diff taken
     *   against the queued patch instead of the confirmed state shows up — the
     *   queued patch's extra field would be unset;
     * * a **server version**, so a patch that forgot to carry it shows up.
     */
    private suspend fun seedConfirmedState(shadow: FakeSyncShadowDao) {
        (1..3).forEach { index ->
            shadow.upsert(
                SyncShadowEntity(
                    ownerId = "owner-1",
                    profileId = "profile-1",
                    entityType = DocType.Task.key,
                    entityId = "t-$index",
                    confirmedJson = """{"title":"old title t-$index"}""",
                    inFlightJson = """{"title":"in flight t-$index","extra":"queued"}""",
                    inFlightPatchId = "queued-patch-$index",
                    serverVersion = 7,
                ),
            )
        }
    }

    /**
     * The batch diffs against the confirmed state, exactly as the per-entity path does.
     *
     * The equivalence test above runs against entities the server has never seen: the
     * base is empty, so every field of the entity is a change. That is the easy half
     * of the contract.
     *
     * The hard half is an entity the server *does* hold. The diff has to be taken
     * against the confirmed state — the state the server really holds — and not
     * against the in-flight state of the queued patch, which the server has not
     * applied yet: a diff against the in-flight state drops the fields the queued
     * patch carried, and the server loses them. And the patch has to carry the
     * server's version, or the server refuses it as based on a version it never had.
     */
    @Test
    fun `the batch writes the same patches against a confirmed state`() =
        runTest {
            val entities = (1..3).map { TestEntity("t-$it") }

            val singleShadow = FakeSyncShadowDao()
            seedConfirmedState(singleShadow)
            val oneByOne = FakeSyncOutboxDao()
            val singleService = service(outbox = oneByOne, shadow = singleShadow)
            entities.forEach { singleService.enqueue(it) }

            val batchShadow = FakeSyncShadowDao()
            seedConfirmedState(batchShadow)
            val batched = FakeSyncOutboxDao()
            val batchService = service(outbox = batched, shadow = batchShadow)
            batchService.enqueueAll(entities)

            oneByOne.rows.zip(batched.rows).forEach { (single, batch) ->
                assertEquals(
                    single.patch().ops,
                    batch.patch().ops,
                    "the same field changes — the batch diffs against the same base",
                )
                assertEquals(
                    single.patch().baseVersion,
                    batch.patch().baseVersion,
                    "the same base version",
                )
            }

            val patch = batched.rows.first().patch()
            assertEquals(
                7L,
                patch.baseVersion,
                "the patch carries the version the server confirmed",
            )
            assertEquals(
                listOf("title"),
                patch.ops.map { it.field },
                "the diff is taken against the confirmed state, not against the " +
                    "in-flight state of the queued patch: ${patch.ops}",
            )
        }

    @Test
    fun `the batch replaces an entity's queued patch and leaves another profile's row alone`() =
        runTest {
            val outbox = FakeSyncOutboxDao()
            val svc = service(outbox = outbox)

            // Another profile's queued patch for the same entity id.
            outbox.insert(
                SyncOutboxEntity(
                    patchId = "other-profile-patch",
                    ownerId = "owner-1",
                    profileId = "profile-2",
                    entityId = "t-1",
                    entityType = DocType.Task.key,
                    payload = "{}",
                    createdAt = 0L,
                ),
            )
            // This profile's queued patch for the same entity, to be replaced.
            svc.enqueue(TestEntity("t-1"))

            val result = svc.enqueueAll(listOf(TestEntity("t-1"), TestEntity("t-2")))

            assertTrue(result.isSuccess, "the batch must succeed: $result")
            assertEquals(
                1,
                outbox.rows.count { it.patchId == "other-profile-patch" },
                "another profile's queued patch must survive the batch",
            )
            assertEquals(
                2,
                outbox.rows.count { it.profileId == "profile-1" },
                "the batch queues one row per entity — the replaced row is not a second one",
            )
            assertEquals(
                3,
                outbox.rows.size,
                "the other profile's row and the batch's two rows",
            )
        }

    // ─── the statements are bounded ────────────────────────────────────────────

    @Test
    fun `the batch issues one insert and one delete per chunk, not one per entity`() =
        runTest {
            val outbox = CountingOutboxDao()
            val svc = service(outbox = outbox)
            val entities = (1..1200).map { TestEntity("t-$it") }

            val result = svc.enqueueAll(entities)

            assertTrue(result.isSuccess, "the batch must succeed: $result")
            assertEquals(1200, outbox.rows.size, "every entity still gets a row")
            assertEquals(
                3,
                outbox.insertAllCalls,
                "the rows are inserted one statement per chunk — 1200 rows in chunks of " +
                    "$SQL_BIND_CHUNK is three statements, not 1200",
            )
            assertEquals(
                3,
                outbox.deleteByEntitiesCalls,
                "the coalescing delete is one statement per chunk of ids — 1200 ids in chunks " +
                    "of $SQL_BIND_CHUNK is three statements, not 1200",
            )
            assertEquals(0, outbox.insertCalls, "the per-entity insert is not used")
            assertEquals(0, outbox.deleteByEntityCalls, "the per-entity delete is not used")
        }

    @Test
    fun `the batch holds one chunk of patches at a time, not the whole batch`() =
        runTest {
            val outbox = CountingOutboxDao()
            val shadow = CountingShadowDao()
            val svc = service(outbox = outbox, shadow = shadow)
            val entities = (1..1200).map { TestEntity("t-$it") }

            val result = svc.enqueueAll(entities)

            assertTrue(result.isSuccess, "the batch must succeed: $result")
            assertTrue(
                outbox.insertAllSizes.all { it <= SQL_BIND_CHUNK },
                "no statement carries more than a chunk of rows: ${outbox.insertAllSizes}",
            )
            assertTrue(
                shadow.readSizes.all { it <= SQL_BIND_CHUNK },
                "the shadows are read chunk by chunk, not all at once: ${shadow.readSizes}",
            )
        }
}
