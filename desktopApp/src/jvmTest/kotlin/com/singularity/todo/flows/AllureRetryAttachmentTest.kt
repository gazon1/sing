package com.singularity.todo.flows

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.step
import io.qameta.allure.Allure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The two retry rules of the Allure attachment, checked on the real harness.
 *
 * A retry that passes must leave the green testcase clean; exhausted retries must
 * attach the bundle of the *last* failed attempt and nothing earlier. Both are
 * invisible to a test that only looks at its own result while it runs, so the
 * assertion lives in `@AfterAll`: `allure-junit-platform` writes the result file
 * when the test finishes, after the body has returned.
 */
@Tag("slow")
@Tag("slow:compose")
@OptIn(ExperimentalTestApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AllureRetryAttachmentTest {
    private lateinit var passingCaseId: String
    private lateinit var exhaustedCaseId: String

    @Test
    fun `a passing retry leaves no failure attachment`() {
        passingCaseId = currentTestCaseId()

        var attempts = 0
        withAttempts(2) {
            runDesktopAppTest {
                attempts += 1
                if (attempts == 1) fail("intentional first-attempt failure")
            }
        }

        assertEquals(2, attempts, "the harness did not retry, so this test proved nothing")
    }

    @Test
    fun `exhausted retries attach the final failure bundle`() {
        exhaustedCaseId = currentTestCaseId()

        var attempts = 0
        val failure =
            withAttempts(2) {
                assertFailsWith<AssertionError> {
                    runDesktopAppTest {
                        attempts += 1
                        // The recorder keeps the whole history, so the final attempt's
                        // steps.txt is the only one that names attempt-2.
                        step("attempt-$attempts") {}
                        fail("intentional failure from attempt $attempts")
                    }
                }
            }

        assertEquals(2, attempts, "the harness ran $attempts attempt(s), not 2")
        assertContains(
            failure.message.orEmpty(),
            "attempt 2",
            message = "the harness reported the wrong attempt",
        )
    }

    @AfterAll
    fun `the results follow both retry rules`() {
        val passing = resultJson(passingCaseId)
        assertTrue(
            attachmentsOf(passing).isEmpty(),
            "a test that passed on retry carried failure attachments: ${attachmentsOf(passing)}",
        )

        val attachments = attachmentsOf(resultJson(exhaustedCaseId))
        assertTrue(attachments.isNotEmpty(), "exhausted retries attached nothing")
        val names = attachments.map { it["name"]?.jsonPrimitive?.content }
        assertEquals(names.size, names.distinct().size, "an artifact was attached twice: $names")

        val steps = attachments.single { it["name"]?.jsonPrimitive?.content == "steps.txt" }
        val content = resultsDir().resolve(steps["source"]?.jsonPrimitive?.content.orEmpty()).readText()
        assertContains(
            content,
            "attempt-2",
            message = "the attached steps came from an earlier attempt:\n$content",
        )
    }

    private fun currentTestCaseId(): String =
        Allure.getLifecycle().getCurrentTestCaseOrStep().orElseThrow {
            IllegalStateException("no Allure testcase is running, so this test proves nothing")
        }

    private fun resultJson(caseId: String): JsonObject {
        val result = resultsDir().resolve("$caseId-result.json")
        assertTrue(result.isFile, "the Allure result of a test was not written: $result")
        return Json.parseToJsonElement(result.readText()).jsonObject
    }

    private fun attachmentsOf(result: JsonObject): List<JsonObject> =
        result["attachments"]?.jsonArray.orEmpty().map { it.jsonObject }

    private fun <T> withAttempts(
        count: Int,
        block: () -> T,
    ): T {
        val previous = System.getProperty("retry.maxAttempts")
        System.setProperty("retry.maxAttempts", count.toString())
        return try {
            block()
        } finally {
            if (previous == null) {
                System.clearProperty("retry.maxAttempts")
            } else {
                System.setProperty("retry.maxAttempts", previous)
            }
        }
    }

    private fun resultsDir(): File = File(System.getProperty("allure.results.directory") ?: "build/allure-results")
}
