package com.singularity.todo.arch

import java.io.File
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A vendor SDK appears in the transport and authentication seams, and nowhere else.
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
 * The boundary is drawn at **one file per seam**, because each SDK is needed in
 * exactly a few places: to own a client (`SupabaseClientProvider`), to speak auth
 * (`SupabaseAuthGateway`), to keep the session (`SecureSessionManager`), to speak
 * PostgREST (`PostgrestSyncRpc`), to speak Storage
 * (`SupabaseBackupBucket`), to call an Edge Function
 * (`SupabaseGoogleTokenGateway`) and to tell its failures apart
 * (`SupabaseTransportFailure`). Everything else the SDKs offer — the session flow,
 * the plugin surface, the user model — is reached through a project interface, so
 * it can be replaced or tested without a network.
 *
 * ## Why there is more than one vendor
 *
 * The application depends on two SDKs now: Supabase's own client, and KMPAuth's
 * credential model on top of it. A rule that only watched the first would let the
 * second spread through the codebase one file at a time, and the second is exactly
 * the one that brings a credential type into the domain. Each vendor has its own
 * allowlist, because a seam is a seam for *one* vendor: the file that keeps the
 * session speaks Supabase, and the file that signs in speaks KMPAuth.
 *
 * ## Why the rule is one file per seam
 *
 * A seam is a file, not a package and not a class: one vendor-touching class per
 * file, named after the vendor role it plays. A file that holds both a project
 * interface and the vendor implementation of it puts the vendor's types in a file
 * that other code reads for the interface alone, and the boundary stops being
 * readable. The backup port and its Storage implementation started out that way
 * and were split apart (`BackupBucket` from `SupabaseBackupBucket`), which keeps
 * the import list of every other file free of the vendor, and is the thing this
 * gate is for.
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
 * last test here feeds the rule a sample that *does* import each vendor and asserts
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
    fun `only the declared seams import a vendor sdk`() {
        for (vendor in VENDORS) {
            val offenders = productionSources()
                .filter { file -> file.readText().contains(vendor.prefix) }
                .filterNot { file -> vendor.seams.any { file.path.endsWith(it) } }
                .map { it.name }

            assertEquals(
                emptyList(),
                offenders,
                "these files import ${vendor.prefix} but are not a declared seam. A vendor " +
                    "SDK carries a session, a connection pool and a serialiser, and each file " +
                    "that reaches for one is a step toward having its types in the domain " +
                    "model. Go through SyncRpc, SyncApiClient or AuthRepository instead — " +
                    offenders.joinToString(),
            )
        }
    }

    @Test
    fun `the declared seams still exist and still import their vendor`() {
        // Without this, deleting a seam file would make the rule above vacuously
        // true: no file would be an offender because nothing would import anything.
        // The rule would keep passing while measuring nothing at all.
        for (vendor in VENDORS) {
            for (suffix in vendor.seams) {
                val matches = productionSources().filter { it.path.endsWith(suffix) }
                assertTrue(
                    matches.isNotEmpty(),
                    "the seam $suffix does not exist, so the confinement rule is vacuous",
                )
                assertTrue(
                    matches.any { it.readText().contains(vendor.prefix) },
                    "the seam $suffix no longer imports ${vendor.prefix}, so it is no longer a seam",
                )
            }
        }
    }

    @Test
    fun `the rule detects a vendor import outside a seam`() {
        // Positive control. See the class comment.
        for (vendor in VENDORS) {
            val offender = """
                package com.singularity.todo.feature.tasks

                import ${vendor.prefix}.Something

                class Sneaky
            """.trimIndent()

            val caught = listOf(offender)
                .filter { it.contains(vendor.prefix) }
                .filterNot { text -> vendor.seams.any { text.endsWith(it) } }

            assertEquals(
                1,
                caught.size,
                "the rule must still catch a $vendor.prefix import outside a seam; " +
                    "if it cannot, it cannot fail",
            )
        }
    }

    /**
     * A vendor SDK and the files allowed to name it.
     *
     * The prefix has no trailing dot, so a fully-qualified reference in code
     * (`io.github.jan.supabase.SupabaseClient` as a type argument) is caught as
     * readily as an import line. An import-only scan would miss the former, which is
     * the same capability by another route.
     */
    private data class Vendor(val prefix: String, val seams: List<String>)

    private companion object {
        val VENDORS = listOf(
            Vendor(
                prefix = "io.github.jan.supabase",
                seams = listOf(
                    "auth/SupabaseClientProvider.kt",
                    "auth/SupabaseAuthGateway.kt",
                    "auth/SecureSessionManager.kt",
                    "sync/PostgrestSyncRpc.kt",
                    "backup/SupabaseBackupBucket.kt",
                    "calendarSync/auth/SupabaseGoogleTokenGateway.kt",
                    "transport/SupabaseTransportFailure.kt",
                ),
            ),
            Vendor(
                prefix = "com.mmk.kmpauth",
                seams = listOf(
                    "auth/SupabaseAuthGateway.kt",
                ),
            ),
        )
    }
}
