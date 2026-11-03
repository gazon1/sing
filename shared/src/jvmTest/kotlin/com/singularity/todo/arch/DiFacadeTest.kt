package com.singularity.todo.arch

import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The DI-facade invariant from
 * `docs/decisions/2026-09-27-di-module-aggregator-narrative.md`, made executable.
 *
 * `core/di/Modules.kt` is a **facade aggregator**, not a source of truth: it composes the
 * per-domain `*DiModule.kt` functions and registers the few bindings that must land at
 * root scope. The rule is that a `single { }`, `factory { }` or `viewModel { }` declaration
 * lives inside a function that builds a module — never loose in the file body. In the
 * aggregator that function is one of the facades; in a per-domain `*DiModule.kt` it is
 * the domain's own `*Module()` factory.
 *
 * ## Why a rule that passes on its first run
 *
 * The invariant already holds; nothing needed fixing. This test exists because the project
 * has a run of guards that were inactive without anyone noticing — seven custom rulesets
 * with no `detekt.yml` block, a provider missing from the ServiceLoader file, a rule whose
 * only finding was credited in an ADR it never produced. A test that passes now is the
 * case working as intended: it makes the invariant fail loudly the first time it is
 * violated, instead of leaving a reviewer to notice.
 *
 * That is also why [positiveControlDetectsLooseBinding] is here. A guard that has never
 * been seen to fail is not known to work.
 *
 * Scope: `shared/src/commonMain/kotlin` production sources.
 */
@Tag("fast")
class DiFacadeTest {
    companion object {
        private const val PKG = "com.singularity.todo"

        private val scope = CommonMainKonsistScope.scope

        /**
         * Functions in [Modules.kt] that are allowed to declare bindings.
         *
         * `domainModule` and `coreLoggingModule` are the two documented facades.
         * `aiToolsModule` is included because it is an `expect` declaration in the same
         * file whose platform actuals are the ones that declare the tools.
         */
        private val FACADE_FUNCTIONS = setOf("domainModule", "coreLoggingModule", "aiToolsModule")

        /** Koin declarations that must not appear loose in a DI file. */
        private val BINDING_CALLS = setOf("single", "factory", "viewModel", "singleOf", "factoryOf", "viewModelOf")

        /**
         * Suffixes that mean an expression continues on the next line.
         *
         * The shape ktlint's multiline-expression-wrapping produces: a trailing
         * operator, or a trailing comma of an argument list that is still open.
         */
        private val CONTINUATION_SUFFIXES = listOf(",", "+", "-", "*", "/", "&&", "||", "?:", ":")

        /** Package name derived from the file path, as [ArchitectureTest] does. */
        private fun KoFileDeclaration.packageName(): String {
            val normalizedPath = path.replace('\\', '/')
            val normalizedRoot = CommonMainKonsistScope.commonMainRoot.replace('\\', '/').trimEnd('/')
            return normalizedPath
                .removePrefix("$normalizedRoot/")
                .substringBeforeLast('/')
                .replace('/', '.')
        }

        /**
         * The facade aggregator, resolved lazily so a lookup failure surfaces as a test
         * failure with a readable message rather than an initializer crash.
         */
        private val modulesFile: KoFileDeclaration by lazy {
            val candidates = scope.files.filter { it.packageName() == "$PKG.core.di" }
            val match = candidates.filter { it.name == "Modules" }
            if (match.size != 1) {
                error(
                    "expected exactly one core/di/Modules.kt, found ${match.size} among " +
                        candidates.map { it.name }.sorted(),
                )
            }
            match.single()
        }
    }

    /**
     * Positive control: the detector below is proven able to fail.
     *
     * Runs the same scan over a synthetic facade file that *does* declare a binding
     * outside its facade function. If this ever passes, the rule has stopped detecting
     * and every other test in this class is vacuous.
     */
    @Test
    fun positiveControlDetectsLooseBinding() {
        val synthetic =
            """
            package com.example

            fun coreLoggingModule() = module {
                factory { Logger.withTag("App") }
            }

            fun strayHelper() {
                single { SomethingElse(get()) }
            }
            """.trimIndent()

        val strayDeclarations = bindingDeclarationsIn(synthetic, facadeNames = setOf("coreLoggingModule"))

        if (strayDeclarations.isEmpty()) {
            fail(
                "the facade scan found nothing in a file that declares a binding outside a " +
                    "facade function — the rule is no longer detecting, so every other " +
                    "assertion in DiFacadeTest is vacuous",
            )
        }
        kotlin.test.assertEquals(1, strayDeclarations.size, "expected the one stray binding")
    }

    /**
     * Positive control: the same detection, against a facade whose body ktlint put on
     * the line BELOW the `=`.
     *
     * This is the layout the scan has to survive: the facade name is read on one line,
     * the body arrives on the next. A scan that ends the body at the signature line
     * sees a stray binding after the facade as belonging to the facade and lets it
     * through — the guard stops guarding exactly where the code was reformatted.
     */
    @Test
    fun positiveControlDetectsLooseBindingAfterABodyOnTheNextLine() {
        val synthetic =
            """
            package com.example

            fun coreLoggingModule(): Module =
                module {
                    factory { Logger.withTag("App") }
                }

            fun strayHelper() {
                single { SomethingElse(get()) }
            }
            """.trimIndent()

        val strayDeclarations = bindingDeclarationsIn(synthetic, facadeNames = setOf("coreLoggingModule"))

        if (strayDeclarations.isEmpty()) {
            fail(
                "a binding declared after a facade whose body sits on the next line was not " +
                    "reported — the scan ends the facade body at the signature line, so the " +
                    "binding is credited to the facade and the guard is dead",
            )
        }
        kotlin.test.assertEquals(1, strayDeclarations.size, "expected the one stray binding")
    }

    /**
     * Positive control: a facade whose body is an expression with no braces at all.
     *
     * `fun domainModule(): List<Module> = compute()` has no block to follow, so the
     * body ends on the signature line. A scan that waits for a block never closes
     * that body, credits the next top-level binding to the facade, and lets it
     * through.
     *
     * The binding below is loose in the FILE BODY — not inside another function.
     * That is the shape that tells the two apart: a binding inside a following
     * function is reported by a scan that closes the facade AND by a scan that
     * does not, so it cannot tell whether the hole is closed.
     */
    @Test
    fun positiveControlDetectsLooseBindingAfterABracelessFacade() {
        val synthetic =
            """
            package com.example

            fun coreLoggingModule(): Module = compute()

            single { SomethingElse(get()) }
            """.trimIndent()

        val strayDeclarations = bindingDeclarationsIn(synthetic, facadeNames = setOf("coreLoggingModule"))

        if (strayDeclarations.isEmpty()) {
            fail(
                "a binding declared after a brace-less facade was not reported — the scan " +
                    "keeps the facade open forever, so the binding is credited to the facade " +
                    "and the guard is dead",
            )
        }
        kotlin.test.assertEquals(1, strayDeclarations.size, "expected the one stray binding")
        // The owner is part of the finding, not decoration: the per-domain half of the
        // guard reads exactly this owner to tell a file-level binding from one that
        // sits inside a module function.
        kotlin.test.assertTrue(
            strayDeclarations.single().endsWith("<file body>"),
            "a binding loose in the file body is owned by the file body, not by a function",
        )
    }

    /**
     * Positive control: a binding after a facade whose body is a wrapped CALL.
     *
     * The call closes the facade, and what follows it is at file level again — so
     * the stray binding has to be reported.
     */
    @Test
    fun positiveControlDetectsLooseBindingAfterAWrappedCallFacade() {
        val synthetic =
            """
            package com.example

            fun coreLoggingModule(): Module =
                module(
                    factory { Logger.withTag("App") },
                )

            fun strayHelper() {
                single { SomethingElse(get()) }
            }
            """.trimIndent()

        val strayDeclarations = bindingDeclarationsIn(synthetic, facadeNames = setOf("coreLoggingModule"))

        if (strayDeclarations.isEmpty()) {
            fail(
                "a binding declared after a facade whose body is a wrapped call was not " +
                    "reported — the facade is closed by the call, and the binding after it " +
                    "is loose",
            )
        }
        kotlin.test.assertEquals(1, strayDeclarations.size, "expected the one stray binding")
    }

    /**
     * Negative control: a facade whose body sits on the next line and declares a
     * binding — that binding is inside the facade and must not be reported.
     *
     * The positive controls above would also pass if the scan reported everything.
     * This one fails when it does.
     */
    @Test
    fun aFacadeWithItsBodyOnTheNextLineReportsNothing() {
        val synthetic =
            """
            package com.example

            fun coreLoggingModule(): Module =
                module {
                    factory { Logger.withTag("App") }
                }
            """.trimIndent()

        val strayDeclarations = bindingDeclarationsIn(synthetic, facadeNames = setOf("coreLoggingModule"))

        kotlin.test.assertEquals(
            emptyList<String>(),
            strayDeclarations,
            "a binding declared inside a facade is not a stray binding",
        )
    }

    /**
     * Negative control: a facade whose body is a wrapped CALL, not a block.
     *
     * `= module(` opens a parenthesis that closes two lines later, and the bindings
     * between them are inside the facade. A scan that closes the facade as soon as
     * the braces balance reports them as loose — and a gate that cries wolf gets
     * allowlisted.
     */
    @Test
    fun aFacadeWhoseBodyIsAWrappedCallReportsNothing() {
        val synthetic =
            """
            package com.example

            fun coreLoggingModule(): Module =
                module(
                    factory { Logger.withTag("App") },
                    single { SomethingElse(get()) },
                )
            """.trimIndent()

        val strayDeclarations = bindingDeclarationsIn(synthetic, facadeNames = setOf("coreLoggingModule"))

        kotlin.test.assertEquals(
            emptyList<String>(),
            strayDeclarations,
            "bindings inside a facade's wrapped call belong to the facade",
        )
    }

    /**
     * Negative control: a comment between the `=` and the body.
     *
     * A comment is not the end of the body. A scan that ends it there loses the
     * facade and reports the binding inside the body as loose.
     */
    @Test
    fun aCommentBetweenTheSignatureAndTheBodyReportsNothing() {
        val synthetic =
            """
            package com.example

            fun coreLoggingModule(): Module =
                // the module the logging setup lives in
                module {
                    factory { Logger.withTag("App") }
                }
            """.trimIndent()

        val strayDeclarations = bindingDeclarationsIn(synthetic, facadeNames = setOf("coreLoggingModule"))

        kotlin.test.assertEquals(
            emptyList<String>(),
            strayDeclarations,
            "a comment does not end the body of the facade it sits in",
        )
    }

    /**
     * Positive control: a facade whose body carries a string with a brace and a paren.
     *
     * The scanner counts braces and parentheses to know where a body ends, and a
     * string literal carries characters that are not code. Counting the `(` of
     * `"App ( {"` leaves the facade open forever, so the binding after it is credited
     * to the facade and the guard misses exactly what it exists to catch.
     */
    @Test
    fun aStrayBindingAfterAFacadeWithBracesInAStringIsReported() {
        val synthetic =
            """
            package com.example

            fun coreLoggingModule(): Module =
                module {
                    factory { Logger.withTag("App ( {") }
                }

            single { SomethingElse(get()) }
            """.trimIndent()

        val strayDeclarations = bindingDeclarationsIn(synthetic, facadeNames = setOf("coreLoggingModule"))

        kotlin.test.assertEquals(
            1,
            strayDeclarations.size,
            "a binding after a facade whose body carries a string with braces is loose",
        )
    }

    /**
     * Positive control: a binding declared with an explicit type argument.
     *
     * `single<Repo> { RepoImpl(get()) }` is how most of the bindings in this
     * repository are written — 87 declarations across 19 files. A scan that reads
     * the call name as "everything before the first space" sees `single<Repo>`,
     * which is not a binding call, and reports nothing at all. The guard then
     * passes on the very files it was written for.
     */
    @Test
    fun aGenericBindingLooseInTheFileBodyIsReported() {
        val synthetic =
            """
            package com.example

            single<Repo> { RepoImpl(get()) }
            """.trimIndent()

        val strayDeclarations = bindingDeclarationsIn(synthetic, facadeNames = emptySet())

        kotlin.test.assertEquals(
            1,
            strayDeclarations.size,
            "a binding declared with an explicit type argument is a binding, and it is " +
                "loose in the file body",
        )
        kotlin.test.assertTrue(
            strayDeclarations.single().endsWith("<file body>"),
            "a binding loose in the file body is owned by the file body",
        )
    }

    /**
     * Positive control: a URL inside a string literal.
     *
     * The file-level cleanup used to cut `//` to the end of the line wherever it
     * found one — including inside a literal. `Logger.withTag("http://example.com")`
     * lost its closing quote and its closing brace, the facade never closed, and the
     * binding after it was credited to the facade: the scan reported nothing.
     *
     * A URL in a log tag is the least exotic string a DI file can carry, so the
     * cleanup has to read literals as literals.
     */
    @Test
    fun aUrlInsideAStringDoesNotBreakTheScan() {
        val synthetic =
            """
            package com.example

            fun coreLoggingModule(): Module =
                module {
                    factory { Logger.withTag("http://example.com") }
                }

            single { SomethingElse(get()) }
            """.trimIndent()

        val strayDeclarations = bindingDeclarationsIn(synthetic, facadeNames = setOf("coreLoggingModule"))

        kotlin.test.assertEquals(
            1,
            strayDeclarations.size,
            "a binding after a facade whose body carries a URL is loose — the cleanup " +
                "must not cut the literal at its `//`",
        )
        kotlin.test.assertTrue(
            strayDeclarations.single().endsWith("<file body>"),
            "the binding after the facade is loose in the file body",
        )
    }

    @Test
    fun `Modules kt declares no binding outside a facade function`() {
        val stray = bindingDeclarationsIn(modulesFile.text, FACADE_FUNCTIONS)
        if (stray.isNotEmpty()) {
            fail(
                "core/di/Modules.kt declares Koin bindings outside a facade function. " +
                    "It is a facade aggregator, not a source of truth — put the binding in " +
                    "the owning feature's *DiModule.kt, or wrap it in one of " +
                    "$FACADE_FUNCTIONS. Offenders: $stray",
            )
        }
    }

    /**
     * A per-domain `*DiModule.kt` declares bindings — the ADR puts them there — but it
     * declares them inside a module function, never loose in the file body.
     *
     * The filter is on [KoFileDeclaration.nameWithExtension], not on `name`: Konsist's
     * `name` is the file name WITHOUT its extension, so `name.endsWith("DiModule.kt")`
     * matched nothing and this test scanned no file at all. A guard that never runs
     * is not a guard.
     */
    @Test
    fun `per-domain DiModule files declare no loose bindings`() {
        val offenders =
            scope.files
                .filter { it.nameWithExtension.endsWith("DiModule.kt") }
                .filter { it.packageName().startsWith("$PKG.core.di") }
                .filter { file ->
                    bindingDeclarationsIn(file.text, facadeNames = emptySet())
                        .any { it.endsWith("<file body>") }
                }.map { it.path }

        if (offenders.isNotEmpty()) {
            fail(
                "A per-domain *DiModule.kt declares a Koin binding loose in the file body. " +
                    "Each must return a single `module { }` built from its feature's " +
                    "declarations, so the aggregator in core/di/Modules.kt stays the only " +
                    "place that composes them. Offenders: $offenders",
            )
        }
    }

    @Test
    fun `every facade function in Modules kt is one of the known set`() {
        val declared =
            modulesFile
                .functions()
                .filter { it.name in setOf("domainModule", "coreLoggingModule", "aiToolsModule") }
                .map { it.name }

        val unknown = declared.filterNot { it in FACADE_FUNCTIONS }
        if (unknown.isNotEmpty()) {
            fail("Unexpected top-level function in Modules.kt: $unknown")
        }
    }

    /**
     * Lines in [source] that call a Koin binding declaration and are not inside a function
     * whose name is in [facadeNames].
     *
     * Brace-depth counting rather than a PSI walk, because the question is positional —
     * "is this declaration inside a facade body" — and the file is a facade aggregator by
     * construction, so the scan only ever runs over a handful of lines. Parentheses are
     * counted as well: a facade whose body is a wrapped call (`= module(`) stays open
     * until the call closes, and the bindings inside it belong to the facade.
     */
    private fun bindingDeclarationsIn(
        source: String,
        facadeNames: Set<String>,
    ): List<String> {
        val offenders = mutableListOf<String>()
        var depth = 0
        var parenDepth = 0
        var enclosingFns = mutableListOf<String>()
        // Whether the body of the function whose name was just read starts on a LATER
        // line. The name is known before the body starts, so the body must not be
        // treated as finished until it has been seen — and it must not stay pending
        // forever either, or the next top-level binding would be credited to the
        // facade and let through unreported.
        var bodyPending = false

        // Comments and literals are not code: the scan reads the source with both
        // removed, so a `(` inside a string never unbalances the counters and prose
        // about DI never opens a body.
        codeOnly(source).lines().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEachIndexed
            val code = line

            // A top-level `fun name(` opens a function body at depth 0.
            if (depth == 0 && line.startsWith("fun ")) {
                // The previous top-level function is over.
                enclosingFns.clear()
                enclosingFns.add(
                    line
                        .substringAfter("fun ")
                        .substringBefore("(")
                        .trim()
                        .substringBefore(":")
                        .trim(),
                )
                // The body opens on this line when the line carries the brace. When the
                // signature ends with `=` — or with an expression that is still open —
                // it opens BELOW: the shape ktlint's multiline-expression-wrapping
                // produces. A body that is an expression without braces (`= compute()`)
                // ends on the signature line itself.
                bodyPending =
                    !code.contains('{') &&
                    (line.endsWith("=") || expressionContinuesOnNextLine(code))
            } else if (depth == 0 && bodyPending) {
                // This line carries the body of the function whose name was read above.
                // The body ends with this line unless it opens a brace — the depth
                // counter then says where it ends — or the expression continues on the
                // next line.
                if (line.isNotEmpty()) {
                    val opensBlock = code.contains('{')
                    val continues = !opensBlock && expressionContinuesOnNextLine(code)
                    bodyPending = continues
                    if (!opensBlock && !continues) {
                        // The body is over; the next line is at file level again.
                        enclosingFns.clear()
                    }
                }
            }

            // The binding call may carry an explicit type argument: `single<Repo> { }`.
            // The call name ends where the type argument starts, so the name is the
            // leading identifier of the line. Reading it as "everything before the
            // first space" produced `single<Repo>`, which is in no list of binding
            // calls — and 87 of the declarations in this repository are written that
            // way, so the scan saw almost nothing.
            val firstToken = bindingCallName(line)
            if (firstToken in BINDING_CALLS && enclosingFns.none { it in facadeNames }) {
                val owner = enclosingFns.lastOrNull() ?: "<file body>"
                offenders += "line ${index + 1} in $owner"
            }

            depth += code.count { it == '{' }
            depth -= code.count { it == '}' }
            // An argument list that is still open keeps the function open too: the
            // bindings inside it belong to the facade, not to the file body.
            parenDepth += code.count { it == '(' }
            parenDepth -= code.count { it == ')' }
            if (parenDepth < 0) parenDepth = 0
            if (depth > 0) {
                // The body has started — the function is no longer "pending".
                bodyPending = false
            }
            if (depth <= 0 && parenDepth <= 0 && !bodyPending) {
                depth = 0
                enclosingFns.clear()
            }
        }
        return offenders
    }

    /**
     * [source] with the comments removed and the CONTENT of every string literal blanked.
     *
     * Two things in a Kotlin file are not code, and the scan has to see neither:
     *
     *  - a comment: prose about DI is not a call, and it must not open a body, count
     *    a brace, or end the body of a function that is waiting for one;
     *  - the content of a literal: the `(` of `"App ("` is not a parenthesis, and
     *    counting it leaves the facade open forever — the binding after it is then
     *    credited to the facade and the guard misses exactly what it exists for.
     *
     * The content is blanked, not deleted: every line keeps its length, so a finding
     * still points at the line the binding really is on, and a line that held nothing
     * but a comment becomes empty and is skipped.
     *
     * The previous implementation cut `//` to the end of the line wherever it found
     * one — including inside a literal. `Logger.withTag("http://example.com")` lost
     * its closing quote and brace, the facade never closed, and the scan reported
     * nothing at all.
     */
    private fun codeOnly(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            i =
                when {
                    source.startsWith("//", i) -> {
                        blankLineComment(source, i, out)
                    }

                    source.startsWith("/*", i) -> {
                        blankBlockComment(source, i, out)
                    }

                    source.startsWith("\"\"\"", i) -> {
                        blankRawString(source, i, out)
                    }

                    source[i] == '"' -> {
                        blankString(source, i, out)
                    }

                    else -> {
                        out.append(source[i])
                        i + 1
                    }
                }
        }
        return out.toString()
    }

    /** Blanks a line comment, keeping the line break. Returns the index after it. */
    private fun blankLineComment(
        source: String,
        start: Int,
        out: StringBuilder,
    ): Int {
        var i = start
        while (i < source.length && source[i] != '\n') {
            out.append(' ')
            i++
        }
        return i
    }

    /**
     * Blanks a block comment, keeping its line breaks. Returns the index after it.
     *
     * Kotlin block comments nest, so the depth is counted rather than assumed.
     */
    private fun blankBlockComment(
        source: String,
        start: Int,
        out: StringBuilder,
    ): Int {
        var i = start
        var depth = 0
        while (i < source.length) {
            when {
                source.startsWith("/*", i) -> {
                    depth++
                    out.append("  ")
                    i += 2
                }

                source.startsWith("*/", i) -> {
                    depth--
                    out.append("  ")
                    i += 2
                    if (depth == 0) return i
                }

                source[i] == '\n' -> {
                    out.append('\n')
                    i++
                }

                else -> {
                    out.append(' ')
                    i++
                }
            }
        }
        return i
    }

    /** Blanks a raw string, keeping its line breaks. Returns the index after it. */
    private fun blankRawString(
        source: String,
        start: Int,
        out: StringBuilder,
    ): Int {
        var i = start + 3
        out.append("\"\"\"")
        while (i < source.length && !source.startsWith("\"\"\"", i)) {
            out.append(if (source[i] == '\n') '\n' else ' ')
            i++
        }
        if (i < source.length) {
            out.append("\"\"\"")
            i += 3
        }
        return i
    }

    /** Blanks a string literal, keeping the quotes. Returns the index after it. */
    private fun blankString(
        source: String,
        start: Int,
        out: StringBuilder,
    ): Int {
        var i = start + 1
        out.append('"')
        while (i < source.length && source[i] != '"') {
            if (source[i] == '\\' && i + 1 < source.length) {
                out.append(' ')
                out.append(if (source[i + 1] == '\n') '\n' else ' ')
                i += 2
            } else {
                out.append(if (source[i] == '\n') '\n' else ' ')
                i++
            }
        }
        if (i < source.length) {
            out.append('"')
            i++
        }
        return i
    }

    /**
     * The binding call name at the start of [line].
     *
     * The call may carry an explicit type argument: `single<Repo> { }`. The call name
     * ends where the type argument starts, so the name is the leading identifier of
     * the line. Reading it as "everything before the first space" produced
     * `single<Repo>`, which is in no list of binding calls — and 87 of the declarations
     * in this repository are written that way, so the scan saw almost nothing.
     */
    private fun bindingCallName(line: String): String = line.takeWhile { it.isLetterOrDigit() || it == '_' }

    /**
     * Whether the expression on [line] carries on to the next line.
     *
     * An expression body that does not fit is wrapped by ktlint, and the wrap shows
     * up as a trailing operator, a trailing comma inside an argument list, or an
     * unclosed parenthesis. Without this the scanner would end the body at the first
     * wrapped line and credit the next top-level binding to the facade — the failure
     * mode this scan exists to catch.
     */
    private fun expressionContinuesOnNextLine(line: String): Boolean {
        if (line.count { it == '(' } > line.count { it == ')' }) {
            return true
        }
        return CONTINUATION_SUFFIXES.any { line.endsWith(it) }
    }
}
