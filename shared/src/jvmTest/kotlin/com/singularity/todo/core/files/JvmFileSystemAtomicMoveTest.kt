package com.singularity.todo.core.files

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.FileSystemException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The move is one rename, not an unlink followed by a rename.
 *
 * ## Why this test is here
 *
 * `Files.move(source, target, REPLACE_EXISTING)` is *not* an atomic replace:
 * the platform unlinks the target first and only then renames the source onto
 * it, so between the two steps the target does not exist. The backup's writes
 * are atomic only if the move that publishes them is — a reader that looks for
 * the change file in that window finds nothing and treats a good backup as a
 * broken one.
 *
 * `ATOMIC_MOVE` is what turns the move into a single POSIX `rename`, which
 * replaces the target in one step and never leaves it missing.
 *
 * ## How the atomicity is observed
 *
 * The window itself cannot be watched from a test — it is closed too fast. What
 * *can* be watched is what the platform does with the target when the rename
 * cannot happen at all: moving a directory onto an existing file.
 *
 * - Without `ATOMIC_MOVE` the platform deletes the target first and only then
 *   discovers that a directory cannot take a file's place — the file is gone
 *   and the target is a directory.
 * - With `ATOMIC_MOVE` the rename is a single step, it fails, and the target is
 *   still the file it was.
 *
 * The target surviving the failed move is the observable difference between the
 * two implementations, and it is the same difference that closes the window in
 * the case that matters.
 */
@Tag("slow")
@Tag("slow:data")
class JvmFileSystemAtomicMoveTest {
    private val tempDirLazy = lazy { Files.createTempDirectory("singularity-atomic-move-").toFile() }
    private val tempDir: File by tempDirLazy

    @AfterTest
    fun tearDown() {
        if (tempDirLazy.isInitialized()) tempDir.deleteRecursively()
    }

    @Test
    fun `a move that cannot replace the target leaves the target alone`() =
        runTest {
            val target = File(tempDir, "target.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val source = File(tempDir, "source.dir").apply { mkdirs() }

            assertFailsWith<FileSystemException> {
                JvmFileSystem().move(source.absolutePath, target.absolutePath)
            }

            assertTrue(target.exists(), "the target must survive a move that could not replace it")
            assertContentEquals(byteArrayOf(1, 2, 3), target.readBytes(), "the target must keep its bytes")
        }
}
