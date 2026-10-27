package com.singularity.todo.arch

import com.singularity.todo.core.backup.BackupEntityKind
import java.io.File
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every backup entity kind must have a removal path.
 *
 * ## The hole this closes
 *
 * A delta can carry the rows it removed, and the restore takes them out. Which
 * row is taken out by which statement used to be written by hand, kind by kind,
 * and nothing checked it: a new kind in `BackupEntityKind` compiled, the restore
 * ran, and the row was simply never deleted — quietly, on the one device that
 * needed it.
 *
 * The compiler now catches most of it — the `when` that dispatches a removal is
 * exhaustive, so a kind without a branch does not compile. This test is the
 * second line: it reads the sources, so it also catches the ways the branch
 * stops being a removal path without the compiler noticing — a branch replaced
 * by an `else`, a dispatch that stopped dispatching, a kind that was added and
 * the dispatch that was not.
 *
 * What it reads is the branch *label*, not the statement under it: a branch
 * that stopped deleting anything is not this test's finding. The compiler does
 * not catch that one either — the branch is there, and it compiles.
 *
 * ## What it reads
 *
 * Two facts, both from the sources:
 *
 * - the kinds — the constants of `BackupEntityKind`, read from the file that
 *   declares it;
 * - the removal path — the branches of the `when (kind)` that deletes a row.
 *
 * The order of the removals is not this test's business: it is declared next to
 * the kinds (see `docs/decisions/2026-10-27-the-removal-order-is-a-declaration.md`),
 * and the phases are read from there.
 *
 * ## The control
 *
 * The third test is the control that makes the first one mean something: a
 * dispatch that is missing a kind is reported, and the report names the kind.
 * A rule that cannot fail is a rule that says nothing.
 */
@Tag("slow")
@Tag("slow:data")
class BackupRemovalPathTest {

    @Test
    fun `every kind has a removal path`() {
        val sources = backupSources()
        val kinds = declaredKinds(sources)
        val dispatch = removalDispatch(sources)

        val missing = (kinds - dispatch.branches).sorted()
        if (missing.isNotEmpty()) {
            fail(
                "These backup entity kinds have no removal path: ${missing.joinToString()}\n\n" +
                    "The importer deletes a row in the `when (kind)` at " +
                    "${dispatch.file.path}:${dispatch.method.line}, and a kind that is not in it is " +
                    "never deleted: the restore reports success, and the account keeps a row the " +
                    "person deleted on the other device. Add the branch, or say why the kind does " +
                    "not need one.\n\n" +
                    "The kinds declared: ${kinds.sorted().joinToString()}\n" +
                    "The kinds with a path: ${dispatch.branches.sorted().joinToString()}",
            )
        }
    }

    @Test
    fun `the test reads the kinds the enum declares, and the dispatch names no other`() {
        val sources = backupSources()
        val kinds = declaredKinds(sources)
        val dispatch = removalDispatch(sources)

        assertEquals(
            BackupEntityKind.entries.map { it.name }.toSet(),
            kinds,
            "the kinds read from the source are not the kinds the enum declares — this test is " +
                "reading the wrong thing, and a rule that reads the wrong thing says nothing",
        )
        assertTrue(
            dispatch.branches.isNotEmpty(),
            "the removal dispatch was found, but it names no kind at all",
        )
        assertEquals(
            emptyList(),
            (dispatch.branches - kinds).sorted(),
            "the dispatch names kinds the enum does not declare",
        )
    }

    @Test
    fun `the removals go in the order the kinds are declared in`() {
        val declared = BackupEntityKind.entries.map { it.name }

        assertEquals(
            EXPECTED_ORDER,
            declared,
            "the order the rows are removed in has changed.\n\n" +
                "The order is a decision about the tables: an annotation goes before the " +
                "attachment it belongs to, a link before the row it hangs off, and the parents " +
                "last. The kinds are declared in that order, and this test says which order that " +
                "is — so a kind that is added, moved or swapped cannot change it quietly.\n\n" +
                "If the order really has to change, change it here too: this list is the order, " +
                "written down where a reader can see it.",
        )
    }

    @Test
    fun `a kind without a removal path is reported`() {
        val dispatch = """
            private suspend fun removeRow(
                kind: BackupEntityKind,
                key: String,
                userId: UserId,
            ) {
                when (kind) {
                    BackupEntityKind.TASKS -> taskDao.deleteForUser(key, userId.value)
                    BackupEntityKind.NOTES -> noteDao.deleteForUser(key, userId.value)
                }
            }
        """.trimIndent()

        val branches = branchLabels(dispatch)
        val missing = kindsWithoutPath(setOf("TASKS", "NOTES", "PROJECTS"), branches)

        assertEquals(
            listOf("PROJECTS"),
            missing,
            "a kind the dispatch does not name must be reported, and named",
        )
    }

    // ── what the rule reads ────────────────────────────────────────────────────

    /** The production sources of the backup package. */
    private fun backupSources(): List<File> =
        SourceScan.productionFiles()
            .filter { it.path.replace(File.separatorChar, '/').contains("/core/backup/") }
            .sortedBy { it.path }

    /** The constants of `BackupEntityKind`, read from the file that declares it. */
    private fun declaredKinds(sources: List<File>): Set<String> {
        val file = sources.firstOrNull { ENUM_DECLARATION.containsMatchIn(it.readText()) }
            ?: fail(
                "No file in core/backup declares `enum class BackupEntityKind`. The kinds moved, " +
                    "and this test has to follow them — or the kinds are gone, and the restore has " +
                    "nothing to remove.",
            )
        val stripped = KotlinText.stripComments(file.readText())
        val declaration = ENUM_DECLARATION.find(stripped)?.range?.first
            ?: fail("The enum in ${file.path} cannot be read — this test cannot read it.")
        val open = stripped.indexOf('{', declaration)
        val close = KotlinText.matchingBrace(stripped, open)
        if (close < 0) {
            fail("The enum in ${file.path} does not close — this test cannot read it.")
        }
        return KIND_CONSTANT.findAll(stripped.substring(open + 1, close))
            .map { it.groupValues[1] }
            .toSet()
    }

    /** The `when (kind)` that deletes a row, and the kinds its branches name. */
    private fun removalDispatch(sources: List<File>): Dispatch {
        val found = sources.flatMap { file ->
            SourceScan.methods(file.readText())
                .filter { DISPATCH_SUBJECT.containsMatchIn(it.body) }
                .map { method -> Dispatch(file, method, branchLabels(method.body)) }
        }
        if (found.isEmpty()) {
            fail(
                "No `when (kind)` in core/backup. The importer used to delete a row in one, and " +
                    "without it a removal is never taken out — the restore reports success and " +
                    "the account keeps the row the person deleted on the other device.",
            )
        }
        if (found.size > 1) {
            fail(
                "There is more than one `when (kind)` in core/backup: " +
                    found.joinToString { "${it.file.name}:${it.method.line}" } +
                    ". This test cannot tell which one is the removal path.",
            )
        }
        return found.single()
    }

    /** The kinds a dispatch names, read from its branches. */
    private fun branchLabels(body: String): Set<String> =
        BRANCH_LABEL.findAll(body).map { it.groupValues[1] }.toSet()

    /** The kinds that have no path, sorted. */
    private fun kindsWithoutPath(kinds: Set<String>, branches: Set<String>): List<String> =
        (kinds - branches).sorted()

    /** The removal dispatch, and the kinds it names. */
    private class Dispatch(val file: File, val method: SourceScan.Method, val branches: Set<String>)

    private companion object {
        /**
         * The order the rows are removed in.
         *
         * The children first — the links, and the rows that hang off one — then the rows
         * that hold no link of their own, and last the rows the children hang off.
         *
         * Inside a phase the order matters too: an annotation goes before the
         * attachment it belongs to, because the annotations are not cascaded either.
         */
        val EXPECTED_ORDER = listOf(
            // The children first.
            "TASK_TAGS",
            "TASK_DEPENDENCIES",
            "CHECKLIST_ITEMS",
            "PROJECT_TAG_GROUPS",
            // Then the rows themselves.
            "ATTACHMENT_ANNOTATIONS",
            "ATTACHMENTS",
            "AGENDA_VIEWS",
            "SAVED_SEARCHES",
            "TASK_REMINDERS",
            "PROJECT_REMINDERS",
            "TIME_ENTRIES",
            // Then the rows the children hang off, last.
            "TAGS",
            "TAG_GROUPS",
            "TASKS",
            "NOTES",
            "PROJECTS",
            "PROFILES",
        )

        /** The declaration of the kinds. */
        val ENUM_DECLARATION = Regex("""enum class BackupEntityKind\b""")

        /** A constant of the enum: an upper-case name with its phase in parentheses. */
        val KIND_CONSTANT = Regex("""(?m)^\s*([A-Z][A-Z0-9_]*)\s*\(""")

        /** The `when` that dispatches a removal. */
        val DISPATCH_SUBJECT = Regex("""when\s*\(\s*kind\s*\)""")

        /** A branch of that `when`: the kind it deletes. */
        val BRANCH_LABEL = Regex("""BackupEntityKind\.([A-Z][A-Z0-9_]*)\s*->""")
    }
}
