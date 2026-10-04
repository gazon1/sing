package com.singularity.todo.arch

import java.io.File
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No token is ever written to plain-text preferences again.
 *
 * ## The defect this exists to prevent
 *
 * `DataStoreSessionStore` wrote the access token, the refresh token and the
 * account's email address into `DataStore<Preferences>` — a plain-text file in the
 * app's data directory, included in backups and readable by anything with access to
 * the sandbox. The refresh token is a bearer credential: whoever holds it can mint
 * an access token and act as the user. That is not a leak in the sense of a log
 * line ending up in a file; it is the credential itself, at rest, unencrypted.
 *
 * `SecureSessionStore` moved it into the platform keychain and erases the old copy
 * once, at first read.
 *
 * ## Why a scan and not a type
 *
 * The rule is about a *write to a particular preference key from a particular
 * file*, which is not something a type system can state and not something a
 * reviewer reliably notices — `prefs[ACCESS_TOKEN] = session.accessToken` is an
 * ordinary-looking line. The scan reads every production source file and fails on
 * an assignment to, or a removal of, either token key anywhere outside the one
 * class allowed to perform the migration.
 *
 * A removal counts as well as an assignment, deliberately: a future "housekeeping"
 * pass that clears a key is still the plain-text store reaching into credential
 * storage, and it would reintroduce the coupling this removed.
 *
 * ## The positive control
 *
 * This is a regex over source text. A regex that quietly stops matching reports
 * success having checked nothing — the failure mode this project has already paid
 * for twice. The last test feeds the rule source that does exactly what is
 * forbidden and asserts it is caught.
 */
@Tag("fast")
class PlaintextTokenIsolationTest {

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

    /** `prefs[ACCESS_TOKEN] = …`, `prefs.remove(ACCESS_TOKEN)`, and the long forms. */
    private val forbiddenWrite = Regex(
        """\[\s*(?:\w+\.)?(ACCESS_TOKEN|REFRESH_TOKEN)\s*\]\s*=|""" +
            """remove\(\s*(?:\w+\.)?(ACCESS_TOKEN|REFRESH_TOKEN)\s*\)""",
    )

    @Test
    fun `no production file writes a token to preferences outside the migration`() {
        val offenders = productionSources()
            .filterNot { it.path.endsWith(MIGRATION_OWNER) }
            .flatMap { file ->
                file.readText()
                    .lineSequence()
                    .withIndex()
                    .filter { (_, line) -> forbiddenWrite.containsMatchIn(line) }
                    .map { (i, line) -> "${file.name}:${i + 1} ${line.trim()}" }
            }
            .toList()

        assertEquals(
            emptyList(),
            offenders,
            "a session token is being written to plain-text preferences. The refresh " +
                "token is a bearer credential for the user's account, and preferences are " +
                "an unencrypted file that travels in backups. Tokens belong in the keychain, " +
                "behind SecureSessionStore — " + offenders.joinToString(),
        )
    }

    @Test
    fun `the token keys still exist, and are declared in only one place`() {
        // Without this, renaming the constants would make the rule above vacuous:
        // no key would be named, so nothing could match.
        for (key in listOf("ACCESS_TOKEN", "REFRESH_TOKEN")) {
            val declarations = productionSources().filter { it.readText().contains("val $key") }
            assertTrue(
                declarations.isNotEmpty(),
                "no production file declares `$key`, so the isolation rule has nothing to match",
            )
            assertTrue(
                declarations.all { it.path.endsWith(MIGRATION_OWNER) },
                "the token preference keys are declared outside $MIGRATION_OWNER",
            )
        }
    }

    @Test
    fun `the rule detects a plain-text token write`() {
        val offender = """
            override suspend fun save(session: Session.SignedIn) {
                dataStore.edit { prefs ->
                    prefs[ACCESS_TOKEN] = session.accessToken
                    prefs[REFRESH_TOKEN] = session.refreshToken
                }
            }
        """.trimIndent()

        val found = forbiddenWrite.findAll(offender).map { it.groupValues[1] }.toList()
        assertEquals(listOf("ACCESS_TOKEN", "REFRESH_TOKEN"), found)
    }

    private companion object {
        /**
         * The one class allowed to touch the legacy keys: it moves a value out of
         * them and then erases it. Even here, the erase is the *second* of two
         * operations — see `SecureSessionStoreTest`.
         */
        const val MIGRATION_OWNER = "auth/SessionStore.kt"
    }
}
