package com.singularity.todo.importer

import com.singularity.todo.core.backup.ArchiveBudget
import com.singularity.todo.core.backup.BackupDomain
import com.singularity.todo.core.backup.BackupManifest
import com.singularity.todo.core.backup.BackupPayload
import com.singularity.todo.core.backup.JvmBackupCodec
import com.singularity.todo.core.files.JvmFileSystem
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.serialization.StableJson
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every named profile is a promise that its dataset is usable, not just named.
 *
 * The profiles first shipped with `checklistItems = tasks * 2` and everything else
 * scaled from the task count. That made 50k and 100k exceed
 * [ArchiveBudget.MAX_TOTAL_BYTES] on the first run, so the two names a caller
 * reaches for at scale failed before writing an archive — the issue requires that
 * "actual support for each profile is decided by passing the archive budget and an
 * import check, not by the record count alone".
 *
 * These tests are that check. They cost a few seconds at 100k and are the reason a
 * later composition change cannot silently break a profile.
 */
class SyntheticBackupProfileBudgetTest {
    @Test
    fun `every profile fits the backup archive budget`() {
        for (profile in GenerateProfile.entries) {
            val payloadBytes = payloadBytesFor(profile)
            assertTrue(
                payloadBytes.size.toLong() <= ArchiveBudget.MAX_TOTAL_BYTES,
                "${profile.cliName} profile serializes to ${payloadBytes.size} bytes, " +
                    "over the ${ArchiveBudget.MAX_TOTAL_BYTES}-byte archive budget. " +
                    "Reduce the composition in SyntheticBackupGenerator.PROFILE_COMPOSITIONS.",
            )
            assertTrue(
                payloadBytes.size > 0,
                "${profile.cliName} produced an empty payload",
            )
        }
    }

    /**
     * A generated archive has to survive the codec and the manifest validator — the
     * same path `generate` runs before it reports success and `verify` runs later.
     *
     * The 100k profile is the one this exists for: it spends 96% of the budget, so a
     * growth of the task DTO can push the *archive* over a limit while the payload
     * alone still looks fine.
     */
    @Test
    fun `every profile round-trips through the codec`() =
        runTest {
            val fs = JvmFileSystem()
            val codec = JvmBackupCodec()
            for (profile in GenerateProfile.entries) {
                val payload = SyntheticBackupGenerator.generate(paramsFor(profile)).payload
                SyntheticBackupValidator.validate(payload)

                val payloadBytes = payloadBytesFor(profile)
                val manifest =
                    BackupDomain.buildManifest(
                        appVersion = "test",
                        nowEpochMillis = 0L,
                        userId = UserId.anonymous,
                        payloadBytes = payloadBytes,
                        counts = BackupDomain.countsOf(payload),
                    )
                val manifestBytes =
                    StableJson.encodeToString(BackupManifest.serializer(), manifest).encodeToByteArray()
                SyntheticBackupValidator.ensureWithinArchiveBudget(payloadBytes, manifestBytes)

                val tmp = Files.createTempFile("synthetic-${profile.cliName}", ".zip")
                try {
                    codec.export(manifestBytes, payloadBytes, emptyList(), tmp.toString(), fs).getOrThrow()
                    val read = codec.import(tmp.toString(), fs).getOrThrow()
                    val decodedManifest =
                        StableJson.decodeFromString(BackupManifest.serializer(), read.manifestBytes.decodeToString())
                    val decodedPayload =
                        StableJson.decodeFromString(BackupPayload.serializer(), read.payloadBytes.decodeToString())

                    BackupDomain.validateManifest(decodedManifest, read.payloadBytes).getOrThrow()
                    SyntheticBackupValidator.validate(decodedPayload)
                    assertEquals(payload, decodedPayload, "${profile.cliName} did not survive the round-trip")
                } finally {
                    Files.deleteIfExists(tmp)
                }
            }
        }

    private fun paramsFor(profile: GenerateProfile): GenerateParams =
        SyntheticBackupGenerator.defaultParams(profile, seed = MEASUREMENT_SEED)

    private fun payloadBytesFor(profile: GenerateProfile): ByteArray {
        val payload = SyntheticBackupGenerator.generate(paramsFor(profile)).payload
        return StableJson.encodeToString(BackupPayload.serializer(), payload).encodeToByteArray()
    }

    private companion object {
        /** The seed the compositions were measured with. */
        const val MEASUREMENT_SEED = 42L
    }
}
