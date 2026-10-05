package com.singularity.todo.feature.calendar_sync.domain.logic

import com.singularity.todo.feature.calendar_sync.domain.model.CalendarSyncStatus
import com.singularity.todo.feature.calendar_sync.error.FailureType
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The codec is a total function in both directions, so the properties that matter are
 * round-tripping and never throwing on garbage — the previous implementation failed both
 * in a way that was invisible until the settings screen showed the wrong state.
 */
@Tag("fast")
class CalendarSyncStatusCodecTest {

    private val neverSynced = CalendarSyncStatus.Idle(null)

    @Test
    fun `every status round-trips through the store`() {
        val statuses = listOf(
            CalendarSyncStatus.Disabled,
            CalendarSyncStatus.Syncing,
            CalendarSyncStatus.Idle(null),
            CalendarSyncStatus.Idle(0L),
            CalendarSyncStatus.Idle(1_700_000_000_123L),
            CalendarSyncStatus.Failed("boom"),
            CalendarSyncStatus.Failed("boom", FailureType.Network),
            CalendarSyncStatus.Failed("revoked", FailureType.PermissionRevoked),
        )

        statuses.forEach { status ->
            val encoded = CalendarSyncStatusCodec.encode(status)
            assertEquals(
                status,
                CalendarSyncStatusCodec.decode(encoded, neverSynced),
                "round trip changed $status (encoded as \"$encoded\")",
            )
        }
    }

    /**
     * The bug that motivated this: the old reader's `else` branch ran
     * `removePrefix("Failed:")` on anything unrecognised, so the literal this same class
     * wrote for Idle came back as `Failed("Idle")`. Idle must be recoverable.
     */
    @Test
    fun `Idle is reconstructed, not misread as a failure`() {
        val encoded = CalendarSyncStatusCodec.encode(CalendarSyncStatus.Idle(1_700_000_000_000L))
        assertEquals(
            CalendarSyncStatus.Idle(1_700_000_000_000L),
            CalendarSyncStatusCodec.decode(encoded, neverSynced),
        )
    }

    /**
     * FailureType was never stored, so it always came back Unknown and the settings screen
     * could not offer the recovery action it already knew about.
     */
    @Test
    fun `FailureType survives the round trip`() {
        FailureType.entries.forEach { type ->
            val status = CalendarSyncStatus.Failed("some reason", type)
            val decoded = CalendarSyncStatusCodec.decode(CalendarSyncStatusCodec.encode(status), neverSynced)
            assertEquals(status, decoded)
            assertEquals(type, (decoded as CalendarSyncStatus.Failed).type)
        }
    }

    @Test
    fun `a reason containing the separator survives`() {
        val status = CalendarSyncStatus.Failed("http 401: token expired", FailureType.Network)
        val decoded = CalendarSyncStatusCodec.decode(CalendarSyncStatusCodec.encode(status), neverSynced)
        assertEquals(status, decoded)
    }

    @Test
    fun `a reason containing the escape character survives`() {
        val status = CalendarSyncStatus.Failed("""C:\path\to\thing""", FailureType.Transient)
        val decoded = CalendarSyncStatusCodec.decode(CalendarSyncStatusCodec.encode(status), neverSynced)
        assertEquals(status, decoded)
    }

    @Test
    fun `a reason that looks like another tag survives`() {
        // "Idle", "Syncing" and "Failed:x" were all mistaken for structure by the old reader.
        listOf("Idle", "Syncing", "Failed:real reason", "Disabled", "").forEach { reason ->
            val status = CalendarSyncStatus.Failed(reason, FailureType.Unknown)
            val decoded = CalendarSyncStatusCodec.decode(CalendarSyncStatusCodec.encode(status), neverSynced)
            assertEquals(status, decoded, "reason \"$reason\" did not survive")
        }
    }

    @Test
    fun `nothing written falls back to the supplied default`() {
        assertEquals(neverSynced, CalendarSyncStatusCodec.decode(null, neverSynced))
        assertEquals(neverSynced, CalendarSyncStatusCodec.decode("", neverSynced))
        assertEquals(neverSynced, CalendarSyncStatusCodec.decode("   ", neverSynced))
    }

    /**
     * A value written by a newer build, or corrupted on disk, must not throw on read — a
     * crash while observing a preference flow takes down the settings screen.
     */
    @Test
    fun `garbage decodes to a renderable status instead of throwing`() {
        listOf("Nonsense", "Failed", "Failed:", "Failed:reason:NoSuchType", "Idle:not-a-number", ":::").forEach { raw ->
            val decoded = CalendarSyncStatusCodec.decode(raw, neverSynced)
            assertEquals(true, decoded is CalendarSyncStatus, "\"$raw\" produced $decoded")
        }
    }

    @Test
    fun `an unknown FailureType name degrades to Unknown rather than failing`() {
        val decoded = CalendarSyncStatusCodec.decode("Failed:why:Martian", neverSynced)
        assertEquals(CalendarSyncStatus.Failed("why", FailureType.Unknown), decoded)
    }
}
