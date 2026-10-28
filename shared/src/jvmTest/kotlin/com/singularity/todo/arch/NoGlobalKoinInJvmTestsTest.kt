package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

@Tag("fast")
class NoGlobalKoinInJvmTestsTest {
    @Test
    fun `jvmTest classes do not touch the global Koin context`() {
        val commonMain = System.getProperty("commonMain.root")
            ?: error("commonMain.root system property is not set")
        val moduleDir = Path.of(commonMain).toAbsolutePath().normalize().parent?.parent?.parent
            ?: error("Could not derive module directory")
        val root = moduleDir.resolve("src/jvmTest/kotlin")
        val offenders = mutableListOf<String>()
        if (root.exists()) {
            Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .forEach { file ->
                        if (file.fileName.toString() == "NoGlobalKoinInJvmTestsTest.kt") return@forEach
                        val code = file.readText()
                            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
                            .replace(Regex("//[^\\n]*"), "")
                        if (code.contains("startKoin") || code.contains("GlobalContext.get")) {
                            offenders += file.toString()
                        }
                    }
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "These jvmTest files touch the global Koin context, which races with " +
                "KoinIsolationExtension under parallel execution: $offenders",
        )
    }
}
