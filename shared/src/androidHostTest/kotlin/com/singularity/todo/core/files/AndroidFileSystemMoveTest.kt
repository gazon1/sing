package com.singularity.todo.core.files

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.FileSystemException

/**
 * The phone's move replaces what was at the target.
 *
 * ## Why this test is here
 *
 * `AndroidFileSystem.move` had no test at all. The contract test that pins the
 * `FileSystem` port lives in `jvmTest` and reaches the desktop's implementation
 * only, so the half of the app that runs on the phone could move a file by a
 * rename that leaves the old name behind — and nothing would have said so.
 *
 * The move is what makes the backup's writes atomic: the archive and the
 * change are written under a temporary name and moved over the real one. So
 * "the target is replaced and the old name is gone" is the whole contract.
 *
 * ## Why a JUnit4 class
 *
 * Robolectric is a JUnit4 runner and the class is discovered through the
 * Vintage engine. JUnit4 test methods return nothing, so `runTest` is called
 * from a method that returns nothing — a method written as `fun x() = runTest { }`
 * returns a `TestResult` and is never discovered.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidFileSystemMoveTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun aMoveReplacesWhatWasAtTheTarget() {
        runTest {
            val fs = AndroidFileSystem(context)
            val dir = File(context.filesDir, "move-test").apply { mkdirs() }
            val from = File(dir, "from.bin").apply { writeBytes(byteArrayOf(1)) }
            val to = File(dir, "to.bin").apply { writeBytes(byteArrayOf(2)) }

            fs.move(from.absolutePath, to.absolutePath)

            assertTrue("the moved file must be at the target", to.exists())
            assertFalse("the old name must be gone", from.exists())
            assertTrue(
                "the moved bytes must win, not the ones that were there",
                to.readBytes().contentEquals(byteArrayOf(1)),
            )
        }
    }

    /**
     * The phone's move is one rename, not an unlink followed by a rename.
     *
     * `Files.move(..., REPLACE_EXISTING)` alone unlinks the target first, so
     * between the two steps the target does not exist — and a backup reader that
     * looks for the change file in that window finds nothing. `ATOMIC_MOVE` is
     * what makes it a single POSIX `rename`; the option lives in the Android
     * implementation, so it is pinned here.
     *
     * Robolectric runs the host JDK's `java.nio.file`, not Android's provider,
     * so what is observed here is the *option list* the Android implementation
     * passes. The provider's own behaviour is established from its source: in
     * AOSP's libcore (`ojluni/src/main/java/sun/nio/fs/UnixCopyFile.java`) the
     * `ATOMIC_MOVE` branch renames the source onto the target directly, while
     * the branch without it unlinks the target first and only then renames —
     * the same two branches the JDK has.
     *
     * The observable difference: when the rename cannot happen at all, the
     * target survives. Without `ATOMIC_MOVE` the platform deletes the target
     * first and only then fails.
     */
    @Test
    fun aMoveThatCannotReplaceTheTargetLeavesTheTargetAlone() {
        runTest {
            val fs = AndroidFileSystem(context)
            val dir = File(context.filesDir, "move-test").apply { mkdirs() }
            val to = File(dir, "to.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val from = File(dir, "from.dir").apply { mkdirs() }

            try {
                fs.move(from.absolutePath, to.absolutePath)
                throw AssertionError("moving a directory over a file must not succeed")
            } catch (expected: FileSystemException) {
                // the rename itself is what failed
            }

            assertTrue("the target must survive a move that could not replace it", to.exists())
            assertTrue(
                "the target must keep its bytes",
                to.readBytes().contentEquals(byteArrayOf(1, 2, 3)),
            )
        }
    }
}
