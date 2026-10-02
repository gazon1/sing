package com.singularity.todo.detekt

import dev.detekt.test.FakeLanguageVersionSettings
import dev.detekt.test.TestConfig
import dev.detekt.test.utils.compileContentForTest
import org.jetbrains.kotlin.config.ExplicitApiMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoDirectDispatchersRuleTest {

    private val rule = NoDirectDispatchersRule(TestConfig())
    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    @Test
    fun `Dispatchers_IO is flagged`() {
        val code = """
            package com.singularity.todo.core.log

            fun writeLog() {
                withContext(Dispatchers.IO) { }
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("Dispatchers.IO"))
    }

    @Test
    fun `Dispatchers_Default is flagged`() {
        val code = """
            package com.singularity.todo.core.log

            fun compute() {
                withContext(Dispatchers.Default) { }
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("Dispatchers.Default"))
    }

    @Test
    fun `Dispatchers_IO_limitedParallelism is not flagged`() {
        val code = """
            package com.singularity.todo.core.log

            private val LogDispatcher = Dispatchers.IO.limitedParallelism(1)
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(0, findings.size)
    }

    @Test
    fun `Dispatchers_IO in FileLogWriter is whitelisted`() {
        val code = """
            package com.singularity.todo.core.log

            class FileLogWriter {
                private val io = Dispatchers.IO.limitedParallelism(1)
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com/singularity/todo/core/log/FileLogWriter.kt")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(0, findings.size)
    }
}
