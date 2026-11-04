package com.singularity.todo.arch

import com.singularity.todo.core.ui.components.ScreenActionsHolder
import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The shell bar has one way in: the two shared publishers.
 *
 * ## The defect this catches
 *
 * [ScreenActionsHolder] reaches a screen through `LocalScreenActions`, and its
 * published fields — `title`, `navigationIcon`, `override`, `actions`,
 * `selectionTitle`, `onExitSelection` — are plain `var`s. Nothing in the type
 * system says who may write them, so a composable that wants a bar can set them
 * itself. That is how #1717 happened: a second publisher was added, it wrote
 * fields the holder did not have, and the only reason the mistake surfaced was
 * that a *different* field's name happened to collide — `main` did not compile,
 * but not for the reason the author needed to know.
 *
 * A screen that writes the holder directly is a second way to drive the shell
 * bar: it bypasses the disposal in [TopAppBar] and `ShellOverride`, clears what
 * the bar standing next to it published, or publishes a title the shell never
 * agreed to draw. The shell bar is the one surface every screen shares, and it
 * had no owner.
 *
 * ## The rule
 *
 * A write to a published field is allowed in exactly two places: inside
 * [TopAppBar] and inside `ShellOverride`. Both are the shared publishers — the
 * composables a screen calls instead of reaching for the holder. The holder
 * itself may clear its own properties unqualified (that is `clear()`), because
 * writing its fields is what the class is for; a write with a receiver is a
 * write from outside.
 *
 * A "write" is read from the source, not from the compiler: an assignment to one
 * of the fields through `LocalScreenActions.current` itself, or through a
 * holder-typed name — the result of `LocalScreenActions.current`, a
 * `remember { ScreenActionsHolder() }`, or a `ScreenActionsHolder`-typed
 * parameter. A file that names neither the composition local nor the type
 * cannot have a holder to write, so unrelated `.title =` assignments elsewhere
 * in the tree are not matched rather than matched and excused.
 *
 * ## The registry is checked against the tree, not assumed
 *
 * The allowed set above is the *claim*. The tree is the *fact*: the functions
 * whose bodies write a published field. This test enumerates the fact and
 * compares it with the claim in both directions — a function that writes the
 * holder without being registered is reported, and a registration with no
 * writer behind it is reported too. A registry that is only read, never
 * checked, is a list that goes stale the first time somebody adds a publisher.
 *
 * ## What it cannot see
 *
 * A holder reached through a helper that returns it under another declared type
 * — an inferred `= screenActions()` with no `ScreenActionsHolder` in the file —
 * is not matched. Nor is an unqualified write inside an extension function on
 * the holder. Neither exists today; both would have to be introduced together
 * with the type they hide.
 *
 * ## The controls
 *
 * The positive controls feed the rule synthetic sources: a screen writing the
 * holder is reported with its file and line, a write through the composition
 * local itself is reported, an unregistered writer and a stale registration are
 * each reported, and a read, an unrelated `.title` assignment and the holder's
 * own clear are not. Without them, a rule that finds nothing is
 * indistinguishable from a rule that looks in the wrong place.
 */
@Tag("fast")
class ScreenActionsPublishersTest {
    @Test
    fun `nothing publishes into the shell bar outside the shared publishers`() {
        val sources = allSources()
        // A rule over an empty set agrees with everything. The roots are declared in
        // shared/build.gradle.kts; this is what notices when one of them moves.
        assertTrue(
            sources.size > 100,
            "the scan covered only ${sources.size} files — the source roots are wrong",
        )

        val offenders = violations(writeSites(sources))
        if (offenders.isNotEmpty()) {
            fail(
                "These sites write ScreenActionsHolder's published fields outside " +
                    "${REGISTERED_PUBLISHERS.sorted().joinToString(" and ")}:\n" +
                    offenders.joinToString("\n") { "  ${it.path}:${it.line} — ${it.field}" } +
                    "\n\nPublish through TopAppBar(overrideShell = true) or ShellOverride(...). " +
                    "A screen that sets the holder itself drives the shell bar behind the " +
                    "shared publishers' back — which is how a screen once lost its title.",
            )
        }
    }

    @Test
    fun `the registered publishers are exactly the functions that write the holder`() {
        val observed = observedPublishers(writeSites(allSources()))
        val report = comparePublishers(observed)

        val problems =
            buildList {
                report.unregistered.forEach { (name, site) ->
                    add("$name at ${site.path}:${site.line} writes the holder but is not registered")
                }
                report.stale.forEach { add("$it is registered but no function writes the holder") }
            }
        if (problems.isNotEmpty()) {
            fail(
                "The publisher registry and the tree disagree:\n" +
                    problems.joinToString("\n") { "  - $it" } +
                    "\n\nRegister a new publisher in REGISTERED_PUBLISHERS here, or remove the " +
                    "registration that no longer writes. The other test excuses this registry, " +
                    "so a publisher missing from it is reported there as a violation.",
            )
        }
    }

    @Test
    fun `the published field list names every field the holder declares`() {
        val holder =
            allSources().firstOrNull { it.path.endsWith("/ScreenActions.kt") }
                ?: fail("ScreenActions.kt is not in the scan — the source roots are wrong")
        val declared = declaredFields(holder.source)
        assertTrue(declared.isNotEmpty(), "parsed no published fields out of ${holder.path}")

        val report = compareFields(declared, ScreenActionsHolder.publishedFields)
        assertTrue(
            report.unlisted.isEmpty() && report.unknown.isEmpty(),
            "ScreenActionsHolder.publishedFields and the declared fields disagree: " +
                "unlisted=${report.unlisted}, unknown=${report.unknown}. Add the field beside " +
                "the others in the companion list (and to a publisher), or remove it from both.",
        )
    }

    @Test
    fun `clear resets every published field`() {
        val holder =
            allSources().firstOrNull { it.path.endsWith("/ScreenActions.kt") }
                ?: fail("ScreenActions.kt is not in the scan — the source roots are wrong")
        val report = compareFields(clearedFields(holder.source), ScreenActionsHolder.publishedFields)

        assertTrue(
            report.unlisted.isEmpty() && report.unknown.isEmpty(),
            "ScreenActionsHolder.clear() and publishedFields disagree: " +
                "cleared but not listed=${report.unlisted}, listed but not cleared=${report.unknown}. " +
                "A field that survives the shell's reset is a stale bar on the next screen.",
        )
    }

    // ── the positive controls ─────────────────────────────────────────────────────

    @Test
    fun `a screen writing the holder is reported with its file and line`() {
        val offenders =
            violations(
                writeSites(listOf(SourceFile(SCREEN_PATH, SCREEN_WRITING_HOLDER))),
            )

        assertEquals(
            listOf("$SCREEN_PATH:8 title"),
            offenders.map { "${it.path}:${it.line} ${it.field}" },
            "a screen that sets the holder's title was not reported",
        )
        assertEquals(
            "WidgetScreen",
            offenders.single().publisher,
            "the write was not attributed to the composable that contains it",
        )
    }

    @Test
    fun `a holder write from the holder itself is not a publisher`() {
        val sources = listOf(SourceFile(HOLDER_PATH, HOLDER_CLEARING_ITSELF))
        val sites = writeSites(sources)

        assertEquals(emptyList<WriteSite>(), violations(sites), "the holder's own clear was reported")
        assertEquals(
            emptyMap<String, WriteSite>(),
            observedPublishers(sites),
            "the holder's own clear was registered as a publisher",
        )
    }

    @Test
    fun `a read of the holder is not a write`() {
        val offenders =
            violations(
                writeSites(listOf(SourceFile(SCREEN_PATH, SCREEN_READING_HOLDER))),
            )

        assertEquals(emptyList<WriteSite>(), offenders, "a read of the holder was reported as a write")
    }

    @Test
    fun `an unrelated title assignment in a holder-aware file is not reported`() {
        val offenders =
            violations(
                writeSites(listOf(SourceFile(SCREEN_PATH, SCREEN_WRITING_ANOTHER_TITLE))),
            )

        assertEquals(
            emptyList<WriteSite>(),
            offenders,
            "an assignment to a field of the same name on a non-holder receiver was reported",
        )
    }

    @Test
    fun `a write through the composition local itself is reported`() {
        val offenders =
            violations(
                writeSites(listOf(SourceFile(SCREEN_PATH, SCREEN_WRITING_THROUGH_THE_LOCAL))),
            )

        assertEquals(
            listOf("$SCREEN_PATH:7 title"),
            offenders.map { "${it.path}:${it.line} ${it.field}" },
            "a write through LocalScreenActions.current was not reported",
        )
    }

    @Test
    fun `a publisher missing from the registry is reported`() {
        val observed =
            observedPublishers(
                writeSites(listOf(SourceFile(SCREEN_PATH, SCREEN_WRITING_HOLDER))),
            )
        val report = comparePublishers(observed)

        assertEquals(setOf("WidgetScreen"), report.unregistered.keys)
        // The synthetic tree carries only the unregistered writer, so the real
        // registrations have no publisher here either. That direction is the next
        // test's; this one asserts the one it names.
        assertEquals(REGISTERED_PUBLISHERS, report.stale)
    }

    @Test
    fun `a registration with no publisher behind it is reported`() {
        val site = WriteSite(SCREEN_PATH, 8, "title", "TopAppBar")
        val report = comparePublishers(observed = mapOf("TopAppBar" to site))

        assertEquals(setOf("ShellOverride"), report.stale)
        assertEquals(emptyMap<String, WriteSite>(), report.unregistered)
    }

    @Test
    fun `a field added to the holder without the list is reported`() {
        val declared =
            declaredFields(
                """
                class ScreenActionsHolder {
                    var title: (@Composable () -> Unit)? = null
                    var actions: (@Composable RowScope.() -> Unit)? = null
                }
                """.trimIndent(),
            )

        val report = compareFields(declared, listOf("title"))

        assertEquals(listOf("actions"), report.unlisted)
        assertEquals(emptyList(), report.unknown)
    }

    @Test
    fun `a field the clear does not reset is reported`() {
        val cleared = clearedFields(HOLDER_CLEARING_TITLE_ONLY)
        val report = compareFields(cleared, listOf("title", "actions"))

        assertEquals(listOf("actions"), report.unknown)
        assertEquals(emptyList(), report.unlisted)
    }

    @Test
    fun `a listed field the holder does not declare is reported`() {
        val report = compareFields(declared = listOf("title"), listed = listOf("title", "phantom"))

        assertEquals(emptyList(), report.unlisted)
        assertEquals(listOf("phantom"), report.unknown)
    }

    // ── the rule ───────────────────────────────────────────────────────────────────

    private data class SourceFile(val path: String, val source: String)

    private data class WriteSite(
        val path: String,
        val line: Int,
        val field: String,
        val publisher: String?,
    )

    private data class Declaration(
        val name: String,
        val start: Int,
        val bodyEnd: Int,
    )

    private data class PublisherReport(
        val unregistered: Map<String, WriteSite>,
        val stale: Set<String>,
    )

    private data class FieldReport(
        val unlisted: List<String>,
        val unknown: List<String>,
    )

    /**
     * Every write to a published field, wherever it sits.
     *
     * The receiver must be a holder-typed name *in the enclosing declaration's
     * scope*, so a local `state.title = …` beside a holder is not a write to the
     * holder, and a holder name leaking out of one function does not excuse a
     * same-named variable in the next.
     */
    private fun writeSites(sources: List<SourceFile>): List<WriteSite> =
        sources.flatMap { file ->
            val code = KotlinText.stripComments(file.source)
            val declaredFunctions = declarations(code)
            FIELD_WRITE.findAll(code).mapNotNull { match ->
                val offset = match.range.first
                // The outermost declaration that contains the write is the publisher:
                // a local helper inside TopAppBar must not become a publisher of its
                // own, and a helper inside a screen must not hide the screen's write.
                val declaration =
                    declaredFunctions.firstOrNull { offset >= it.start && offset < it.bodyEnd }
                val scope = declaration?.let { code.substring(it.start, it.bodyEnd) } ?: code
                val receiver = match.groupValues[1].filterNot { it.isWhitespace() }
                if (receiver != LOCAL_HOLDER && receiver !in holderNamesIn(scope)) return@mapNotNull null
                WriteSite(
                    path = file.path,
                    line = code.take(offset).count { it == '\n' } + 1,
                    field = match.groupValues[2],
                    publisher = declaration?.name,
                )
            }
        }

    /** The writes the shared publishers do not own: what the gate fails on. */
    private fun violations(sites: List<WriteSite>): List<WriteSite> =
        sites.filter { it.publisher !in REGISTERED_PUBLISHERS }

    /** The tree's own answer to "which functions write the holder?". */
    private fun observedPublishers(sites: List<WriteSite>): Map<String, WriteSite> =
        sites
            .mapNotNull { site -> site.publisher?.let { it to site } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, publisherSites) -> publisherSites.minBy { it.line } }

    private fun comparePublishers(
        observed: Map<String, WriteSite>,
        registered: Set<String> = REGISTERED_PUBLISHERS,
    ): PublisherReport =
        PublisherReport(
            unregistered = observed.filterKeys { it !in registered },
            stale = registered - observed.keys,
        )

    private fun compareFields(
        declared: List<String>,
        listed: List<String>,
    ): FieldReport =
        FieldReport(
            unlisted = declared - listed.toSet(),
            unknown = listed - declared.toSet(),
        )

    /**
     * The `fun` declarations of [code], outermost first, with the range of their
     * bodies.
     *
     * Braces are matched by [SourceScan], so a brace inside a string does not end
     * a body early; the parameter list is found with [SourceScan.closingParen],
     * exactly as [SourceScan.methods] does.
     */
    private fun declarations(code: String): List<Declaration> =
        DECLARATION.findAll(code).mapNotNull { match ->
            val closeParen = SourceScan.closingParen(code, match.range.last)
            if (closeParen < 0) return@mapNotNull null
            var brace = closeParen + 1
            while (brace < code.length && code[brace] != '{' && code[brace] != '\n') brace++
            if (brace >= code.length || code[brace] != '{') return@mapNotNull null
            val body = SourceScan.bodyOf(code, brace) ?: return@mapNotNull null
            Declaration(
                name = match.groupValues[1],
                start = match.range.first,
                bodyEnd = brace + 1 + body.length,
            )
        }.toList()

    private fun holderNamesIn(scope: String): Set<String> =
        (
            HOLDER_DECLARATION.findAll(scope).map { it.groupValues[1] } +
                HOLDER_PARAMETER.findAll(scope).map { it.groupValues[1] }
        ).toSet()

    private fun declaredFields(source: String): List<String> =
        FIELD_DECLARATION.findAll(KotlinText.stripComments(source)).map { it.groupValues[1] }.toList()

    /**
     * The fields the holder's own `clear()` resets, read from its body.
     *
     * `publishedFields` is the list the gate excuses; `clear()` is the one write
     * that is supposed to reset all of it. Two lists of the same field names in one
     * class drift exactly the way any pair of lists does, and the drift is silent:
     * the shell clears five fields and the sixth stays on the next screen's bar.
     */
    private fun clearedFields(source: String): List<String> {
        val code = KotlinText.stripComments(source)
        val clear = declarations(code).firstOrNull { it.name == "clear" } ?: return emptyList()
        return FIELD_ASSIGNMENT.findAll(code.substring(clear.start, clear.bodyEnd))
            .map { it.groupValues[1] }
            .toList()
    }

    // ── the tree ───────────────────────────────────────────────────────────────────

    /**
     * The production sources that can reach the holder.
     *
     * `:shared` and `:shared-ui` are where the holder, the publishers and every
     * screen live; the two app modules compose those screens and do not declare
     * any of their own. Widening this is a two-root change with two more declared
     * inputs, and #2174 (a test source set for `:shared-ui`) is the other end of
     * the same line.
     */
    private fun allSources(): List<SourceFile> {
        val repo = repoRoot()
        val roots =
            listOf(
                propertyRoot("commonMain.root"),
                propertyRoot("androidMain.root"),
                propertyRoot("jvmMain.root"),
            ) + productionSourceSets(propertyRoot("sharedUiSources.root"))
        return roots
            .flatMap { dir ->
                assertTrue(dir.isDirectory, "a scan root is not a directory: ${dir.path}")
                dir.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .map { SourceFile(it.relativeTo(repo).invariantSeparatorsPath, it.readText()) }
                    .toList()
            }.distinctBy { it.path }
    }

    /**
     * `:shared-ui`'s production source sets, and only those.
     *
     * The root is the module because the holder and the publishers are in
     * `commonMain` while the platform shells that draw them are in
     * `androidMain`/`jvmMain`. A test source set is deliberately not scanned:
     * #2174 adds one, and a UI test that publishes into a holder of its own is
     * not a second way into the shell bar.
     */
    private fun productionSourceSets(moduleRoot: File): List<File> =
        listOf("commonMain", "androidMain", "jvmMain")
            .map { File(moduleRoot, "$it/kotlin") }
            .filter { it.isDirectory }
            .also { assertTrue(it.isNotEmpty(), "no production source set under ${moduleRoot.path}") }

    private fun repoRoot(): File = propertyRoot("commonMain.root").parentFile.parentFile.parentFile.parentFile

    private fun propertyRoot(name: String): File =
        File(
            System.getProperty(name)
                ?: error("$name is not set — see shared/build.gradle.kts"),
        )

    private companion object {
        /** The claim. The tree is the fact, and the test above compares the two. */
        val REGISTERED_PUBLISHERS = setOf("TopAppBar", "ShellOverride")

        /** The composition local's own receiver, spelled the way a write may spell it. */
        const val LOCAL_HOLDER = "LocalScreenActions.current"

        val FIELD_WRITE =
            Regex(
                """(?<![\w.])(LocalScreenActions\s*\.\s*current|[A-Za-z_]\w*)\s*\??\.\s*""" +
                    """(actions|title|navigationIcon|override|selectionTitle|onExitSelection)\s*=(?!=)""",
            )

        val HOLDER_DECLARATION =
            Regex(
                """\b(?:val|var)\s+(\w+)\s*(?::\s*ScreenActionsHolder\??)?\s*=\s*""" +
                    """(?:remember\s*\{\s*)?(?:LocalScreenActions\.current|ScreenActionsHolder\s*\()""",
            )

        val HOLDER_PARAMETER = Regex("""\b(\w+)\s*:\s*ScreenActionsHolder\??""")

        val FIELD_DECLARATION = Regex("""(?m)^[ \t]*var\s+(\w+)\s*:""")

        /** An unqualified assignment, the way `clear()` writes its own fields. */
        val FIELD_ASSIGNMENT = Regex("""(?m)^[ \t]*(\w+)\s*=(?!=)""")

        /**
         * A `fun` declaration, at line start after its annotations and modifiers.
         * The match ends on the `(` that opens the parameter list.
         */
        val DECLARATION =
            Regex(
                """(?m)^[ \t]*(?:@[\w.]+(?:\([^)]*\))?[ \t]*)*""" +
                    """(?:(?:public|private|internal|protected|override|suspend|""" +
                    """inline|tailrec|operator|infix|external)[ \t]+)*""" +
                    """fun[ \t]+(?:<[^>]+>[ \t]*)?(?:[\w.]+\.)?(\w+)[ \t]*\(""",
            )

        const val SCREEN_PATH = "shared-ui/src/commonMain/kotlin/com/singularity/todo/feature/widget/WidgetScreen.kt"

        const val HOLDER_PATH =
            "shared-ui/src/commonMain/kotlin/com/singularity/todo/core/ui/components/ScreenActions.kt"

        val SCREEN_WRITING_HOLDER =
            """
            package com.singularity.todo.feature.widget

            import com.singularity.todo.core.ui.components.LocalScreenActions

            @Composable
            fun WidgetScreen() {
                val holder = LocalScreenActions.current
                holder?.title = { Text("Widget") }
            }
            """.trimIndent()

        val SCREEN_READING_HOLDER =
            """
            package com.singularity.todo.feature.widget

            import com.singularity.todo.core.ui.components.LocalScreenActions

            @Composable
            fun WidgetScreen() {
                val holder = LocalScreenActions.current ?: return
                val title = holder.title
                val overrides = holder.override
            }
            """.trimIndent()

        val SCREEN_WRITING_ANOTHER_TITLE =
            """
            package com.singularity.todo.feature.widget

            import com.singularity.todo.core.ui.components.LocalScreenActions

            @Composable
            fun WidgetScreen(state: WidgetState) {
                val holder = LocalScreenActions.current
                val published = holder?.title
                state.title = "Widget"
            }
            """.trimIndent()

        val SCREEN_WRITING_THROUGH_THE_LOCAL =
            """
            package com.singularity.todo.feature.widget

            import com.singularity.todo.core.ui.components.LocalScreenActions

            @Composable
            fun WidgetScreen() {
                LocalScreenActions.current?.title = { Text("Widget") }
            }
            """.trimIndent()

        val HOLDER_CLEARING_ITSELF =
            """
            package com.singularity.todo.core.ui.components

            class ScreenActionsHolder {
                var title: (@Composable () -> Unit)? = null

                fun clear() {
                    title = null
                }
            }
            """.trimIndent()

        val HOLDER_CLEARING_TITLE_ONLY =
            """
            package com.singularity.todo.core.ui.components

            class ScreenActionsHolder {
                var title: (@Composable () -> Unit)? = null
                var actions: (@Composable RowScope.() -> Unit)? = null

                fun clear() {
                    title = null
                }
            }
            """.trimIndent()
    }
}
