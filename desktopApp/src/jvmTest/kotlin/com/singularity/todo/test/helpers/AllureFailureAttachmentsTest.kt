package com.singularity.todo.test.helpers

import io.qameta.allure.AllureLifecycle
import io.qameta.allure.FileSystemResultsWriter
import io.qameta.allure.model.TestResult
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The bundle reaches Allure as the files that exist, under their own media types,
 * and they belong to the testcase the lifecycle is running.
 *
 * The lifecycle is passed in rather than taken from `Allure.getLifecycle()`, so the
 * contract is checked against a writer in a temp directory and not against whatever
 * the JUnit adapter is doing in the same JVM.
 */
@Tag("fast")
class AllureFailureAttachmentsTest {
    @Test
    fun `attaches existing artifacts to the running testcase`(
        @TempDir dir: Path,
    ) {
        val results = dir.resolve("allure-results")
        val lifecycle = AllureLifecycle(FileSystemResultsWriter(results))
        val bundle = FailureBundle("AllureFailureAttachmentsTest", 3, dir.resolve("attempt-3").toFile())
        bundle.outputDir.mkdirs()
        bundle.screenshotFile.writeBytes(byteArrayOf(1, 2, 3))
        bundle.stepsFile.writeText("1. open the agenda")

        lifecycle.scheduleTestCase(TestResult().setUuid("testcase"))
        lifecycle.startTestCase("testcase")
        bundle.attachToAllure(lifecycle)
        lifecycle.stopTestCase("testcase")
        lifecycle.writeTestCase("testcase")

        val json = resultJson(results).readText()
        assertContains(json, "screenshot.png")
        assertContains(json, "image/png")
        assertContains(json, "steps.txt")
        assertContains(json, "text/plain")
        assertFalse(json.contains("db-state.txt"), "a missing artifact was attached: $json")
        assertEquals(
            2,
            results.toFile().listFiles()!!.count { file -> file.name.contains("-attachment.") },
            "only the two existing artifacts should be attached",
        )
    }

    private fun resultJson(results: Path) =
        results.toFile().listFiles { file -> file.name.endsWith("-result.json") }!!.single()
}
