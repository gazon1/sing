package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Is the set of places the write rules scan actually the set of places that enqueue?
 *
 * ## The hole this closes
 *
 * `SyncedWriteEnqueuesTest`, `an unscoped write is guarded` and `a method that writes twice
 * enqueues twice` all answer questions *about a file they are already looking at*. None of
 * them can notice a file it is not looking at — and the files it looks at are chosen by a
 * glob: `feature/**/*RepositoryImpl.kt` declaring a `SyncRepository`-typed property.
 *
 * A glob is a hypothesis about where the code lives, and a hypothesis does not fail when it
 * stops being true; it just quietly stops covering. A new `WidgetStore.kt` under
 * `feature/widget/data/` writes rows and enqueues, and every rule above passes, because
 * none of them was ever asked about a file that is not named `*RepositoryImpl.kt`. The
 * write is unscoped, or unguarded, or half-patched, and the suite is green.
 *
 * This is the same shape as the failure `PlatformModuleMirrorTest` and the missing
 * `desktopPlatformModule` bindings had, one layer down: a mirror that answers correctly
 * about everything it holds, and is silent about what it does not hold.
 *
 * ## Why the exemption is derived, not listed
 *
 * The question is "which calls are a repository enqueueing its own synced write", and the
 * answer is the receiver: `syncRepository.enqueue(`. Repositories enqueue through the port
 * they were handed. The other `enqueue(` spellings in the tree are different call paths and
 * are not matched at all, rather than matched and excused:
 *
 * - `SyncRepositoryImpl.enqueue` forwards to `engine.enqueue` — it *is* the port.
 * - `CoreDiModule` hands `get<SyncEngine>().enqueue` to `SyncDocumentWriter` — it is wiring.
 *
 * A list of those two files would be a fourth hand-written list in a session that has
 * already produced three that went stale silently, and the two most recent of them went
 * stale while the tree stayed green. The receiver is a fact about the call, so it is read
 * from the call.
 *
 * The one remaining case is documentation: `GenericUserScopedRepository`'s KDoc spells out
 * the canonical pipeline, including `syncRepository.enqueue(item)`. A dependency that exists
 * only in a comment is not a dependency, so block comments are removed before matching.
 * That strip is deliberately conservative — it removes KDoc and `/* */`, and full-line `//`
 * comments, and leaves trailing `//` comments alone. Over-stripping can hide a real call;
 * under-stripping can only produce a false positive, which a human resolves in a minute.
 *
 * ## Why the escape is "no DAO write" and not a name
 *
 * A file that enqueues through the port but contains no DAO write has nothing for the three
 * rules to miss — which is why the interface documenting the pipeline passes. That is a
 * syntactic fact about the file, decided the same way for every file, rather than a list of
 * filenames that only the author of this test remembers to update.
 *
 * ## What this does not prove
 *
 * It reads `syncRepository.enqueue(`. A repository that takes the port as a constructor
 * parameter with a different name, or that reaches the engine directly — the thing
 * `SyncEngineTakesNoFeatureTypesTest` exists to prevent — enqueues without matching here.
 * And it says nothing about a repository that writes synced rows and never enqueues at
 * all: there is no enqueue call to find it by. Closing that is a separate question, and the
 * reason this one is asked first is that it is the one that can be asked without the server.
 */
@Tag("fast")
class EnqueueSiteIsScannedTest {

    @Test
    fun `every enqueue that writes a row sits in a file the write rules scan`() {
        val offenders = escapedSites(allSources())

        if (offenders.isNotEmpty()) {
            fail(
                "These files enqueue a patch but are not scanned by SyncedWriteEnqueuesTest, so " +
                    "none of its three rules can see their writes:\n" +
                    offenders.joinToString("\n") { "  ${it.path}:${it.line}" } +
                    "\n\nEither move the write into a `feature/**/data/*RepositoryImpl.kt` that " +
                    "declares the port, or — if it is not a repository — say why it enqueues at " +
                    "all. Do not leave it here: a file the rules cannot see is a file the " +
                    "rules cannot fail.",
            )
        }
    }

    @Test
    fun `a repository outside the glob is reported`() {
        // The control that makes the rule above meaningful. The synthetic file has every
        // property the rule looks at — it writes a row and enqueues — and differs only in
        // where it lives. A rule whose scan set silently excluded it would pass the first
        // test forever and never notice.
        val escaped = escapedSites(
            listOf(
                SourceFile(
                    "feature/widget/data/WidgetStore.kt",
                    """
                    class WidgetStore(
                        private val syncRepository: SyncRepository,
                    ) {
                        suspend fun rename(id: WidgetId, name: String) {
                            widgetDao.upsert(row.copy(name = name))
                            syncRepository.enqueue(row)
                        }
                    }
                    """.trimIndent(),
                ),
            ),
        )
        assertEquals(
            listOf("feature/widget/data/WidgetStore.kt:6"),
            escaped.map { "${it.path}:${it.line}" },
            "a file under feature/ that writes a row and enqueues is not scanned by any rule",
        )
    }

    @Test
    fun `a repository inside the glob is not reported`() {
        val escaped = escapedSites(
            listOf(
                SourceFile(
                    "feature/widget/data/WidgetRepositoryImpl.kt",
                    """
                    class WidgetRepositoryImpl(
                        private val widgetDao: WidgetDao,
                        private val syncRepository: SyncRepository,
                    ) {
                        suspend fun rename(id: WidgetId, name: String) {
                            widgetDao.upsert(row.copy(name = name))
                            syncRepository.enqueue(row)
                        }
                    }
                    """.trimIndent(),
                ),
            ),
        )
        assertEquals(emptyList(), escaped, "a scanned repository was reported as escaped")
    }

    @Test
    fun `a documented pipeline is not a call site`() {
        // The interface spells the pipeline out in KDoc, receiver and all. Treating
        // documentation as a call would put a permanent, unexplainable entry in the failure
        // message — which is how a rule loses its readers.
        val escaped = escapedSites(
            listOf(
                SourceFile(
                    "core/repository/WidgetUserScopedRepository.kt",
                    """
                    interface WidgetUserScopedRepository {
                        /**
                         * 1. currentUser.assertCanWrite(item.id)
                         * 2. widgetDao.upsert(item)
                         * 3. syncRepository.enqueue(item)
                         */
                        suspend fun save(item: Widget): Result<Unit>
                    }
                    """.trimIndent(),
                ),
            ),
        )
        assertEquals(emptyList(), escaped, "an enqueue inside KDoc was treated as a call site")
    }

    @Test
    fun `the port itself and its wiring are not repository call sites`() {
        // Both files call `enqueue(`. Neither is a repository enqueueing its own write, and
        // neither is excused by name — they are not matched, because the receiver is the
        // engine rather than the port.
        val escaped = escapedSites(
            listOf(
                SourceFile(
                    "core/sync/SyncRepositoryImpl.kt",
                    """
                    class SyncRepositoryImpl(private val engine: SyncEngine) : SyncRepository {
                        override suspend fun enqueue(entity: SyncableEntity): Result<Unit> =
                            engine.enqueue(entity)

                        override suspend fun push(): Result<Unit> {
                            outboxDao.upsert(row)
                            return engine.enqueue(entity)
                        }
                    }
                    """.trimIndent(),
                ),
                SourceFile(
                    "core/di/CoreDiModule.kt",
                    """
                    fun syncWriter(): Module = module {
                        single {
                            SyncDocumentWriter(
                                enqueue = { entity -> get<SyncEngine>().enqueue(entity) },
                            )
                        }
                    }
                    """.trimIndent(),
                ),
            ),
        )
        assertEquals(emptyList(), escaped, "the port or its wiring was reported as a call site")
    }

    @Test
    fun `a trailing comment does not hide a real call`() {
        // The conservative half of the comment stripper. `//` at the end of a line is not
        // removed, because a string literal on that line may contain `/*`, and removing
        // block comments across a false start swallows the rest of the file — including the
        // call this rule exists to find. An unreported trailing comment costs nothing; a
        // swallowed call costs a silent green suite.
        val escaped = escapedSites(
            listOf(
                SourceFile(
                    "feature/widget/data/WidgetStore.kt",
                    """
                    class WidgetStore(private val syncRepository: SyncRepository) {
                        suspend fun rename() {
                            val base = "http://x"
                            widgetDao.upsert(row); syncRepository.enqueue(row) // ok
                        }
                    }
                    """.trimIndent(),
                ),
            ),
        )
        assertEquals(
            listOf("feature/widget/data/WidgetStore.kt:4"),
            escaped.map { "${it.path}:${it.line}" },
            "a call after a URL string was lost to the comment stripper",
        )
    }

    @Test
    fun `the scan set is not empty`() {
        // The failure mode of a rule whose subject set is empty: every assertion passes
        // because it is iterating over nothing. `every enqueue … is scanned` above is
        // exactly that shape, so it needs the check its own rule carries.
        val scanned = allSources().filter { isScanned(it.path, it.source) }
        assertTrue(
            scanned.isNotEmpty(),
            "no source matched the scanned shape — the path or the declaration pattern has " +
                "moved, and the rule above is checking nothing",
        )
        assertTrue(
            scanned.any { PORT_ENQUEUE.containsMatchIn(stripComments(it.source)) },
            "nothing in the scanned set calls the port's enqueue — the scan set and the thing " +
                "being scanned have diverged",
        )
    }

    // ── the rule ───────────────────────────────────────────────────────────────────

    private data class SourceFile(val path: String, val source: String)

    private data class Site(val path: String, val line: Int)

    /**
     * Sources that enqueue through the port, write a row, and are not scanned.
     *
     * The three predicates are ordered cheapest-first and the write check comes before the
     * scan check on purpose: a file with no DAO write is not a hole, whatever it is called.
     */
    private fun escapedSites(sources: List<SourceFile>): List<Site> = sources.flatMap { file ->
        val code = stripComments(file.source)
        val call = PORT_ENQUEUE.find(code)
        if (call == null) return@flatMap emptyList()
        if (!WRITES_ROW_IN_BODY.containsMatchIn(code)) return@flatMap emptyList()
        if (isScanned(file.path, code)) return@flatMap emptyList()
        listOf(Site(file.path, code.take(call.range.first).count { it == '\n' } + 1))
    }

    /** The shape `SyncedWriteEnqueuesTest` scans, restated rather than imported. */
    private fun isScanned(path: String, source: String): Boolean =
        path.startsWith(FEATURE_ROOT) &&
            path.endsWith(REPOSITORY_IMPL_SUFFIX) &&
            DECLARES_PORT.containsMatchIn(source)

    /**
     * Comments, removed in place so line numbers survive for the failure message.
     *
     * Only block comments and comments that are alone on their line. A trailing `//` is
     * left alone for the reason in the control above: the only reliable way to strip it is
     * to track string literals, and a stripper that mis-tracks them eats code.
     */
    private fun stripComments(source: String): String = source
        .replace(BLOCK_COMMENT) { blank(it.value) }
        .replace(FULL_LINE_COMMENT) { it.value }

    /** Same length and same newlines, so a line number in the message still points at the call. */
    private fun blank(text: String): String = NON_NEWLINE.replace(text, " ")

    // ── the tree ───────────────────────────────────────────────────────────────────

    private fun allSources(): List<SourceFile> {
        val root = commonMainRoot()
        return root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .map { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
            .map { (path, source) -> SourceFile(path, source) }
            .toList()
    }

    private fun commonMainRoot(): File {
        val root = System.getProperty("commonMain.root")
            ?: error("commonMain.root system property is not set — see the jvmTest task config")
        val dir = File(root, "com/singularity/todo")
        assertTrue(dir.isDirectory, "commonMain sources are not where this test expects them: ${dir.path}")
        return dir
    }

    private companion object {
        const val FEATURE_ROOT = "feature/"
        const val REPOSITORY_IMPL_SUFFIX = "RepositoryImpl.kt"

        /** A repository enqueueing its own write. The receiver is what makes it one. */
        val PORT_ENQUEUE = Regex("""syncRepository\s*\.\s*enqueue\s*\(""")

        /** The same write vocabulary `SyncedWriteEnqueuesTest` scans for. */
        val WRITES_ROW_IN_BODY = Regex(
            """Dao\.(upsert|insert|update\w*|softDelete\w*|set\w*|patch\w*|delete\w*)\(""",
        )

        val DECLARES_PORT = Regex("""private val \w+\s*:\s*SyncRepository""")

        val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")

        val FULL_LINE_COMMENT = Regex("""(?m)^[ \t]*//[^\n]*""")

        val NON_NEWLINE = Regex("""[^\n]""")
    }
}