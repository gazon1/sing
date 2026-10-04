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
 * ## Two names for the same scoping column
 *
 * The predicate may name the scoping column `user_id` (entities) or `owner_id`
 * (`sync_state`, whose composite key is `(owner_id, profile_id)`). Both are
 * scoping predicates and both satisfy the rule. Only the first was recognised, so
 * every scoped `sync_state` write was reported as a hole — and the two available
 * ways to silence that were both wrong: allowlisting a genuinely scoped query
 * claims in writing that it is cross-profile, and renaming the column to match
 * the gate would have coupled a schema decision to a lint rule. The check accepts
 * either name instead.
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
    fun rule_accepts_an_owner_scoped_update() {
        // `sync_state` is keyed by (owner_id, profile_id). Its writes are scoped, and
        // reporting them as holes would push the next agent towards "fixing" a
        // correct query by allowlisting it as cross-profile.
        assertTrue(
            !missingUserPredicate(
                "UPDATE sync_state SET last_lsn = :lsn WHERE owner_id = :ownerId AND profile_id = :profileId",
            ),
        )
    }

    @Test
    fun rule_still_rejects_a_write_scoped_only_by_the_secondary_key() {
        // profile_id alone identifies nothing: the same profile id exists under two
        // accounts, so a write scoped by it alone is a cross-owner write.
        assertTrue(
            missingUserPredicate("UPDATE sync_state SET last_lsn = :lsn WHERE profile_id = :profileId"),
        )
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
    fun a_query_wrapped_across_literals_is_still_extracted() {
        // The gate must see a query however ktlint happened to wrap it. Without this
        // it has a formatting-shaped hole: reformat a query across two literals and it
        // stops being checked at all, which is how a write with no `user_id` predicate
        // would sail through. The real instance: the outbox's `markFailed` query was
        // wrapped to satisfy line length and its allowlist entry immediately reported
        // itself stale — the gate was reporting on a query it could no longer see.
        val wrapped = """
            @Query(
                "UPDATE sync_outbox SET attempts = attempts + 1, last_error = :error, " +
                    "next_attempt_at = :nextAttemptAt WHERE patch_id = :id",
            )
        """.trimIndent()
        val onOneLine = "@Query(" +
            "\"UPDATE sync_outbox SET attempts = attempts + 1, last_error = :error, " +
            "next_attempt_at = :nextAttemptAt WHERE patch_id = :id\")"

        assertEquals(
            queriesIn(onOneLine).map { normalise(it.text) },
            queriesIn(wrapped).map { normalise(it.text) },
            "wrapping a query changed what the gate sees — the two forms must match",
        )
    }

    @Test
    fun a_wrapped_unscoped_write_is_still_reported() {
        val wrapped = """
            @Query(
                "DELETE FROM sync_something WHERE patch_id = :id " +
                    "AND entity_id = :entityId",
            )
        """.trimIndent()

        assertTrue(
            queriesIn(wrapped).any { missingUserPredicate(it.text) },
            "a wrapped unscoped write must still be seen as unscoped",
        )
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
            .flatMap { queriesIn(it.readText()).map { q -> normalise(q.text) } }
            .toList()
        val stale = ALLOWED_UNSCOPED.keys.filterNot { it in allSql }
        assertTrue(
            stale.isEmpty(),
            "ALLOWED_UNSCOPED entries no longer match any query — delete them: $stale",
        )
    }

    private data class Query(val text: String)

    /**
     * Every `@Query` string in [source], with concatenated literals joined.
     *
     * The join matters: ktlint wraps a long query across several literals joined by
     * `+`, and a regex that only matches a single literal cannot see those at all.
     * The trailing comma before the closing paren is part of that: Kotlin allows it,
     * ktlint adds it, and without `,?` in the pattern the wrapped form stops matching.
     * The first version of this extractor did exactly that, and the result was not a
     * false negative in a test but a query that the gate could not see — a write
     * invisible to the missing-`user_id` check, and an allowlist entry that
     * "went stale" the moment the query was wrapped. Reformatting a query silently
     * removed it from the gate.
     */

    // The key form the allowlist is written in: collapsed whitespace, 80 chars.
    private fun normalise(sql: String): String = sql.trim().replace(Regex("\\s+"), " ").take(80)

    private fun queriesIn(source: String): List<Query> =
        Regex(
            """@Query\(\s*((?:"(?:[^"\\]|\\.)*"\s*\+\s*)*"(?:[^"\\]|\\.)*")\s*,?\s*\)""",
            RegexOption.DOT_MATCHES_ALL,
        )
            .findAll(source)
            .map { match ->
                val literals = Regex(""""((?:[^"\\]|\\.)*)"""")
                    .findAll(match.groupValues[1])
                    .map { it.groupValues[1].replace("\\n", " ") }
                    .toList()
                Query(literals.joinToString(" "))
            }
            .toList()

    private fun missingUserPredicate(sql: String): Boolean {
        val trimmed = sql.trim()
        if (trimmed.isEmpty()) return false
        val head = trimmed.substringBefore(' ').trim().uppercase()
        if (head != "UPDATE" && head != "DELETE") return false
        return SCOPING_COLUMNS.none { trimmed.contains(it) }
    }

    private companion object {
        /**
         * Column names that make a write profile-scoped.
         *
         * `user_id` on entities; `owner_id` on [com.singularity.todo.core.sync.SyncStateEntity],
         * whose key is `(owner_id, profile_id)`. Matching a name rather than a shape
         * is a weaker rule than parsing the predicate, and deliberately so: the rule's
         * job is to notice a *missing* predicate, and a false positive on a
         * well-written query costs more than it catches.
         */
        val SCOPING_COLUMNS = listOf("user_id", "owner_id")

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
            // Exactly the 80-character normalised prefix the gate compares against.
            "UPDATE sync_outbox SET attempts = attempts + 1, last_error = :error, next_attemp" to
                "retry bookkeeping on a transport row keyed by a UUID",
            "DELETE FROM sync_outbox WHERE entity_id = :entityId" to
                "entity_id is a UUID, so it cannot collide across profiles",
            "DELETE FROM sync_outbox" to
                "full outbox drain after a successful push — deliberately cross-profile",
            "DELETE FROM sync_dead_letter WHERE patch_id = :id" to
                "same transport queue, same absence of a user dimension; patch_id is a UUID",
            "DELETE FROM sync_dead_letter" to
                "full dead-letter drain — deliberately cross-profile",
            "DELETE FROM sync_shadow" to
                "drains every scope at once, alongside sync_state; leaving another " +
                "account's shadow behind would make the next sign-in diff against a " +
                "base describing someone else's uploads",
            "DELETE FROM sync_state" to
                "drains every scope at once — used on sign-out; leaving another " +
                "account's cursor behind would resume it inside the wrong history",
        )
    }
}
