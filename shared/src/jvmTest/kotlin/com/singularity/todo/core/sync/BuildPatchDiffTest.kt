package com.singularity.todo.core.sync

import com.singularity.todo.core.serialization.StableJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The patch builder's diff, which is the whole of REQ-OS-002.
 *
 * ## What was wrong before
 *
 * `buildPatch` shipped `ops = emptyList()` and a row checksum. The server could then
 * only ask "does the row you based this on still look the same?", and the answer
 * yes-or-no resolves a whole-row conflict by arrival order. Two devices editing two
 * different fields of the same task cannot both win that way, and the product
 * requires them to (REQ-OS-003).
 *
 * A diff needs a base, and the base is the shadow. These tests are mostly about the
 * base being the *right* one — a diff computed against a stale base is correct code
 * producing a wrong patch, which is the failure mode a unit test around the diff
 * function alone would miss.
 */
@Tag("fast")
class BuildPatchDiffTest {

    private val scope = SyncScope("owner-1", "profile-a")

    /** A minimal entity whose JSON the test controls field by field. */
    private class TestEntity(private val fields: JsonObject, override val syncId: String = "entity-1") :
        SyncableEntity {
        override val docType: DocType = DocType.Task
        override val syncServerVersion: Long = 0
        override val syncHlc: Hlc? = null
        override fun toJson(): JsonObject = fields
    }

    private fun entity(vararg pairs: Pair<String, String?>, id: String = "entity-1"): TestEntity =
        TestEntity(
            fields = buildJsonObject {
                for ((key, value) in pairs) {
                    if (value == null) put(key, JsonNull) else put(key, JsonPrimitive(value))
                }
            },
            syncId = id,
        )

    private fun builder(shadowDao: SyncShadowDao) = fakeSyncPatchBuilder(shadowDao)

    private fun opsOf(patch: DeltaPatch) = patch.ops.associate { it.field to it.op }

    // ─── The diff itself ──────────────────────────────────────────────────────

    @Test
    fun `a one-field edit yields exactly one field operation`() = runTest {
        val shadowDao = FakeSyncShadowDao()
        val builder = builder(shadowDao)

        // First upload: the server has nothing, so it needs everything.
        builder.build(entity("title" to "Buy milk", "dueDate" to "2026-10-05"), scope, NOW)
        // Second: the user moves the due date. The title did not change.
        val patch = builder.build(entity("title" to "Buy milk", "dueDate" to "2026-10-06"), scope, NOW)

        assertEquals(setOf("dueDate"), patch.ops.map { it.field }.toSet())
        assertEquals(FieldOp.SET, opsOf(patch)["dueDate"])
        assertEquals(
            null,
            patch.ops.firstOrNull { it.field == "title" },
            "an unchanged field must not be in the patch — that is the bandwidth and the " +
                "overwritten-concurrent-edit problem the diff exists to avoid",
        )
    }

    @Test
    fun `a first upload carries every field, as operations rather than a snapshot`() = runTest {
        val patch = builder(FakeSyncShadowDao())
            .build(entity("title" to "Buy milk", "done" to "false"), scope, NOW)

        assertEquals(setOf("title", "done"), patch.ops.map { it.field }.toSet())
        assertTrue(
            patch.ops.all { it.op == FieldOp.SET },
            "a first upload is still a list of field operations, so the server merges it " +
                "like any other",
        )
    }

    @Test
    fun `a field cleared to null becomes an unset`() = runTest {
        val shadowDao = FakeSyncShadowDao()
        val builder = builder(shadowDao)

        builder.build(entity("title" to "Buy milk", "note" to "aisle 3"), scope, NOW)
        val patch = builder.build(entity("title" to "Buy milk", "note" to null), scope, NOW)

        val op = patch.ops.single { it.field == "note" }
        assertEquals(FieldOp.UNSET, op.op)
        assertEquals(null, op.value)
    }

    @Test
    fun `a field that disappears from the entity is unset, not omitted`() = runTest {
        val shadowDao = FakeSyncShadowDao()
        val builder = builder(shadowDao)

        builder.build(entity("title" to "Buy milk", "legacy" to "x"), scope, NOW)
        val patch = builder.build(entity("title" to "Buy milk"), scope, NOW)

        // Silence would be indistinguishable from "this client never knew the field",
        // and the server would keep a value the user has removed.
        assertEquals(FieldOp.UNSET, opsOf(patch)["legacy"])
    }

    @Test
    fun `an unchanged entity produces an empty patch`() = runTest {
        val shadowDao = FakeSyncShadowDao()
        val builder = builder(shadowDao)

        builder.build(entity("title" to "Buy milk"), scope, NOW)
        val patch = builder.build(entity("title" to "Buy milk"), scope, NOW)

        assertTrue(patch.ops.isEmpty(), "re-saving without editing must send nothing")
    }

    // ─── The base the diff is taken against ───────────────────────────────────

    @Test
    fun `the diff is taken against the in-flight state, not the confirmed one`() = runTest {
        val shadowDao = FakeSyncShadowDao()
        val builder = builder(shadowDao)

        builder.build(entity("title" to "A", "note" to "n"), scope, NOW) // patch 1, in flight
        val patch2 = builder.build(entity("title" to "B", "note" to "n"), scope, NOW) // title only

        // Patch 1 has not been pushed, so the server does not have "note" from *this*
        // client yet. If patch 2 re-sent it, both patches would write it and the
        // payload would be twice the necessary size for as long as the outbox is
        // non-empty — with no counter anywhere to show it.
        assertEquals(listOf("title"), patch2.ops.map { it.field })
    }

    @Test
    fun `after a push is confirmed, the next diff is taken against the pushed state`() = runTest {
        val shadowDao = FakeSyncShadowDao()
        val builder = builder(shadowDao)

        val first = builder.build(entity("title" to "A", "note" to "n"), scope, NOW)
        val inFlight = assertNotNull(
            shadowDao.state("owner-1", "profile-a", DocType.Task.key, "entity-1"),
        ).inFlightJson
        assertEquals(
            1,
            shadowDao.confirm("owner-1", "profile-a", DocType.Task.key, "entity-1", first.patchId, inFlight!!),
        )

        val second = builder.build(entity("title" to "A", "note" to "n2"), scope, NOW)

        assertEquals(listOf("note"), second.ops.map { it.field })
    }

    @Test
    fun `a response for a superseded patch does not promote its state`() = runTest {
        val shadowDao = FakeSyncShadowDao()
        val builder = builder(shadowDao)

        val first = builder.build(entity("title" to "A", "note" to "n"), scope, NOW)
        builder.build(entity("title" to "B", "note" to "n"), scope, NOW) // supersedes `first`

        val inFlight = assertNotNull(
            shadowDao.state("owner-1", "profile-a", DocType.Task.key, "entity-1"),
        ).inFlightJson
        val confirmedBefore = jsonTitle(shadowDao, "owner-1", "profile-a")

        // The server's answer for patch 1 arrives late. Promoting it would roll the
        // shadow back to "A", and the next edit would be diffed against a state the
        // server does not have — a silent loss of the "B" write with nothing to show it.
        assertEquals(
            0,
            shadowDao.confirm("owner-1", "profile-a", DocType.Task.key, "entity-1", first.patchId, inFlight!!),
            "the promote guard must reject a patch that no longer owns the marker",
        )
        assertEquals(
            confirmedBefore,
            jsonTitle(shadowDao, "owner-1", "profile-a"),
            "the confirmed state must be untouched by a stale response",
        )
        assertEquals("B", inFlightTitle(shadowDao), "the newer patch still owns the in-flight state")
    }

    @Test
    fun `releasing a patch leaves the confirmed state where the server really is`() = runTest {
        val shadowDao = FakeSyncShadowDao()
        val builder = builder(shadowDao)

        val first = builder.build(entity("title" to "A", "note" to "n"), scope, NOW)
        assertEquals(1, shadowDao.release("owner-1", "profile-a", DocType.Task.key, "entity-1", first.patchId))

        val shadow = assertNotNull(shadowDao.state("owner-1", "profile-a", DocType.Task.key, "entity-1"))
        assertEquals(null, shadow.inFlightPatchId)
        // The confirmed state is still the empty object, because the server has never
        // confirmed anything for this entity. The next edit therefore re-diffs against
        // nothing and re-sends every field — which is the intent, and the alternative
        // (seeding confirmed with the local state) uploads an entity with every field
        // the user did not touch missing.
        assertEquals(null, jsonTitle(shadowDao, "owner-1", "profile-a"), "never-confirmed state stays empty")
    }

    @Test
    fun `a second entity does not read the first one's shadow`() = runTest {
        val shadowDao = FakeSyncShadowDao()
        val builder = builder(shadowDao)

        builder.build(entity("title" to "A", id = "entity-1"), scope, NOW)
        val other = builder.build(entity("title" to "A", id = "entity-2"), scope, NOW)

        assertEquals(
            setOf("title"),
            other.ops.map { it.field }.toSet(),
            "an entity with no shadow is a first upload, whatever its neighbour did",
        )
    }

    @Test
    fun `another scope does not read this scope's shadow`() = runTest {
        val shadowDao = FakeSyncShadowDao()
        val builder = builder(shadowDao)

        builder.build(entity("title" to "A", "note" to "n"), scope, NOW)
        val other = builder.build(
            entity("title" to "A", "note" to "n"),
            SyncScope("owner-1", "profile-b"),
            NOW,
        )

        assertEquals(
            setOf("title", "note"),
            other.ops.map { it.field }.toSet(),
            "profile B has never synced profile A's task; sending a diff would tell the " +
                "server it already has it",
        )
    }

    // ─── The clock ────────────────────────────────────────────────────────────

    @Test
    fun `each patch carries a logical clock, and consecutive patches are ordered`() = runTest {
        val builder = builder(FakeSyncShadowDao())

        val first = builder.build(entity("title" to "A"), scope, NOW)
        val second = builder.build(entity("title" to "B"), scope, NOW)

        assertNotNull(first.hlc)
        assertNotNull(second.hlc)
        assertTrue(
            second.hlc!! > first.hlc!!,
            "two patches authored in the same millisecond still need a total order, " +
                "which is the counter half of the hybrid clock",
        )
    }

    @Test
    fun `the patch carries no row checksum`() = runTest {
        val serialized = StableJson.encodeToString(
            DeltaPatch.serializer(),
            builder(FakeSyncShadowDao()).build(entity("title" to "A"), scope, NOW),
        )

        assertTrue(
            "shadowChecksum" !in serialized,
            "a whole-row checksum resolves a whole-row conflict, which is the contract " +
                "REQ-OS-003 replaces",
        )
    }

    private fun jsonTitle(shadowDao: FakeSyncShadowDao, ownerId: String, profileId: String): String? =
        titleOf(shadowDao.state(ownerId, profileId, DocType.Task.key, "entity-1")?.confirmedJson)

    private fun inFlightTitle(shadowDao: FakeSyncShadowDao): String? =
        titleOf(shadowDao.state("owner-1", "profile-a", DocType.Task.key, "entity-1")?.inFlightJson)

    private fun titleOf(json: String?): String? {
        if (json == null) return null
        val obj = StableJson.decodeFromString(JsonObject.serializer(), json)
        return (obj["title"] as? JsonPrimitive)?.content
    }

    private companion object {
        const val NOW = 1_760_000_000_000L
    }
}
