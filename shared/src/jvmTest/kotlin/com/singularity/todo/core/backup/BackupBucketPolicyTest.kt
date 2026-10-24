package com.singularity.todo.core.backup

import java.io.File
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The backup bucket's policies have to be scoped to the caller's own uid.
 *
 * ## What the policy is protecting
 *
 * The archives of every account of a project share one bucket. The path carries the
 * owner id, so the *client* keeps the archives apart — but a client-side convention
 * is not a boundary. If the policy says only "this bucket is for signed-in users",
 * then any signed-in account can read, overwrite and delete every other account's
 * archives as long as it can guess the path, and the path is guessable: it starts
 * with the account's own uid.
 *
 * ## Why the uid is read from the last folder
 *
 * The owner id is profile-scoped: the bare uid for the default profile, and
 * `<profile id>/<uid>` for every other one. The profile is a local concept — the
 * server has never heard of it — so the policy cannot check the *first* folder of
 * the path: for a non-default profile the first folder is the profile id, and
 * nothing on the server knows which profile ids belong to which account. The last
 * folder is the uid in both shapes, so that is what the policy checks.
 *
 * ## Why this is a test over SQL text
 *
 * The rule that matters is a property of the migration, and the migration is the
 * only place it is written down. Reading the file answers "is the policy that will
 * be applied scoped to the uid?" — the question that decides whether a user's
 * backups are readable by anyone else with an account. A live database would
 * answer it better, but the answer would then depend on a running server, and the
 * property has to hold on every project the migration is applied to.
 */
@Tag("fast")
class BackupBucketPolicyTest {

    private val sql: String = File(migrationPath).readText()

    @Test
    fun `the bucket exists and is private`() {
        assertTrue(
            sql.contains("insert into storage.buckets (id, name, public)"),
            "the migration must create the bucket",
        )
        assertTrue(
            Regex("""values \('backups', 'backups', false\)""").containsMatchIn(sql),
            "the bucket must be private: a public bucket serves every archive to anyone",
        )
    }

    @Test
    fun `every command on the bucket has a policy`() {
        val commands = policies().map { it.command }.toSet()
        assertEquals(
            setOf("select", "insert", "update", "delete"),
            commands,
            "the backup feature reads, writes, replaces and deletes objects; a policy " +
                "missing for one of them is an upload that fails with a message about " +
                "row-level security",
        )
    }

    @Test
    fun `every policy is scoped to the caller's uid`() {
        for (policy in policies()) {
            assertTrue(
                isUidScoped(policy.body),
                "the policy '${policy.name}' (${policy.command}) is not scoped to the " +
                    "caller's uid — any signed-in account could then read or delete " +
                    "another account's archives",
            )
        }
    }

    @Test
    fun `the uid is read from the last folder of the path`() {
        for (policy in policies()) {
            assertTrue(
                policy.body.contains(LAST_FOLDER),
                "the policy '${policy.name}' does not read the uid from the last folder " +
                    "of the path; the first folder is the profile id for a non-default " +
                    "profile, and the server cannot know it",
            )
        }
    }

    @Test
    fun `the rule can fail`() {
        // The positive control. A rule that cannot fail reports success having
        // checked nothing, which is the specific failure this project has already
        // paid for twice in gates of its own.
        assertFalse(
            isUidScoped("bucket_id = 'backups'"),
            "a bucket-only policy must be recognised as unscoped",
        )
        assertFalse(
            isUidScoped("bucket_id = 'backups' and auth.uid()::text = (storage.foldername(name))[1]"),
            "a policy that reads the *first* folder must be recognised as unscoped",
        )
        assertTrue(
            isUidScoped("bucket_id = 'backups' and $LAST_FOLDER = auth.uid()::text"),
            "the rule must accept the policy the migration writes",
        )
    }

    private fun policies(): List<Policy> = POLICY.findAll(sql).map { match ->
        Policy(
            name = match.groupValues[1],
            command = match.groupValues[2],
            body = match.groupValues[3],
        )
    }.toList()

    private data class Policy(val name: String, val command: String, val body: String)

    private companion object {
        /** The path of the migration, declared by the jvmTest task. */
        val migrationPath: String = System.getProperty("backupBucketPolicy.migration")
            ?: error(
                "backupBucketPolicy.migration system property is not set — " +
                    "see the jvmTest task config in shared/build.gradle.kts",
            )

        /**
         * The last folder of an object's path.
         *
         * `storage.foldername` returns the folders of the path without the file
         * name, so the last one is the owner id's last segment — the uid.
         */
        const val LAST_FOLDER = "(storage.foldername(name))[array_length(storage.foldername(name), 1)]"

        /** A `create policy` statement and the body that decides who it covers. */
        val POLICY = Regex(
            """create policy "([^"]+)"\s+on storage\.objects\s+for\s+(\w+)\s+to\s+authenticated\s+(.*?);""",
            RegexOption.DOT_MATCHES_ALL,
        )

        /** Whether the policy body keeps the caller inside their own uid folder. */
        fun isUidScoped(body: String): Boolean =
            body.contains("auth.uid()") && body.contains(LAST_FOLDER)
    }
}
