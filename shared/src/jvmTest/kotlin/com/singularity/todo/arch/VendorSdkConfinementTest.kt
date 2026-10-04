package com.singularity.todo.arch

import java.io.File
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Supabase SDK appears in the transport and authentication seams, and nowhere else.
 *
 * ## What the rule is protecting
 *
 * A vendor SDK is not a utility. `SupabaseClient` is a session, an HTTP connection
 * pool, a retry policy, a logger and a coroutine scope that all arrive together, and
 * every one of them is reasonable to reach for. The erosion does not happen in one
 * step: a repository takes the client "just to read the current session", a
 * ViewModel constructs one to fire a single request, and a feature starts
 * serialising its domain types with the vendor's serialiser instead of the
 * project's. Each step compiles, each looks local, and by the end the vendor's
 * types are in the domain model and a version bump is a migration.
 *
 * The boundary is drawn at **two files** because the SDK is needed in exactly two
 * places: to own a client ([SupabaseClientProvider]) and to speak PostgREST
 * ([PostgrestSyncRpc]). Everything else the SDK offers — auth calls, the session
 * flow, the rest of the plugin surface — is reached through a project interface, so
 * it can be replaced or tested without a network.
 *
 * ## Why this is a scan
 *
 * Nothing in the type system forbids an import, and an architecture rule that
 * cannot fail is not a rule. This reads every production source file, finds the
 * vendor's package prefix, and fails on any file outside the allowlist. It is a
 * scan rather than a compile error because Kotlin cannot express "this package may
 * only be imported from these two files" — and the violation is a line that
 * compiles perfectly.
 *
 * ## The positive control
 *
 * This is a regex over source text. A regex that silently stops matching reports
 * success having checked nothing, which is the specific failure this project has
 * already paid for twice — a gate that was green and was measuring nothing. The
 * last test here feeds the rule a sample that *does* import the vendor and asserts
 * that it is caught. If someone tightens the pattern into uselessness, that test
 * fails.
 */
@Tag("fast")
class VendorSdkConfinementTest {

    private fun productionSources(): List<File> {
        val root = System.getProperty("commonMain.root")
            ?: error(
                "commonMain.root system property is not set — " +
                    "see the jvmTest task config in shared/build.gradle.kts",
            )
        return File(root).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
    }

    @Test
    fun `only the transport and auth seams import the supabase sdk`() {
        val offenders = productionSources()
            .filter { file -> file.readText().contains(VENDOR_IMPORT) }
            .filterNot { file -> ALLOWED_SUFFIXES.any { file.path.endsWith(it) } }
            .map { it.name }

        assertEquals(
            emptyList(),
            offenders,
            "these files import the Supabase SDK but are not a declared seam. The SDK " +
                "carries a session, a connection pool and a serialiser, and each file that " +
                "reaches for one is a step toward having its types in the domain model. " +
                "Go through SyncRpc, SyncApiClient, AuthRepository or SessionStore instead — " +
                offenders.joinToString(),
        )
    }

    @Test
    fun `the two declared seams still exist and still import the sdk`() {
        // Without this, deleting a seam file would make the rule above vacuously
        // true: no file would be an offender because nothing would import anything.
        // The rule would keep passing while measuring nothing at all.
        for (suffix in ALLOWED_SUFFIXES) {
            val matches = productionSources().filter { it.path.endsWith(suffix) }
            assertTrue(
                matches.isNotEmpty(),
                "the seam $suffix does not exist, so the confinement rule is vacuous",
            )
            assertTrue(
                matches.any { it.readText().contains(VENDOR_IMPORT) },
                "the seam $suffix no longer imports the vendor SDK, so it is no longer a seam",
            )
        }
    }

    @Test
    fun `the rule detects a vendor import outside a seam`() {
        // Positive control. See the class comment.
        val offender = """
            package com.singularity.todo.feature.tasks

            import io.github.jan.supabase.auth.Auth

            class Sneaky(private val client: io.github.jan.supabase.SupabaseClient)
        """.trimIndent()

        assertTrue(
            offender.contains(VENDOR_IMPORT),
            "the rule must still recognise a vendor import; if it cannot, it cannot fail",
        )
    }

    private companion object {
        /**
         * The prefix, without the trailing dot, so a fully-qualified reference in
         * code (`io.github.jan.supabase.SupabaseClient` as a type argument) is
         * caught as readily as an import line. An import-only scan would miss the
         * former, which is the same capability by another route.
         */
        const val VENDOR_IMPORT = "io.github.jan.supabase"

        val ALLOWED_SUFFIXES = listOf(
            "auth/SupabaseClientProvider.kt",
            "sync/PostgrestSyncRpc.kt",
        )
    }
}
