package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
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
 * ## The two shapes an enqueue takes
 *
 * Most are inline — `syncRepository.enqueue(item)` in the same `runCatchingCancellable`
 * body that wrote the row. Those must be inside `unitOfWork.write { … }`.
 *
 * The rest go through the private `enqueueFresh(id)` helper, which *reads* the row back
 * and enqueues it. The helper's own body is deliberately not wrapped: it writes nothing,
 * and the write it describes happened in the caller. What matters is that **every caller
 * of `enqueueFresh` is wrapped** — and that is checked here rather than assumed, because
 * it is the one place a write and its patch can drift apart without any lexical sign.
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
            walk(file)
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
        // The half that a relaxed version of the rule above would have skipped. `enqueueFresh`
        // writes nothing itself, so its body needs no transaction — but a caller that is not
        // wrapped is exactly the split this ADR describes, reached through a function call
        // rather than a visible pair of statements.
        val offenders = repositoryFiles().flatMap { file ->
            walk(file)
                .filter { it.kind == Kind.HELPER_CALL && !it.insideWrite }
                .map { "${file.name}:${it.line}: ${it.text} calls the enqueue helper outside a unitOfWork.write" }
        }

        if (offenders.isNotEmpty()) {
            fail(
                "The enqueue helper is called outside a unit of work:\n" +
                    offenders.joinToString("\n") { "  $it" } +
                    "\n\nThe write and the patch it describes can then be committed apart.",
            )
        }
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
            class RepositoryImpl {
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

        assertTrue(
            inline.size == 1 && inline.single().text.contains("syncRepository.enqueue(inlineCall)"),
            "expected only the unwrapped inline enqueue, got ${inline.map { it.text }}",
        )
        assertTrue(
            helper.size == 1 && helper.single().text.contains("enqueueFresh(id)"),
            "expected only the unwrapped helper call, got ${helper.map { it.text }}",
        )
        assertTrue(
            helperBody.isEmpty(),
            "the helper's own body was not excluded: ${helperBody.map { it.text }}",
        )
    }

    // ── source walking ─────────────────────────────────────────────────────────────

    private enum class Kind { INLINE_ENQUEUE, HELPER_CALL, HELPER_BODY }

    private data class Site(
        val kind: Kind,
        val line: Int,
        val text: String,
        val insideWrite: Boolean,
    )

    private fun repositoryFiles(): List<File> {
        val root = System.getProperty("commonMain.root")
            ?: error("commonMain.root system property is not set — see the jvmTest task config")
        return File(root, "com/singularity/todo/feature")
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith("RepositoryImpl.kt") }
            .toList()
    }

    private fun walk(file: File): List<Site> = walkOn(file.readText())

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
    private fun walkOn(source: String): List<Site> {
        val sites = mutableListOf<Site>()
        val openBlocks = ArrayDeque<Boolean>()
        var inHelperBody = false

        source.split("\n").forEachIndexed { index, raw ->
            val line = raw.substringBefore("//")
            val trimmed = line.trim()

            // The helper's own body is exempt, and only while we are inside it.
            //
            // The first version returned early only on the closing brace, so every line
            // *between* the declaration and that brace was still classified — and the
            // helper's own `syncRepository.enqueue` was reported as an unwrapped patch
            // in all six repositories. A body that is exempt has to stop being classified
            // on its first line, not on its last.
            if (inHelperBody && trimmed == "}") {
                inHelperBody = false
                openBlocks.removeLastOrNull()
                return@forEachIndexed
            }
            val isHelperDeclaration = trimmed.startsWith("private suspend fun $HELPER(")
            if (isHelperDeclaration) {
                inHelperBody = true
            }

            if (!inHelperBody || isHelperDeclaration) {
                val kind = when {
                    isHelperDeclaration -> Kind.HELPER_BODY
                    "$HELPER(" in trimmed -> Kind.HELPER_CALL
                    "syncRepository.enqueue" in trimmed -> Kind.INLINE_ENQUEUE
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

    private companion object { const val HELPER = "enqueueFresh" }
}