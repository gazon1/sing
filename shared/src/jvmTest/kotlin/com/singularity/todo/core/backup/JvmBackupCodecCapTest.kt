package com.singularity.todo.core.backup

import com.singularity.todo.test.fakes.MapFileSystem
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertFailsWith

@Tag("fast")
class JvmBackupCodecCapTest {
    @Test
    fun oversizedArchiveThrows() = runTest {
        val fs = MapFileSystem()
        val codec = JvmBackupCodec()
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry(BackupFormat.ENTRY_MANIFEST))
            zos.write("{}".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry(BackupFormat.ENTRY_PAYLOAD))
            zos.write(ByteArray(65 * 1024 * 1024))
            zos.closeEntry()
        }
        fs.writeBytes("/tmp/big.zip", baos.toByteArray())
        assertFailsWith<IllegalStateException> {
            codec.import("/tmp/big.zip", fs).getOrThrow()
        }
    }
}