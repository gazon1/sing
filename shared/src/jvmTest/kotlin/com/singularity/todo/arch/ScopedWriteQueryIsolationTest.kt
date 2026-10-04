package com.singularity.todo.arch

import java.io.File
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every `UPDATE` / `DELETE` in a DAO must scope itself to a user.
 *
 * ## The defect this prevents
 *
 * Profile isolation in this codebase is enforced at three layers: `assertCanWrite`
 * before the call, a `user_id = :userId` predicate in the SQL, and the returned
 * affected-row count. Remove the middle one and the outer two still *look* fine — a
 * write aimed at another profile matches zero rows, returns zero, and the caller
 * treats that as success. The observable behaviour only diverges when a query is
 * written or edited without the predicate, which is exactly the kind of omission no
 * test notices until it ships.
 *
 * `INSERT` is exempt on purpose: inserting a row *is* the act of creating it under
 * an id, and the id comes from the caller that already passed `assertCanWrite`.
 *
 * See the baseline spec `openspec/changes/baseline-write-pipeline`
 * (REQ-WP-002, REQ-WP-003) — whose verification checklist claimed this was covered
 * by `EntityMapperCompletenessTest`. It is not: that test checks mapper field
 * coverage against a hand-maintained table and never reads a `@Query`.
 */
@Tag("fast")
class ScopedWriteQueryIsolationTest {

    @Test
    fun rule_accepts_a_scoped_update() {
        assertTrue(
            !missingUserPredicate("UPDATE tasks SET title = :title WHERE id = :id AND user_id = :userId"),
        )
    }

    @Test
    fun rule_rejects_an_unscoped_update() {
        assertTrue(missingUserPredicate("UPDATE tasks SET title = :title WHERE id = :id"))
    }

    @Test
    fun rule_rejects_an_unscoped_delete() {
        assertTrue(missingUserPredicate("DELETE FROM tasks WHERE id = :id"))
    }

    @Test
    fun rule_ignores_inserts_and_reads() {
        assertTrue(!missingUserPredicate("INSERT INTO tasks (id, user_id) VALUES (:id, :userId)"))
        assertTrue(!missingUserPredicate("SELECT * FROM tasks WHERE id = :id AND user_id = :userId"))
    }

    @Test
    fun rule_ignores_an_empty_query() {
        assertTrue(!missingUserPredicate(""))
    }

    @Test
    fun every_update_and_delete_query_scopes_itself_to_a_user() {
        val root = System.getProperty("commonMain.root")
            ?: error(
                "commonMain.root system property is not set — " +
                    "see the jvmTest task config in shared/build.gradle.kts",
            )
        val offenders = File(root).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                queriesIn(file.readText()).mapNotNull { query ->
                    val sql = query.text
                    val head = sql.trim().substringBefore(' ').trim().uppercase()
                    if (head != "UPDATE" && head != "DELETE") return@mapNotNull null
                    if (!missingUserPredicate(sql)) return@mapNotNull null
                    val key = sql.trim().replace(Regex("\\s+"), " ").take(80)
                    if (key in ALLOWED_UNSCOPED) null else "${file.name} — $key"
                }
            }
            .toList()

        if (offenders.isNotEmpty()) {
            fail(
                buildString {
                    appendLine(
                        "UPDATE/DELETE query without a user_id predicate. Either the " +
                            "query is genuinely cross-user (add it to ALLOWED_UNSCOPED " +
                            "with the reason) or it is a profile-isolation hole:",
                    )
                    offenders.forEach { appendLine("  - $it") }
                },
            )
        }
    }

    @Test
    fun every_allowlist_entry_states_why() {
        assertTrue(
            ALLOWED_UNSCOPED.values.none { it.isBlank() },
            "an allowlist entry without a reason is just a hole with paperwork",
        )
    }

    @Test
    fun every_allowlist_entry_is_still_present() {
        """A stale allowlist entry hides a query that has since been fixed."""
        val root = System.getProperty("commonMain.root") ?: return
        val allSql = File(root).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { queriesIn(it.readText()).map { q -> q.text.trim().replace(Regex("\\s+"), " ").take(80) } }
            .toList()
        val stale = ALLOWED_UNSCOPED.keys.filterNot { it in allSql }
        assertTrue(
            stale.isEmpty(),
            "ALLOWED_UNSCOPED entries no longer match any query — delete them: $stale",
        )
    }

    private data class Query(val text: String)

    private fun queriesIn(source: String): List<Query> =
        Regex("""@Query\(\s*"((?:[^"\\]|\\.)*)"\s*\)""", RegexOption.DOT_MATCHES_ALL)
            .findAll(source)
            .map { Query(it.groupValues[1].replace("\\n", " ")) }
            .toList()

    private fun missingUserPredicate(sql: String): Boolean {
        val trimmed = sql.trim()
        if (trimmed.isEmpty()) return false
        val head = trimmed.substringBefore(' ').trim().uppercase()
        if (head != "UPDATE" && head != "DELETE") return false
        return !trimmed.contains("user_id")
    }

    private companion object {
        /**
         * The unscoped writes in the schema, each cross-user by design. Keyed on the
         * normalised query prefix, so editing a query invalidates the entry instead of
         * letting it silently outlive its justification.
         *
         * Two groups: tables that are not user-scoped at all — `profiles` defines the
         * scopes, `remote_config(s)` is a single global 'default' row, and
         * `sync_outbox` has no `user_id` column because it is a transport queue rather
         * than an entity — and retention/maintenance sweeps that are cross-user by
         * definition.
         */
        val ALLOWED_UNSCOPED = mapOf(
            "DELETE FROM llm_usage WHERE created_at < :cutoffEpochMs" to
                "retention purge: rows are deleted by age, across every profile",
            "DELETE FROM profiles WHERE id = :id" to
                "the profiles table is not user-scoped — it defines the scopes",
            "DELETE FROM remote_config_cache WHERE id = 'default'" to
                "single global config row, no user dimension",
            "DELETE FROM remote_configs WHERE id = 'default'" to
                "single global config row, no user dimension",
            "DELETE FROM sync_outbox WHERE patch_id = :id" to
                "sync_outbox is a transport queue with no user_id column; patch_id is a UUID",
            "UPDATE sync_outbox SET attempts = attempts + 1, last_error = :error WHERE patch_" to
                "retry bookkeeping on a transport row keyed by a UUID",
            "DELETE FROM sync_outbox WHERE entity_id = :entityId" to
                "entity_id is a UUID, so it cannot collide across profiles",
            "DELETE FROM sync_outbox" to
                "full outbox drain after a successful push — deliberately cross-profile",
        )
    }
}
