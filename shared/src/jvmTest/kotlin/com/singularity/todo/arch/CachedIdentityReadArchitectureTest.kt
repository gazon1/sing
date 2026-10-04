package com.singularity.todo.arch

import java.io.File
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Forbids reading the *cached* identity flows from inside a reactive block.
 *
 * ## The defect this exists to prevent
 *
 * `ProfileAwareCurrentUser.scopedUserId` and `CurrentUser.currentUserId` are eager
 * `StateFlow`s seeded at construction and corrected by a collector that starts
 * asynchronously. `liveScopedUserId` / `liveUserId` are the cold derivations that
 * answer with the truth at subscription time (ADR
 * `2026-10-04-derived-identity-flows.md`).
 *
 * Reading `.value` once, imperatively, to scope a write is correct — a mutation must
 * pick one identity, and picking it at the moment of the write is the right design.
 * That is why 159 call sites across 43 files still do it, and why this test does not
 * forbid that.
 *
 * Reading `.value` *inside* a `combine` / `collect` / `map` is the bug: the value is
 * sampled once, when the block is built, and then reused for every later emission —
 * so a profile switch mid-stream keeps writing under the previous profile. The
 * concrete instance shipped: `ReadToolsProfileAwareTest` failed on
 * `testAndroidHostTest` and passed on `jvmTest`, because the two source sets schedule
 * the seeding collector differently.
 *
 * ## Why a hand-written scanner and not Konsist
 *
 * Konsist 0.17 has no declaration type for a lambda, so "is this expression lexically
 * inside a reactive block" is not expressible as a Konsist query. The scanner below
 * does it by brace matching, which is only trustworthy if it is itself tested — so
 * [scanner_catches_a_cached_read_inside_a_reactive_block] and its neighbours run the
 * same function over synthetic sources and pin both the positive and the negative.
 * A gate nobody can test is a gate nobody can trust.
 *
 * ## Scope and known limits
 *
 * Scans `commonMain` production sources, excluding `test/fakes`. Two limits are
 * deliberate: the scanner tracks braces, so a reactive call whose lambda opens on a
 * later line (`collect\n{ ... }`) is not detected, and it only knows the combinator
 * names in [REACTIVE_CALLS]. Both err toward silence. A silent gap is preferable to a
 * false positive that trains people to allowlist their way past the rule.
 */
@Tag("fast")
class CachedIdentityReadArchitectureTest {

    @Test
    fun scanner_catches_a_cached_read_inside_a_reactive_block() {
        val source = """
            fun watch(u: ProfileAwareCurrentUser): Flow<Task> = flow {
                val uid = u.scopedUserId.value
                emit(load(uid))
            }
        """.trimIndent()
        val findings = findCachedIdentityReadsInReactiveBlocks(source)
        assertEquals(1, findings.size, "expected the cached read to be flagged: $findings")
        assertTrue(findings.single().line.contains("scopedUserId"))
    }

    @Test
    fun scanner_flags_every_read_inside_one_reactive_block() {
        val source = """
            fun watch(u: ProfileAwareCurrentUser) = flow {
                val a = u.scopedUserId.value
                val b = u.localUserId.value
                emit(a to b)
            }
        """.trimIndent()
        assertEquals(2, findCachedIdentityReadsInReactiveBlocks(source).size)
    }

    @Test
    fun scanner_ignores_a_read_in_a_reactive_calls_argument_list() {
        """
        Documented gap, not an oversight. The read is evaluated once, when the flow is
        constructed — the same one-shot semantics as an imperative repository write. The
        price is that `combine(u.scopedUserId.value) { … }` is not flagged; such a
        combine is useless rather than wrong, because the sampled value can never change.
        """
        val source = """
            fun watch(u: ProfileAwareCurrentUser) = combine(u.scopedUserId.value) { it }
        """.trimIndent()
        assertTrue(findCachedIdentityReadsInReactiveBlocks(source).isEmpty())
    }

    @Test
    fun scanner_ignores_a_read_inside_a_launch_body() {
        """A launch body runs once: picking the identity there is the correct write."""
        val source = """
            private fun stop() {
                scope.launch {
                    repo.stopEntry(currentUser.scopedUserId.value)
                }
            }
        """.trimIndent()
        assertTrue(findCachedIdentityReadsInReactiveBlocks(source).isEmpty())
    }

    @Test
    fun scanner_allows_passing_the_flow_itself_as_an_argument() {
        """`combine(u.scopedUserId)` is the correct way to re-read on every emission."""
        val source = """
            fun watch(u: ProfileAwareCurrentUser) = combine(u.scopedUserId) { it }
        """.trimIndent()
        assertTrue(findCachedIdentityReadsInReactiveBlocks(source).isEmpty())
    }

    @Test
    fun scanner_ignores_an_imperative_read_outside_any_reactive_block() {
        """The one-shot read a repository does before a write is the supported pattern."""
        val source = """
            suspend fun upsert(currentUser: ProfileAwareCurrentUser, task: Task) {
                val userId = currentUser.scopedUserId.value
                dao.insert(task.copy(userId = userId))
            }
        """.trimIndent()
        assertTrue(findCachedIdentityReadsInReactiveBlocks(source).isEmpty())
    }

    @Test
    fun scanner_ignores_the_live_derivations() {
        val source = """
            fun watch(u: ProfileAwareCurrentUser) = flow {
                combine(u.liveScopedUserId) { uid -> uid }
            }
        """.trimIndent()
        assertTrue(
            findCachedIdentityReadsInReactiveBlocks(source).isEmpty(),
            "liveScopedUserId is the correct accessor inside a reactive block",
        )
    }

    @Test
    fun scanner_ignores_a_method_named_like_a_combinator() {
        """`repo.map { }` is a transform, but `remap()` is not — word boundaries matter."""
        val source = """
            fun helper(u: ProfileAwareCurrentUser) {
                remap(u.scopedUserId.value)
            }
        """.trimIndent()
        assertTrue(findCachedIdentityReadsInReactiveBlocks(source).isEmpty())
    }

    @Test
    fun scanner_ignores_reads_in_comments_and_kdoc() {
        val source = """
            /**
             * Do not read `u.scopedUserId.value` inside a combine.
             */
            fun helper(u: ProfileAwareCurrentUser) = u.scopedUserId.value
        """.trimIndent()
        assertTrue(findCachedIdentityReadsInReactiveBlocks(source).isEmpty())
    }

    @Test
    fun scanner_closes_the_block_at_the_matching_brace() {
        """A read after a completed reactive block is imperative again."""
        val source = """
            fun f(u: ProfileAwareCurrentUser): Flow<Int> = flow {
                combine(u.liveScopedUserId) { it }
            }
            fun g(u: ProfileAwareCurrentUser) = u.scopedUserId.value
        """.trimIndent()
        assertTrue(findCachedIdentityReadsInReactiveBlocks(source).isEmpty())
    }

    @Test
    fun production_sources_read_no_cached_identity_inside_a_reactive_block() {
        val root = System.getProperty("commonMain.root")
            ?: error(
                "commonMain.root system property is not set — " +
                    "see the jvmTest task config in shared/build.gradle.kts",
            )
        val findings = File(root).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.path.contains("${File.separator}test${File.separator}") }
            .flatMap { file ->
                findCachedIdentityReadsInReactiveBlocks(file.readText())
                    .map { finding -> file.name to finding }
            }
            .toList()

        if (findings.isNotEmpty()) {
            fail(
                buildString {
                    appendLine(
                        "Cached identity read inside a reactive block — use " +
                            "`liveScopedUserId` / `liveUserId` there, or hoist the " +
                            "read outside the block and close over the value:",
                    )
                    findings.forEach { (file, finding) ->
                        appendLine("  $file:${finding.lineNumber}  ${finding.line.trim()}")
                    }
                    appendLine()
                    appendLine("See ADR 2026-10-04-derived-identity-flows.md.")
                },
            )
        }
    }

    private data class Finding(val lineNumber: Int, val line: String)

    private companion object {
        /**
         * Call names whose trailing lambda **re-runs** — i.e. a reactive block.
         *
         * `launch` is deliberately absent: a `scope.launch { }` body executes once, and
         * a repository write that picks the identity at the moment of the write is the
         * pattern ADR `2026-10-04-derived-identity-flows.md` explicitly preserves.
         * Including it flagged `TaskTimeSlot.start/stop/createManual` — three correct
         * one-shot writes — which is how a gate teaches people to ignore it.
         */
        val REACTIVE_CALLS = setOf(
            "flow",
            "channelFlow",
            "combine",
            "combineStates",
            "flatMapLatest",
            "collect",
            "collectLatest",
            "onEach",
            "map",
            "filter",
            "transform",
            "mapNotNull",
            "distinctUntilChanged",
        )

        /**
         * The cached flows. The `live*` derivations are excluded by the negative
         * lookahead, which is enough: `liveScopedUserId` is a different identifier, so
         * no `\b` position inside it can start a match. A lookbehind for `.` here
         * would reject the *correct* `u.scopedUserId.value` too, because the receiver
         * dot looks identical to a `live` prefix.
         */
        val CACHED_IDENTITY = Regex(
            """\b(?!live)(scopedUserId|localUserId|currentUserId)\s*\??\.\s*value\b""",
        )

        /**
         * A reactive call whose trailing lambda opens a block. One optional
         * argument list is allowed between the name and the brace
         * (`combine(a, b) { … }`); a nested argument list is not, which makes the
         * matcher miss `map(f(x)) { … }` — documented as a deliberate gap.
         */
        // DOT_MATCHES_ALL lives on the Regex, not at the call site: String.replace has
        // no overload taking both options and a transform.
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("""//[^\n]*""")

        val REACTIVE_WITH_LAMBDA = Regex(
            """\b(${REACTIVE_CALLS.joinToString("|")})\s*(<[^<>]*>)?\s*(\([^()]*\))?\s*\{""",
        )

        fun stripComments(source: String): String = source
            .replace(BLOCK_COMMENT) { m ->
                // Keep the line count so reported line numbers still point at the source.
                "\n".repeat(m.value.count { it == '\n' })
            }
            .replace(LINE_COMMENT, "")

        fun findCachedIdentityReadsInReactiveBlocks(source: String): List<Finding> {
            val lines = stripComments(source).split("\n")
            // Stack of currently open reactive lambda bodies, keyed by the brace depth
            // they opened at. A block counts as open while depth is strictly greater.
            val openBlocks = ArrayDeque<Int>()
            val findings = mutableListOf<Finding>()
            var depth = 0

            lines.forEachIndexed { index, raw ->
                val opensBlock = REACTIVE_WITH_LAMBDA.containsMatchIn(raw)
                val braceAt = if (opensBlock) raw.indexOf('{') else -1
                val read = CACHED_IDENTITY.find(raw)

                if (read != null) {
                    // Inside a reactive body when the block opened on an earlier line, or
                    // when the read sits after this line's own `{`.
                    //
                    // A read in the call's *argument list* is deliberately not a finding:
                    // it is evaluated once, when the flow is constructed, which is the
                    // same one-shot semantics as an imperative repository write. The cost
                    // of that choice is a known gap — `combine(u.scopedUserId.value) { … }`
                    // samples a value that can never change and is therefore a useless
                    // combine rather than a wrong one. Benign, and not worth a false
                    // positive on every `dao.watchBy(id, scopedUserId.value)`.
                    val insideOpenBlock = openBlocks.isNotEmpty()
                    val insideThisBody = braceAt >= 0 && read.range.last > braceAt
                    if (insideOpenBlock || insideThisBody) {
                        findings += Finding(index + 1, raw)
                    }
                }

                if (opensBlock) openBlocks.addLast(depth)
                depth += raw.count { it == '{' } - raw.count { it == '}' }
                while (openBlocks.isNotEmpty() && depth <= openBlocks.last()) {
                    openBlocks.removeLast()
                }
            }
            return findings
        }
    }
}
