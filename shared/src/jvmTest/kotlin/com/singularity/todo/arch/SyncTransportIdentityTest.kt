package com.singularity.todo.arch

import java.io.File
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No transport method takes an owner id from the caller.
 *
 * ## The defect this exists to prevent
 *
 * `SyncApiClient` had `getEventsSince(userId, …)` and `testConnection(userId)`. The
 * client supplied the value and the server would have believed it: a client that
 * passes another account's id reads that account's events and writes into its data,
 * in a request that is perfectly well-formed and indistinguishable from a
 * legitimate one. That is an authorisation bypass, not a bug — and the fix is
 * structural, because the id must come from the session the server authenticated.
 *
 * The parameter is gone from the interface. This gate is what stops it coming back,
 * because "the caller passes the user id" is a perfectly reasonable-looking line of
 * code, and the review that would catch it is exactly the review nobody reads twice.
 *
 * ## Why the scan is a scan and not a compile error
 *
 * Kotlin cannot express "this interface has no such parameter", and the change is
 * invisible at every call site once made: nothing fails, nothing warns, and the
 * property holds only as long as someone remembers it. A gate that reads the
 * declaration is the cheapest thing that keeps holding.
 *
 * It checks the *name* of the parameter, not its type. A `String` named `userId`,
 * `ownerId` or `profileId` is the thing to forbid; a future method legitimately
 * wanting a `String` for another reason must not trip the rule silently, so the
 * failure message names the exact parameter.
 */
@Tag("fast")
class SyncTransportIdentityTest {

    private fun transportFile(): File {
        val root = System.getProperty("commonMain.root")
            ?: error(
                "commonMain.root system property is not set — " +
                    "see the jvmTest task config in shared/build.gradle.kts",
            )
        val file = File(File(root, "com/singularity/todo/core/sync"), "SyncApi.kt")
        assertTrue(file.exists(), "sync transport interface not found at $file")
        return file
    }

    /** `fun name(a: T, b: U)` parameter names, in order. */
    private val functionSignature = Regex(
        """fun\s+(\w+)\s*\(([^)]*)\)\s*[:{]""",
        RegexOption.DOT_MATCHES_ALL,
    )

    @Test
    fun `no sync transport method takes a caller-supplied owner identity`() {
        val source = transportFile().readText()
        val offenders = mutableListOf<String>()

        for (match in functionSignature.findAll(source)) {
            val name = match.groupValues[1]
            val params = match.groupValues[2]
            if (params.isBlank()) continue
            for (raw in params.split(',')) {
                val param = raw.substringBefore(':').trim().substringBefore('=').trim()
                if (param in OWNER_PARAM_NAMES) {
                    offenders += "$name(… $param …)"
                }
            }
        }

        assertEquals(
            emptyList(),
            offenders,
            "a transport method takes an owner identity from the caller. The server " +
                "must derive it from the session it authenticated: an argument the " +
                "client sets is an argument the client sets wrongly, and a wrong " +
                "value here is an authorisation bypass — " + offenders.joinToString(),
        )
    }

    @Test
    fun `the rule itself still detects the parameter it forbids`() {
        // Positive control. A gate like this one is a regex; if the regex silently
        // stops matching, the test goes green having checked nothing, which is the
        // failure mode this project has already paid for twice.
        val sample = """
            interface Sample {
                suspend fun getEventsSince(userId: String, sinceLsn: Long): List<String>
                suspend fun getConfig(name: String): String?
            }
        """.trimIndent()

        val found = functionSignature.findAll(sample)
            .flatMap { match ->
                match.groupValues[2].split(',')
                    .map { it.substringBefore(':').trim().substringBefore('=').trim() }
            }
            .filter { it in OWNER_PARAM_NAMES }
            .toList()

        assertEquals(listOf("userId"), found)
    }

    private companion object {
        val OWNER_PARAM_NAMES = setOf("userId", "ownerId", "profileId", "accountId")
    }
}
