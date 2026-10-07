package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every patch is enqueued inside a unit of work (ADR
 * `2026-10-05-who-owns-a-row-and-the-patch-that-describes-it`).
 *
 * ## Why a static rule and not just the atomicity test
 *
 * `UnitOfWorkIsAtomicTest` proves the *port* rolls back — two writes, a throw between
 * them, neither survives — against a real database, because that is the only place the
 * property is observable.
 *
 * It cannot prove that any particular repository *uses* it. The repository tests take
 * `FakeUnitOfWork`, which runs the block and does not roll back; they prove the row and
 * the patch both happened, in order. A method that dropped its `unitOfWork.write { … }`
 * wrapper would keep every one of them green while reintroducing the exact split the ADR
 * exists to prevent.
 *
 * That gap is why this file exists. The wrappers were applied by hand to thirty-seven
 * methods, and nothing afterwards would notice one being removed in a later refactor. A
 * wrapper that can be deleted without a test failing is a wrapper that will be.
 *
 * ## The vocabulary is derived, never written down
 *
 * The first version matched two literals: `syncRepository.enqueue` and `enqueueFresh(`.
 * That is a rule that reports green the moment the codebase spells the same call a third
 * way — and the codebase already contains a third spelling
 * (`SyncRepositoryImpl.enqueue` → `engine.enqueue`), which is why the hole was
 * demonstrable rather than theoretical.
 *
 * So both names are read from each file instead:
 *
 * - the receiver is the constructor property declared as `SyncRepository`, wherever the
 *   author happened to name it;
 * - the helper names are the `private … fun`s whose own body performs an enqueue.
 *
 * A file that renames its dependency is still checked, and a new helper is picked up
 * without editing this test. The cost is that a *misnamed* helper that does not enqueue
 * is not exempt — which is the safe direction: an over-reported file is one line of
 * `@Suppress`, an under-reported one is a shipped split.
 *
 * ## The two shapes an enqueue takes
 *
 * Most are inline — `<receiver>.enqueue(item)` in the same `runCatchingCancellable`
 * body that wrote the row. Those must be inside `unitOfWork.write { … }`.
 *
 * The rest go through a private helper which *reads* the row back and enqueues it. The
 * helper's own body is deliberately not wrapped: it writes nothing, and the write it
 * describes happened in the caller. What matters is that **every caller of the helper is
 * wrapped** — and that is checked here rather than assumed, because it is the one place a
 * write and its patch can drift apart without any lexical sign.
 *
 * So this is two checks over the same walk, not one relaxed check: inline enqueues must be
 * wrapped, and helper calls must be wrapped. Skipping the helper body without checking its
 * callers would have silenced five real files and guarded nothing.
 */
@Tag("fast")
class SyncWriteIsAtomicTest {

    @Test
    fun `every patch enqueue is inside a unit of work`() {
        val files = repositoryFiles()
        assertTrue(files.isNotEmpty(), "no repository file found — the path this test reads has moved")

        val offenders = files.flatMap { file ->
            sitesIn(file)
                .filter { it.kind != Kind.HELPER_BODY && !it.insideWrite }
                .map { "${file.name}:${it.line}: ${it.text} is not inside a unitOfWork.write" }
        }

        if (offenders.isNotEmpty()) {
            fail(
                "A patch is enqueued outside a unit of work, so the row and the patch that " +
                    "describes it can be committed apart:\n" +
                    offenders.joinToString("\n") { "  $it" } +
                    "\n\nWrap the write and the enqueue in one `unitOfWork.write { … }`. The " +
                    "atomicity test cannot see this — repository tests take a pass-through " +
                    "UnitOfWork, so a missing wrapper leaves every one of them green.",
            )
        }
    }

    @Test
    fun `every call of the enqueue helper is inside a unit of work`() {
        // The half that a relaxed version of the rule above would have skipped. A helper
        // writes nothing itself, so its body needs no transaction — but a caller that is not
        // wrapped is exactly the split this ADR describes, reached through a function call
        // rather than a visible pair of statements.
        val offenders = repositoryFiles().flatMap { file ->
            sitesIn(file)
                .filter { it.kind == Kind.HELPER_CALL && !it.insideWrite }
                .map {
                    "${file.name}:${it.line}: ${it.text} calls the enqueue helper outside a " +
                        "unitOfWork.write"
                }
        }

        if (offenders.isNotEmpty()) {
            fail(
                "The enqueue helper is called outside a unit of work:\n" +
                    offenders.joinToString("\n") { "  $it" } +
                    "\n\nThe write and the patch it describes can then be committed apart.",
            )
        }
    }

    @Test
    fun `every file that enqueues has a dependency shape this rule can read`() {
        // The check that makes the derivation above honest, and the one whose absence is
        // how the previous version of this file was already broken.
        //
        // The rule recognises enqueues by a name it reads out of each file. If it cannot
        // read that name the rule classifies nothing and reports a clean tree — which is
        // the same shape as a gate whose positive control passes on a sabotaged input: not
        // a wrong answer, no answer at all.
        //
        // Two ways that happens, both real, both silent until this test exists:
        //
        //  - the repository injects SyncRepository in a shape the pattern cannot match, so
        //    every enqueue in it goes unchecked;
        //  - a repository starts enqueuing without declaring the port at all (through the
        //    engine, or a service), which is worth knowing regardless.
        //
        // The synthetic control below is the second case, and it is the one that bit this
        // file first: its own control source declared no constructor, so the vocabulary was
        // empty, so the walk found nothing — and the two checks above passed on it.
        val problems = repositoryFiles().flatMap { file ->
            val source = file.readText()
            val enqueues = ".enqueue(" in source
            val readable = vocabulary(source).receiver.isNotEmpty()

            // Only files that actually enqueue are in scope. A file that merely imports
            // SyncRepository, or mentions it in a KDoc, is a repository this rule has
            // nothing to say about — and saying something about it would train everyone to
            // ignore the message.
            if (enqueues && !readable) {
                listOf(
                    "${file.name}: calls .enqueue( but exposes no readable " +
                        "`private val …: SyncRepository`, so this rule cannot attribute the " +
                        "call — and neither can a reader",
                )
            } else {
                emptyList()
            }
        }

        if (problems.isNotEmpty()) {
            fail(
                "The rule cannot account for these files, so the two checks above pass on a " +
                    "tree that may contain unwrapped patches:\n" +
                    problems.joinToString("\n") { "  $it" } +
                    "\n\nSilence here is worse than a false positive.",
            )
        }
    }

    @Test
    fun `the rule reads at least one repository today`() {
        // A file set that resolved to zero would make all three checks above pass for free.
        val readers = repositoryFiles().count { vocabulary(it.readText()).receiver.isNotEmpty() }
        assertTrue(
            readers > 0,
            "no scanned repository exposes a SyncRepository dependency — the glob or the " +
                "pattern has moved, and every check in this class now passes vacuously",
        )
    }

    // ── the control that keeps the checker honest ───────────────────────────────────

    @Test
    fun `the walk tells wrapped from unwrapped, and skips the helper body`() {
        // A reader cannot tell from prose whether the brace walk works, and a walk that
        // never fires is indistinguishable from one that is not looking. Synthetic source,
        // so the answer is known before it is asked for — and it covers all three cases,
        // because the first version of this walk reported a wrapped call and passed a tree
        // that had an unwrapped one.
        val found = walkOn(
            """
            class RepositoryImpl(
                private val syncRepository: SyncRepository,
            ) {
                override suspend fun wrappedInline(a: Int) {
                    unitOfWork.write {
                        syncRepository.enqueue(inlineCall)
                    }
                }

                override suspend fun unwrappedInline(a: Int) {
                    dao.upsert(a)
                    syncRepository.enqueue(inlineCall)
                }

                override suspend fun wrappedHelper(a: Int) {
                    unitOfWork.write {
                        enqueueFresh(id)
                    }
                }

                override suspend fun unwrappedHelper(a: Int) {
                    enqueueFresh(id)
                }

                private suspend fun enqueueFresh(id: Int) {
                    val row = dao.get(id) ?: return
                    syncRepository.enqueue(row)
                }
            }
            """.trimIndent(),
        )

        val inline = found.filter { it.kind == Kind.INLINE_ENQUEUE && !it.insideWrite }
        val helper = found.filter { it.kind == Kind.HELPER_CALL && !it.insideWrite }
        val helperBody = found.filter { it.kind == Kind.HELPER_BODY }

        assertEquals(
            listOf("syncRepository.enqueue(inlineCall)"),
            inline.map { it.text },
            "expected only the unwrapped inline enqueue",
        )
        assertEquals(
            listOf("enqueueFresh(id)"),
            helper.map { it.text },
            "expected only the unwrapped helper call",
        )
        assertTrue(
            helperBody.isEmpty(),
            "the helper's own body was not excluded: ${helperBody.map { it.text }}",
        )
    }

    @Test
    fun `the vocabulary follows whatever the file calls its dependency and helper`() {
        // The reason this rule does not spell its names down. A checker that only knows
        // `syncRepository.enqueue` and `enqueueFresh(` reports a clean tree the day a
        // repository renames either — and `SyncRepositoryImpl` already writes `engine.enqueue`,
        // which is what made the gap demonstrable instead of theoretical.
        val source = """
            class RepositoryImpl(
                private val outbound: SyncRepository,
            ) {
                override suspend fun unwrappedInline(a: Int) {
                    outbound.enqueue(inlineCall)
                }

                override suspend fun unwrappedHelper(a: Int) {
                    queuePatch(id)
                }

                private suspend fun queuePatch(id: Int) {
                    outbound.enqueue(row)
                }
            }
        """.trimIndent()

        assertEquals("outbound", vocabulary(source).receiver, "receiver not derived from the constructor")

        val found = walkOn(source)
        assertTrue(
            found.isNotEmpty(),
            "the walk produced no sites at all for this source, so nothing below is about " +
                "renaming — the derivation or the walk itself is broken:\n$source",
        )
        assertEquals(
            listOf("outbound.enqueue(inlineCall)"),
            found.filter { it.kind == Kind.INLINE_ENQUEUE }.map { it.text },
            "the renamed receiver was not recognised",
        )
        assertEquals(
            listOf("queuePatch(id)"),
            found.filter { it.kind == Kind.HELPER_CALL }.map { it.text },
            "the renamed helper was not recognised",
        )
        assertTrue(
            found.none { it.kind == Kind.HELPER_BODY },
            "the renamed helper's own body was not excluded",
        )
    }

    // ── source walking ─────────────────────────────────────────────────────────────

    private enum class Kind { INLINE_ENQUEUE, HELPER_CALL, HELPER_BODY }

    private data class Site(val kind: Kind, val line: Int, val text: String, val insideWrite: Boolean)

    private data class Vocabulary(val receiver: String, val helpers: Set<String>)

    private fun repositoryFiles(): List<File> {
        val root = System.getProperty("commonMain.root")
            ?: error("commonMain.root system property is not set — see the jvmTest task config")
        return File(root, "com/singularity/todo/feature")
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith("RepositoryImpl.kt") }
            .toList()
    }

    private fun sitesIn(file: File): List<Site> = walkOn(file.readText())

    /**
     * The two names this file needs, read from the file rather than assumed.
     *
     * A repository that does not inject `SyncRepository` at all is not a repository this
     * rule has anything to say about, so it gets an empty vocabulary rather than a guess.
     */
    private fun vocabulary(source: String): Vocabulary {
        val receiver = Regex("""private val (\w+)\s*:\s*SyncRepository""")
            .find(source)?.groupValues?.get(1)
            ?: return Vocabulary("", emptySet())

        val enqueueCall = "$receiver.enqueue("
        val lines = source.split("\n")
        val helpers = lines.mapIndexedNotNull { index, raw ->
            val match = Regex("""private .*fun (\w+)\(""").find(raw.trim()) ?: return@mapIndexedNotNull null
            // The body runs to the next line that closes at the declaration's own
            // indentation. That is the same shape the walk's exemption already assumes, so
            // the two cannot disagree about where a function ends.
            val indent = raw.takeWhile { it == ' ' }
            val end = lines.indexOfFirstFrom(index + 1) { it == "$indent}" }
            val body = lines.subList(index + 1, if (end < 0) lines.size else end)
            if (body.any { enqueueCall in it }) match.groupValues[1] else null
        }.toSet()

        return Vocabulary(receiver, helpers)
    }

    private inline fun <T> List<T>.indexOfFirstFrom(start: Int, predicate: (T) -> Boolean): Int =
        indices.firstOrNull { it >= start && predicate(this[it]) } ?: -1

    /**
     * LIFO over open braces, asking whether any open block is a `unitOfWork.write`.
     *
     * Two earlier versions were wrong in opposite directions, and both mattered more than
     * the rule they were implementing:
     *
     * - a running depth counter, where a class body and each method accumulated, so a call
     *   inside a transaction read as depth 3 and one in an unwrapped method as depth 1;
     *   neither is 0 and every method looked wrapped;
     * - a "pop while the recorded depth is at or below the current one" fix, where a line
     *   beginning with `}` drives the counter to −1, `1 <= -1` is false, and no block ever
     *   closes — the first `unitOfWork.write` in the file then covered every later enqueue.
     *
     * A `}` closes the most recent `{` and nothing else. That is the whole rule, and it is
     * what lets the answer to "is one of the blocks we are inside a transaction" be asked
     * directly instead of inferred from a number.
     */
    private fun walkOn(source: String): List<Site> = walk(source, vocabulary(source))

    /**
     * The vocabulary is passed in rather than derived here.
     *
     * It was `val vocabulary = vocabulary(source)` inside this function, and the local of
     * the same name as a member function is a trap: the derivation and the walk that
     * depends on it stop being the same expression, and the two controls that call
     * `vocabulary(source)` directly kept passing while every walk silently classified
     * nothing — which is exactly the failure mode this file exists to prevent, arrived at
     * through the fix for it. One derivation, passed in, cannot disagree with itself.
     */
    private fun walk(source: String, vocabulary: Vocabulary): List<Site> {
        // Concatenated rather than templated. Written as `"$vocabulary.receiver.enqueue("`
        // first, and that string did not match the call it was meant to match: the walk
        // classified every helper call and no inline enqueue at all, on real files and on
        // the controls alike, while the vocabulary it was handed was correct — which is why
        // the failure read as "no enqueues found" and pointed at the rule instead of at the
        // line that built the needle.
        //
        // The reason is not recorded because it was not established, and a comment that
        // invents one is worse than a comment that says so. What is worth keeping is the
        // shape of the failure: the two derivations were correct and the composition of
        // them was not, so "the tests say it is broken" was true of a thing nobody was
        // looking at.
        val enqueueCall = vocabulary.receiver + ".enqueue("

        val sites = mutableListOf<Site>()
        val openBlocks = ArrayDeque<Boolean>()
        var inHelperBody = false

        source.split("\n").forEachIndexed { index, raw ->
            val line = raw.substringBefore("//")
            val trimmed = line.trim()

            // A helper's own body is exempt, and only while we are inside it.
            //
            // The first version returned early only on the closing brace, so every line
            // *between* the declaration and that brace was still classified — and the
            // helper's own enqueue was reported as an unwrapped patch in all six
            // repositories. A body that is exempt has to stop being classified on its
            // first line, not on its last.
            if (inHelperBody && trimmed == "}") {
                inHelperBody = false
                openBlocks.removeLastOrNull()
                return@forEachIndexed
            }
            val isHelperDeclaration = vocabulary.helpers.any { trimmed.startsWith("private ") && "fun $it(" in trimmed }
            if (isHelperDeclaration) {
                inHelperBody = true
            }

            if (!inHelperBody || isHelperDeclaration) {
                val kind = when {
                    isHelperDeclaration -> Kind.HELPER_BODY
                    vocabulary.helpers.any { "$it(" in trimmed } -> Kind.HELPER_CALL
                    enqueueCall in trimmed -> Kind.INLINE_ENQUEUE
                    else -> null
                }
                if (kind != null && kind != Kind.HELPER_BODY) {
                    sites += Site(kind, index + 1, trimmed, openBlocks.any { it })
                }
            }

            for (ch in line) {
                when (ch) {
                    '{' -> openBlocks.addLast("unitOfWork.write" in line)
                    '}' -> openBlocks.removeLastOrNull()
                }
            }
        }
        return sites
    }
}
