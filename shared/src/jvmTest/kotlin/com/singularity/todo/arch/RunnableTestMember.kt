package com.singularity.todo.arch

/**
 * What counts as a test JUnit will actually execute.
 *
 * ## Why this is a separate object
 *
 * "Does this class have a runnable test?" had two implementations: this one, in
 * Kotlin, and one in `infra/kiwi/sync.py`. They are two answers to one question,
 * and they diverged. The Kotlin gate matched only `startsWith("@Test")` while
 * `sync.py` matched the whole JUnit set, so `RecurrenceRuleMapperTest` and
 * `RruleGeneratorTest` — both written on `@ParameterizedTest` — were reported
 * clean by the gate that exists to report untagged classes, while CI, running
 * `-Ptest.tags=fast,slow`, silently skipped both. The gate was answering about
 * something else while appearing to answer about this.
 *
 * Two implementations in two languages cannot be compared by a test written in
 * one of them, so the contract is a data file:
 * `config/test-fixtures/runnable-test-members.txt`. `RunnableTestFixtureTest`
 * (this module) and `scripts/tests/test_runnable_test_members.py` both read it
 * and both must give every record the agreed verdict. A third annotation form
 * added to one side and not the other is a red test, not a review comment.
 *
 * ## Why the pattern is a regex
 *
 * The alternative is an AST walk, which is the more correct tool. It is not used
 * here because `commonTest` has no parser dependency, and a gate that needs a
 * new build dependency is a gate that gets removed the first time that
 * dependency is inconvenient. The cost is bounded: a form this regex does not
 * know is still caught one level up, by [TestTagCoverageTest] comparing source
 * classes against the classes that actually produced JUnit XML.
 */
internal object RunnableTestMember {

    /**
     * Every JUnit 5 annotation that makes a member an actual test, plus the
     * `kotlin.test` spelling this project also uses.
     *
     * Both fully-qualified forms are optional prefixes rather than a separate
     * alternative. `RruleGeneratorTest.kt:49` writes
     * `@org.junit.jupiter.api.Test`, and a class whose *only* test member uses
     * that spelling was invisible to this predicate before the shared fixture
     * table existed — the run was real, both detectors called it empty.
     *
     * The trailing `\b` is load-bearing: without it `@Test` also matches the
     * `@TestFactory` prefix, and a lookalike annotation such as `@TestFoo` would
     * read as a test.
     */
    val TEST_MEMBER: Regex = Regex(
        """^\s*@(?:(?:kotlin\.test|org\.junit\.jupiter\.api)\.)?""" +
            """(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b""",
    )

    /**
     * Lines of [source] that belong to the body of the class declared at
     * [classIndex], or an empty list for a declaration with no `{` on it.
     *
     * Brace-counted, so the length of the class is irrelevant:
     * `EntityMapperCompletenessTest` declares its first test 340 lines below its
     * header, and a fixed window in N lines read that real test as absent.
     */
    fun classBody(source: List<String>, classIndex: Int): List<String> {
        val declaration = source[classIndex]
        // A bodyless declaration — `data object Alpha`, a sealed-interface
        // member — has no '{' here. Without this guard the counter would run to
        // the end of the file and blame the *next* class for this one's tests.
        if (!declaration.contains('{')) return emptyList()
        val body = mutableListOf<String>()
        var depth = 0
        var opened = false
        for (i in classIndex until source.size) {
            val line = source[i]
            body.add(line)
            line.forEach { ch ->
                when (ch) {
                    '{' -> {
                        depth++
                        opened = true
                    }

                    '}' -> depth--
                }
            }
            if (opened && depth == 0) break
        }
        return body
    }

    /** True when the class at [classIndex] declares a member JUnit will execute. */
    fun classHasTestMember(source: List<String>, classIndex: Int): Boolean =
        classBody(source, classIndex).any { TEST_MEMBER.containsMatchIn(it) }

    /**
     * True when [source] declares a runnable test class.
     *
     * "Runnable" here means: a concrete, `Test`-named class with an executable
     * member. This is the predicate the fixture table is written against.
     * [TestTagCoverageTest] deliberately does *not* require the `Test` suffix —
     * it asks a different question (does this class carry a tag), and a class
     * named anything else with tests still needs one.
     */
    fun hasRunnableTest(source: String): Boolean {
        val lines = source.split("\n")
        return lines.indices.any { index ->
            val header = classHeaderAt(lines, index) ?: return@any false
            header.endsWith("Test") &&
                !abstractOrOpen(lines[index]) &&
                classHasTestMember(lines, index)
        }
    }

    /**
     * The class or object name declared at [index], or null when the line is not
     * a declaration.
     *
     * `find`, not `matchEntire`: a declaration continues with " {" or " :", and
     * an anchored full match silently skips every class in the repo.
     */
    fun classHeaderAt(lines: List<String>, index: Int): String? =
        CLASS_HEADER.find(lines[index].trim())
            ?.takeIf { it.range.first == 0 }
            ?.groupValues
            ?.get(1)

    /** `abstract class` / `open class` — a declaration that produces no run. */
    fun abstractOrOpen(declaration: String): Boolean =
        MODIFIER_BEFORE_CLASS.find(declaration.trim()) != null

    private const val MODIFIERS =
        "(?:public |internal |private |protected |abstract |open |final |sealed |data |value )*"

    private val CLASS_HEADER = Regex("$MODIFIERS(?:class|object)\\s+(\\w+)")

    private val MODIFIER_BEFORE_CLASS =
        Regex("^(?:public |internal |private |protected )?(?:abstract|open)\\s")
}
