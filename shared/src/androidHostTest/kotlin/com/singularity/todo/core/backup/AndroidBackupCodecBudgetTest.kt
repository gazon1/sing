package com.singularity.todo.core.backup

import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.test.fakes.MapFileSystem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The phone's decoder reads under a budget, like the desktop's.
 *
 * ## What is at stake
 *
 * `AndroidBackupCodec` read every entry of an archive into memory whole, with
 * no limit on how many entries there were or how large they became. A zip says
 * how big its entries are **on disk**, not how big they become: an archive of a
 * few kilobytes can expand to gigabytes. On the JVM the decoder had refused
 * that since the beginning; on Android it had not, so the half of the app that
 * runs on the phone was the half a small file could kill — by exhausting the
 * heap, with no exception and no error state.
 *
 * The budget now lives in `ArchiveBudget`, which both decoders read under, and
 * this class is the witness that the Android one actually does.
 *
 * ## Why a JUnit4 class
 *
 * `androidHostTest` runs Robolectric, which is a JUnit4 runner, discovered
 * through the Vintage engine. JUnit4 has no `@Tag`, so this class carries no
 * `fast`/`slow` tag — the same arrangement as the other classes in this source
 * set. Nothing here touches the Android framework either: the decoder is
 * `java.util.zip` and the project's own ports, so no Robolectric runner is
 * needed.
 *
 * ## Why the test methods return nothing
 *
 * JUnit4 requires a void test method. A method written as `fun x() = runTest { }`
 * returns a `TestResult`, and such a class is **not discovered**: it compiles,
 * the task is green, and the class never runs. `runTest` blocks until the body
 * is over, so calling it from a method that returns nothing is both correct and
 * the only shape Vintage accepts.
 */
class AndroidBackupCodecBudgetTest {

    /**
     * An archive that expands past the byte budget is refused.
     *
     * The bytes are zeros on purpose: they compress to almost nothing, so the
     * archive on disk is tiny while the archive in memory is enormous. That is
     * the shape of the file that used to kill the decoder.
     */
    @Test
    fun anArchiveThatExpandsPastTheByteBudgetIsRefused() {
        runTest {
            val fs = MapFileSystem()
            val codec = AndroidBackupCodec()
            fs.writeBytes("/tmp/big.zip", zipWithEntry(BackupFormat.ENTRY_PAYLOAD, oversizedEntry()))

            val failure = runCatchingCancellable { codec.import("/tmp/big.zip", fs).getOrThrow() }
                .exceptionOrNull()

            assertTrue(
                "the decoder read an archive past its budget instead of refusing it: $failure",
                failure is IllegalStateException,
            )
        }
    }

    /**
     * An archive with more entries than the budget allows is refused.
     *
     * The other half of the budget: a zip can be small in bytes and large in
     * entries, and an unbounded loop over entries is its own kind of exhaustion.
     */
    @Test
    fun anArchiveWithMoreEntriesThanTheBudgetIsRefused() {
        runTest {
            val fs = MapFileSystem()
            val codec = AndroidBackupCodec()
            fs.writeBytes("/tmp/many.zip", zipWithEntries(ArchiveBudget.MAX_ENTRIES + 1))

            val failure = runCatchingCancellable { codec.import("/tmp/many.zip", fs).getOrThrow() }
                .exceptionOrNull()

            assertTrue(
                "the decoder read more entries than its budget allows instead of refusing it: $failure",
                failure is IllegalStateException,
            )
        }
    }

    /** A zip holding one entry whose content is [content]. */
    private fun zipWithEntry(name: String, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry(name))
            zos.write(content)
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    /** A zip holding [count] empty entries. */
    private fun zipWithEntries(count: Int): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            repeat(count) { index ->
                zos.putNextEntry(ZipEntry("entry-$index"))
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** One byte more than the budget allows. */
    private fun oversizedEntry(): ByteArray =
        ByteArray((ArchiveBudget.MAX_TOTAL_BYTES + 1).toInt())
}
