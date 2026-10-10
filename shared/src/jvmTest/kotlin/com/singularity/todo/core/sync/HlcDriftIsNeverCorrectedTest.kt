package com.singularity.todo.core.sync

import com.singularity.todo.core.serialization.StableJson
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * A clock that is behind, and never learns how far (#179, REQ-OS-019).
 *
 * ## What this test is for
 *
 * `HlcFactory.tock` — the half of a Hybrid Logical Clock that merges a clock received
 * from elsewhere — has no caller in production. `tick` takes
 * `max(nowMillis, lastPhysical)`, and `lastPhysical` only ever advances from local
 * events, so a device that receives a far-future clock never learns it and keeps emitting
 * patches the server orders as older than everything another device wrote. Under
 * per-field LWW, every field conflict it takes is lost silently.
 *
 * This is a **characterisation** test: it asserts what the code does today, including
 * the part that is wrong, and says so in its own failure message. It exists so the gap is
 * visible in the suite rather than in an issue — the first step #179 suggests — and so
 * that when the merge half is written, this assertion is the one that has to change.
 *
 * ## Why it is not written as a failing test
 *
 * A failing test cannot be merged, so it cannot be the thing that makes the defect
 * visible to the next person; it becomes a known-red file or an `@Ignore`, and both are
 * worse than an honest green assertion of today's behaviour. When the behaviour changes,
 * this file is deleted or inverted, and the commit that does it will say why.
 *
 * ## Why the pure functions and not the factory
 *
 * The behaviour lives in `Hlc.tick` and `Hlc.tock`, and the wiring is a missing call
 * site rather than a missing algorithm. Constructing an `HlcFactory` would add a session
 * store and a scope to demonstrate arithmetic, and the extra machinery would make the
 * next reader look for the answer somewhere it is not.
 *
 * ## What the fix needs, and is not decided here
 *
 * Two shapes, per #179: carry the merged HLC on `SyncEvent` (a wire change and a protocol
 * version bump), or treat the server's clock as an authority (an RPC the client does not
 * have). Either way there is a second, open question — what to do when a received clock is
 * far ahead of local: clamp, adopt, or record the drift and refuse to write. The last
 * test pins the shape of that question without choosing its answer.
 */
@Tag("fast")
class HlcDriftIsNeverCorrectedTest {

    private val start = 1_700_000_000_000L

    @Test
    fun `a device that receives a far-future clock keeps emitting patches behind it`() {
        // The device has been editing, so its own clock has a plausible history.
        var local = Hlc.tick(last = null, node = "self", nowMillis = start)
        repeat(3) { local = Hlc.tick(last = local, node = "self", nowMillis = start) }
        val beforeRemote = local.physical

        // Another device, online the whole time, writes with a clock a day ahead. Under
        // per-field LWW every one of those writes beats this device's next patch.
        val remote = Hlc("${beforeRemote + DAY}:0:other")

        // Nothing merges it. That is the finding: there is no call site.
        val emitted = Hlc.tick(last = local, node = "self", nowMillis = start)

        assertTrue(
            emitted.physical < remote.physical,
            "the device's next clock caught up to what it received. If this now fails, " +
                "something started merging received clocks — re-read #179 before changing " +
                "this test, because the merge shape and the drift bound are both undecided.",
        )
    }

    @Test
    fun `tock is the operation that would close the gap`() {
        // Pinned so that the test above is demonstrably demonstrating what it claims: if
        // `tock` ever stopped advancing, this would fail and the first test would be
        // proving nothing.
        val local = Hlc("$start:0:self")
        val remote = Hlc("${start + DAY}:0:other")

        val merged = Hlc.tock(local = local, remote = remote, node = "self", nowMillis = start)

        assertTrue(
            merged.physical >= remote.physical,
            "tock did not advance to the remote clock",
        )
    }

    @Test
    fun `sync-event now carries the hlc from the client that pushed it`() = runTest {
        // The second half of #179. Now that `SyncEvent` carries `hlc`, the pull loop
        // has something to hand `tock()` when it receives an event.
        val serialized = StableJson.encodeToString(
            SyncEvent(
                serverLsn = 1,
                entityId = "e1",
                entityType = DocType.Task,
                eventType = SyncEventType.UPDATED,
                createdAt = start,
                hlc = Hlc.of(start, 0, "remote-client"),
            ),
        )

        assertTrue(
            "hlc" in serialized,
            "SyncEvent no longer carries an HLC. If this fails, the field was removed; " +
                "re-read #179 before changing this test.",
        )
    }

    private companion object {
        val DAY = 24.hours.inWholeMilliseconds
    }
}
