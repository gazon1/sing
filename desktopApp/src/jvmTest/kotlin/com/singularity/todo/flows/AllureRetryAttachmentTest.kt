package com.singularity.todo.flows

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.test.helpers.runDesktopAppTest
import io.qameta.allure.Allure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A retry that passes must not leave a failure attachment on the green testcase.
 *
 * This is the only check that catches the attach call moving out of the harness's
 * final-attempt branch: a passing test says nothing about what it wrote.
 *
 * The assertion lives in `@AfterAll` because the Allure result file is written when
 * the test finishes, after the test body has returned.
 */
@Tag("slow")
@Tag("slow:compose")
@OptIn(ExperimentalTestApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AllureRetryAttachmentTest {
    private var attempts = 0
    private lateinit var testCaseId: String

    @Test
    fun `a passing retry leaves no failure attachment`() {
        testCaseId =
            Allure.getLifecycle().getCurrentTestCaseOrStep().orElseThrow {
                IllegalStateException("no Allure testcase is running, so this test proves nothing")
            }

        val previous = System.getProperty("retry.maxAttempts")
        System.setProperty("retry.maxAttempts", "2")
        try {
            runDesktopAppTest {
                attempts += 1
                if (attempts == 1) fail("intentional first-attempt failure")
            }
        } finally {
            if (previous == null) {
                System.clearProperty("retry.maxAttempts")
            } else {
                System.setProperty("retry.maxAttempts", previous)
            }
        }

        assertEquals(2, attempts, "the harness did not retry, so this test proved nothing")
    }

    @AfterAll
    fun `the passing result carries no attachments`() {
        val result = resultsDir().resolve("$testCaseId-result.json")
        assertTrue(result.isFile, "the Allure result of this test was not written: $result")
        val attachments = Json.parseToJsonElement(result.readText()).jsonObject["attachments"]?.jsonArray
        assertTrue(
            attachments.isNullOrEmpty(),
            "a test that passed on retry carried failure attachments: $attachments",
        )
    }

    private fun resultsDir(): File = File(System.getProperty("allure.results.directory") ?: "build/allure-results")
}
