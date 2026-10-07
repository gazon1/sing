package com.singularity.todo.arch

import com.singularity.todo.feature.reminders.data.CrossProfileReadRegistry
import java.io.File
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every `SELECT` that reads a profile-owned table must scope itself to a profile.
 *
 * ## The defect this prevents
 *
 * [ScopedWriteQueryIsolationTest] covers `UPDATE` and `DELETE` and says nothing about
 * reads, because a write already has an outer guard: `assertCanWrite` runs before the
 * call. Reads have no such guard, so the SQL predicate is the only thing standing between
 * a query and another profile's rows.
 *
 * This gate found the first live instance while being written. `ReminderDao.watchAllProfiles()`
 * was added deliberately — arming OS alarms is a device-wide operation — but the DAO already
 * had `observeAll()` sitting next to it, one predicate away, and the difference between "this
 * reminder fires for its owner" and "it never fires at all" is not visible in a diff. The
 * entry in [CrossProfileReadRegistry] records the decision; this test is what makes the
 * decision hold for the *next* query.
 *
 * ## Where the scoping columns come from
 *
 * Not a hand-written table list. [scopedTables] is derived from `Entities.kt`, so a new
 * entity is covered the moment it is declared, and a column renamed in one place cannot
 * silently exempt the gate in another. The same reasoning that made
 * `ScopedWriteQueryIsolationTest` accept both `user_id` and `owner_id` for `sync_state`.
 *
 * ## The three shapes an unfiltered read can have
 *
 * 1. **A sanctioned read** — listed in [CrossProfileReadRegistry]. One today.
 * 2. **A primary-key hydration** — `WHERE id = :id` and nothing else. These are legitimate:
 *    the id came from an already-scoped query. They are not waved through, though; the DAO
 *    has to still *offer* a scoped alternative for the same table, which
 *    [every unscoped read by primary key has a scoped sibling] checks.
 * 3. **Everything else** — a collection read with no profile predicate. Not allowed, and
 *    not on the list.
 *
 * ## What is exempt, and why that is not a loophole
 *
 * - **Join tables** (`task_tags`, `task_dependencies`) and child rows (`checklist_items`)
 *   carry no scoping column at all, so there is nothing to filter on. Exempt by
 *   construction, exactly as `INSERT` is exempt in the write gate.
 * - **`profiles`** is exempt by table, and it is the one exemption worth arguing about.
 *   The `profiles` table *is* the isolation boundary's subject: it holds every profile,
 *   and its own `user_id` is a nullable "whose profile this is, or null while it belongs to
 *   nobody", not the key rows are selected by. Scoping a profile listing by ownership would
 *   be circular — you need the list to know who is who. A reader who finds a new unfiltered
 *   query on another table should treat this list as closed.
 */
@Tag("fast")
class ScopedReadQueryIsolationTest {

    @Test
    fun `rule accepts a scoped read`() {
        assertTrue(
            !crossesProfiles(
                "SELECT * FROM tasks WHERE id = :id AND user_id = :userId",
                scopedTables = mapOf("tasks" to setOf("user_id")),
            ),
        )
    }

    @Test
    fun `rule rejects a collection read with no profile predicate`() {
        assertTrue(
            crossesProfiles(
                "SELECT * FROM tasks ORDER BY created_at DESC",
                scopedTables = mapOf("tasks" to setOf("user_id")),
            ),
        )
    }

    @Test
    fun `rule rejects a read scoped to something that is not a profile`() {
        // `owner_id` and `user_id` are both scoping columns, but a predicate naming
        // neither is what this exists to catch.
        assertTrue(
            crossesProfiles(
                "SELECT * FROM tasks WHERE deleted_at IS NULL",
                scopedTables = mapOf("tasks" to setOf("user_id")),
            ),
        )
    }

    @Test
    fun `rule ignores a table that carries no scoping column`() {
        assertTrue(
            !crossesProfiles(
                "SELECT * FROM task_tags WHERE task_id = :taskId",
                scopedTables = mapOf("tasks" to setOf("user_id")),
            ),
        )
    }

    @Test
    fun `rule ignores an exempt table`() {
        assertTrue(
            !crossesProfiles(
                "SELECT * FROM profiles ORDER BY created_at ASC",
                scopedTables = mapOf("profiles" to setOf("user_id")),
            ),
        )
    }

    @Test
    fun `every unscoped read in the DAOs is sanctioned, scoped, or exempt`() {
        val violations = daosUnscopedReads().filter { read ->
            read.table in scopedTables() &&
                read.table !in EXEMPT_TABLES &&
                !mentionsScopingColumn(read.sql) &&
                read.entry() !in CrossProfileReadRegistry.sanctioned &&
                !isPrimaryKeyHydration(read.sql)
        }

        assertTrue(
            violations.isEmpty(),
            "SELECTs on a profile-owned table with no profile predicate:\n" +
                violations.joinToString("\n") { "  ${it.entry()} — ${it.sql}" } +
                "\n\nFix it by adding the predicate. If crossing a profile is genuinely correct " +
                "here — a device-wide re-arm, a lookup by an id obtained from a scoped query — " +
                "add it to CrossProfileReadRegistry and say why in the KDoc above the method.",
        )
    }

    @Test
    fun `every unscoped read by primary key has a scoped sibling`() {
        // The shape this allows is "hydrate the row whose id a scoped query already
        // returned". It stops being that the moment the DAO stops offering a scoped way
        // to do the same lookup, which is when the by-PK read becomes the only way in.
        val hydrations = daosUnscopedReads().filter { read ->
            read.table in scopedTables() &&
                read.table !in EXEMPT_TABLES &&
                !mentionsScopingColumn(read.sql) &&
                read.entry() !in CrossProfileReadRegistry.sanctioned &&
                isPrimaryKeyHydration(read.sql)
        }
        val orphans = hydrations.filter { read ->
            allReads().none { sibling ->
                sibling.iface == read.iface &&
                    sibling.table == read.table &&
                    mentionsScopingColumn(sibling.sql) &&
                    sharesIdPredicate(sibling.sql)
            }
        }

        assertTrue(
            hydrations.isNotEmpty(),
            "No primary-key hydrations found, so this check is vacuous. The DAOs certainly " +
                "have some — if this fires, the SQL matcher has stopped matching " +
                "'SELECT * FROM <table> WHERE id = :id'.",
        )
        assertTrue(
            orphans.isEmpty(),
            "Primary-key reads with no scoped alternative left in the same DAO:\n" +
                orphans.joinToString("\n") { "  ${it.entry()} — ${it.sql}" } +
                "\n\nAdd the scoped variant, or scope this one. A by-PK read is only safe " +
                "because a scoped read exists; with none left, nothing keeps the id scoped.",
        )
    }

    @Test
    fun `the scan actually reads the DAOs`() {
        // Anti-vacuity. A scan that matches nothing passes every rule above trivially, and
        // a renamed file or a moved source root would turn this gate into a no-op that
        // still reported green — the failure the project's honesty gate exists to prevent.
        val reads = daosUnscopedReads()
        assertTrue(
            reads.size >= 10,
            "Only found ${reads.size} unscoped SELECTs in Daos.kt; expected at least 10. " +
                "The scan has stopped matching and every rule above is now vacuous.",
        )
        assertTrue(
            scopedTables().keys.containsAll(listOf("tasks", "tags", "task_reminders", "projects", "notes")),
            "Scoped tables derived from Entities.kt no longer include the tables it should: " +
                "${scopedTables().keys.sorted()}",
        )
    }

    @Test
    fun `the exempt table list is exactly the one this test argues for`() {
        // One exemption, one reason, pinned. Adding `profiles`'s neighbours to this list
        // is exactly the move that would make the gate decorative.
        assertEquals(
            setOf("profiles"),
            EXEMPT_TABLES,
            "Every exempt table is a permanent hole in this gate. A new one needs a reason " +
                "written down next to it, not just a line here.",
        )
    }

    // ─── the rule ────────────────────────────────────────────────────────────────

    private fun crossesProfiles(sql: String, scopedTables: Map<String, Set<String>>): Boolean {
        val table = tableOf(sql)
        return table != null &&
            table in scopedTables &&
            table !in EXEMPT_TABLES &&
            !mentionsScopingColumn(sql) &&
            !isPrimaryKeyHydration(sql)
    }

    private fun isPrimaryKeyHydration(sql: String): Boolean =
        PK_ONLY.contains(sql.trimEnd().removeSuffix(";")) ||
            PRIMARY_KEY_BY_ID.matches(sql.trim())

    private fun mentionsScopingColumn(sql: String): Boolean = SCOPING_COLUMNS.any { it in sql }

    private fun sharesIdPredicate(sql: String): Boolean = "id = :id" in sql || "id = :taskId" in sql

    private fun tableOf(sql: String): String? = Regex("""FROM\s+(\w+)""", RegexOption.IGNORE_CASE)
        .find(sql)?.groupValues?.get(1)

    // ─── the corpus ──────────────────────────────────────────────────────────────

    /** One `@Query("SELECT ...")` and the interface and method it was declared in. */
    private data class Read(val iface: String, val method: String, val table: String, val sql: String) {
        fun entry(): String = "$iface.$method"
    }

    private fun daosUnscopedReads(): List<Read> = allReads().filterNot { mentionsScopingColumn(it.sql) }

    /**
     * Every `SELECT` in the DAOs, scoped or not.
     *
     * The sibling check needs the scoped ones: searching for a scoped alternative inside a
     * list that has already had them filtered out is a check that can only ever fail, and
     * the first version of this file did exactly that.
     */
    private fun allReads(): List<Read> {
        val lines = daoSource().readText().lines()
        val reads = mutableListOf<Read>()
        var iface = ""
        lines.forEachIndexed { index, line ->
            Regex("""interface\s+(\w+)""").find(line)?.let { iface = it.groupValues[1] }
            val sql = Regex("""@Query\("([^"]+)"\)""").find(line)?.groupValues?.get(1)
                ?: return@forEachIndexed
            if (!sql.trimStart().startsWith("SELECT", ignoreCase = true)) return@forEachIndexed
            val table = tableOf(sql) ?: return@forEachIndexed
            val method = lines.drop(index + 1).take(4)
                .firstNotNullOfOrNull { Regex("""fun\s+(\w+)\s*\(""").find(it)?.groupValues?.get(1) }
                ?: "<unresolved>"
            reads += Read(iface.ifEmpty { "<unknown>" }, method, table, sql)
        }
        return reads
    }

    /**
     * Tables that carry a profile column, derived from the entities.
     *
     * Parsed rather than listed so that adding an entity, or renaming `user_id`, cannot
     * quietly exempt a table from the gate. A gate whose input is maintained by hand is a
     * gate that will be wrong the first time somebody adds a table.
     */
    private fun scopedTables(): Map<String, Set<String>> {
        val entities = File(commonMainRoot, "core/database/Entities.kt")
            .takeIf { it.exists() } ?: daoSource().parentFile.resolve("Entities.kt")
        val scoped = mutableMapOf<String, Set<String>>()
        entities.readText().split("@Entity").forEach { block ->
            val table = Regex("""tableName\s*=\s*"(\w+)"""")
                .find(block)?.groupValues?.get(1) ?: return@forEach
            val columns = buildSet { collectScopingColumns(block, this) }
            if (columns.isNotEmpty()) scoped[table] = columns
        }
        return scoped
    }

    /**
     * The profile columns an entity declares, however it spells them.
     *
     * Entities name them either as a mapped column (`@ColumnInfo("user_id") val userId`)
     * or as a bare property (`val userId`). Only reading one of the two is how a rename
     * silently exempts a table from this gate.
     */
    private fun collectScopingColumns(block: String, into: MutableSet<String>) {
        Regex("""name\s*=\s*"(user_id|owner_id|profile_id)"""")
            .findAll(block).forEach { into += it.groupValues[1] }
        Regex("""val\s+(userId|ownerId|profileId)\s*:""")
            .findAll(block).forEach { into += it.groupValues[1] }
    }

    private fun daoSource(): File =
        File(commonMainRoot).walkTopDown().firstOrNull { it.name == "Daos.kt" }
            ?: error("Daos.kt not found under $commonMainRoot")

    private companion object {
        val commonMainRoot: String = System.getProperty("commonMain.root")
            ?: error("commonMain.root is not set — see the jvmTest config in shared/build.gradle.kts")

        val SCOPING_COLUMNS = listOf("user_id", "owner_id", "profile_id", "userId", "ownerId", "profileId")

        val PRIMARY_KEY_BY_ID = Regex("""SELECT \* FROM \w+ WHERE id = :id""", RegexOption.IGNORE_CASE)

        val PK_ONLY = setOf("SELECT * FROM profiles WHERE id = :id")

        val EXEMPT_TABLES = setOf("profiles")
    }
}
