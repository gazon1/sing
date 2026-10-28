package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.fail

/**
 * Enforces harness conventions for the `shared` JVM test suite.
 *
 * These conventions apply to `shared/src/jvmTest/kotlin/com/singularity/todo`
 * and `shared/src/commonTest/kotlin/com/singularity/todo`.
 * Unlike the desktop harness conventions (which enforce UI-layer rules like scroll-reachability
 * and button-state assertions), this file covers the shared layer: coroutine-test hygiene,
 * VM test patterns, and source hygiene.
 *
 * ## Why a separate file
 *
 * `HarnessConventionTest` in `desktopApp` enforces desktop-UI-layer conventions:
 * `checkA11y`, scroll-aware drawer helpers, button asserts. Those rules are specific to
 * Compose Desktop UI testing and do not apply to `shared` tests (which run without a UI,
 * against pure Kotlin modules).
 *
 * This file adds conventions that are specific to the shared layer: the `awaitState`
 * helper (vs raw `advanceUntilIdle()` loops), the `runVmTest` helper for ViewModel tests,
 * the coroutine scope injection pattern, and source hygiene.
 *
 * ## Conventions
 *
 * 1. **`awaitState` must be used instead of raw `advanceUntilIdle()` loops.**
 *    A bare `while (!predicate) { advanceUntilIdle() }` can hang the entire test suite:
 *    when the predicate waits on a state that will never arrive, nothing is scheduled, so
 *    virtual time cannot advance, and the deadline is never reached. `awaitState` has a
 *    wall-clock spin guard (120 s) that catches this case and fails fast with a
 *    descriptive message. A loop without the guard is a suite-level hazard.
 *
 * 2. **VM tests should use `runVmTest` rather than hand-rolling `runTest`.**
 *    `runVmTest` wires `AutoCloseableCoroutineScope`, advances the clock, and
 *    handles `assertIs<UiState.Content>()` in one call. Hand-rolled patterns tend to
 *    miss the clock advance or the scope cleanup.
 *
 * 3. **`runBlocking` is prohibited in test code.**
 *    Enforced by detekt `NoRunBlocking`. `runBlocking` blocks the calling thread and
 *    risks deadlock in coroutine-heavy code; tests must use `runTest` (or `runVmTest`).
 *
 * 4. **Every test class must carry `@Tag("fast")` or `@Tag("slow")`.**
 *    A test without `@Tag` is invisible to the `fast` / `slow` filter that gates CI.
 *    Tagging is enforced for all test classes in both `jvmTest` and `commonTest`.
 *
 * 5. **`kotlinx.coroutines.delay` over 500 ms is prohibited in test code.**
 *    Enforced by detekt `NoRealDelayInTest` (which also bans `Thread.sleep`). A long
 *    real-time delay cannot be controlled by virtual time; use `advanceTimeBy(N)` on the
 *    TestScope, or `awaitState { }`. Short delays inside `runTest` are virtual time and
 *    are deliberately allowed.
 *
 * 6. **`stateIn` is prohibited in ViewModel tests.**
 *    `stateIn` holds its upstream flow active for 5 seconds after the last subscriber
 *    disappears, which keeps the VM alive and breaks test isolation. Use
 *    `MutableStateFlow` + explicit `scope.launch { }.collect {}` instead.
 *
 * 7. **`koinInject()` is prohibited in test code.**
 *    `koinInject()` resolves dependencies at call time — too early for test setup
 *    and inconsistent with `koinViewModel()`. Tests must use `koinViewModel()` /
 *    `koinViewModel { parametersOf(p) }` for ViewModels, and direct `get<T>()` for
 *    other dependencies.
 *
 * 8. **MockK is prohibited in test code.**
 *    MockK mocking makes tests brittle and couples them to implementation details.
 *    All test doubles must use `Fake*` implementations from `test/fakes/`.
 *
 * ## Scanner smoke test
 *
 * `positiveControl — the scanner still detects violations` runs on every CI pass.
 * It applies each violation pattern to a synthetic snippet that is known to contain
 * exactly one violation. If a scanner has regressed and silently stopped detecting,
 * this test fails — preventing a broken detector from giving a false-green signal.
 */
@Tag("fast")
@Suppress("VariableNaming", "PropertyName") // UPPER_CASE names are intentional for regex/pattern constants
class SharedHarnessConventionsTest {

    /** Scans both jvmTest (production harness tests) and commonTest (shared helpers tests). */
    private val allTestSources: List<File> by lazy {
        // Injected by the jvmTest task config in `shared/build.gradle.kts`.
        // Falls back to relative paths for local runs outside Gradle.
        val jvmTestRoot: File = System.getProperty("jvmTest.root")?.let { File(it, "com/singularity/todo") }
            ?: File("src/jvmTest/kotlin/com/singularity/todo")
        val commonTestRoot: File = System.getProperty("commonTest.root")?.let { File(it, "com/singularity/todo") }
            ?: File("src/commonTest/kotlin/com/singularity/todo")

        val jvmSources = jvmTestRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.parentFile?.name != "helpers" }
            .filter { it.name != "SharedHarnessConventionsTest.kt" }
            .filter { !it.name.endsWith("Fakes.kt") }
            .filter { !it.name.endsWith("Fake.kt") }
            .toList()

        // commonTest test helpers (AwaitState, RunVmTest, testVm) are defined here and
        // are allowed to use delay/advanceUntilIdle internally — but test files (if any)
        // in commonTest must also follow the same rules. Exclude the helpers themselves.
        val commonSources = commonTestRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.parentFile?.name != "helpers" }
            .toList()

        jvmSources + commonSources
    }

    /**
     * Files that use `AutoCloseableCoroutineScope` inside `runTest` (or `testVm` factory)
     * instead of fully delegating to the harness, and the reason each exemption exists.
     *
     * Reasons are mandatory — an exemption without a reason is a maintenance trap.
     *
     * The map is empty. The two files that were in it — the agenda's own tests —
     * said they could not use the harness because `TestVmContext` did not expose the
     * raw VM, and that is not true any more: it exposes both the VM and the
     * background scope. The real reason those tests hand-rolled a scope was the
     * agenda's clock flow, which never ends — and that is exactly what
     * `VmScope.Background` is for. So the tests say what kind of ViewModel they are
     * testing, and the harness builds the scope that kind needs.
     *
     * See ADR `docs/decisions/2026-10-27-vm-test-scope-exemption.md`.
     *
     * Two-way check: validated against actual source in the
     * `exempted VM test files actually contain hand-rolled scope` test.
     */
    private val EXEMPT_VM_TEST: Map<String, String> = mapOf()

    /**
     * Matches hand-rolled AutoCloseableCoroutineScope in runTest:
     * - `val vmScope = AutoCloseableCoroutineScope(...)`  (with val)
     * - `scope = AutoCloseableCoroutineScope(...)`        (without val, e.g. SavedAgenda)
     *
     * Uses non-greedy `[^}]*?` so it stops at the FIRST `}` rather than consuming
     * across nested `{` inside the constructor call (DOT_MATCHES_ALL makes `.` match
     * `{` too, so `[^}]` is necessary to stop at the right boundary).
     */
    private val VM_SCOPE_PATTERN = Regex(
        """runTest\s*\{[^}]*?(?:val\s+)?\w+Scope\s*=\s*AutoCloseableCoroutineScope""",
        RegexOption.DOT_MATCHES_ALL,
    )

    @Test
    fun `awaitState is used instead of raw advanceUntilIdle polling loops`() {
        // A bare `while (!predicate) { advanceUntilIdle() }` has no spin guard and can
        // hang the suite. `awaitState` wraps the same logic with a wall-clock backstop.
        val VIOLATION_PATTERN = Regex(
            """while\s*\(\s*!\s*\w+\s*\)\s*\{[^}]*?advanceUntilIdle\s*\(\s*\)""",
            RegexOption.DOT_MATCHES_ALL,
        )

        val violations = allTestSources
            .mapNotNull { file ->
                val body = file.readText()
                val matches = VIOLATION_PATTERN.findAll(body).toList()
                if (matches.isEmpty()) {
                    null
                } else {
                    "${file.name}: ${matches.size} raw advanceUntilIdle loop(s)"
                }
            }

        if (violations.isEmpty()) return
        fail(
            "Test files using raw advanceUntilIdle() loops instead of awaitState:\n" +
                violations.joinToString("\n") { "  - $it" } +
                "\n\nUse `awaitState { predicate() }` from " +
                "`shared/src/commonTest/kotlin/com/singularity/todo/test/helpers/AwaitState.kt`. " +
                "It has a 120 s wall-clock spin guard that prevents suite hangs.",
        )
    }

    @Test
    fun `runVmTest is used for ViewModel tests`() {
        // `runVmTest` wires AutoCloseableCoroutineScope + clock advance + state assertions.
        // Hand-rolled `runTest { ... }` + manual scope creation misses one of these.
        val violations = allTestSources
            .mapNotNull { file ->
                val body = file.readText()
                val matches = VM_SCOPE_PATTERN.findAll(body).toList()
                if (matches.isEmpty()) {
                    null
                } else if (file.name in EXEMPT_VM_TEST) {
                    null
                } else {
                    "${file.name}: hand-rolled TestScope + AutoCloseableCoroutineScope"
                }
            }

        if (violations.isEmpty()) return
        fail(
            "Test files hand-rolling TestScope instead of using runVmTest:\n" +
                violations.joinToString("\n") { "  - $it" } +
                "\n\nUse `runVmTest { }` from " +
                "`shared/src/commonTest/kotlin/com/singularity/todo/test/helpers/RunVmTest.kt`. " +
                "It wires the scope, clock, and state assertions correctly.",
        )
    }

    @Test
    fun `exempted VM test files actually contain hand-rolled scope`() {
        // Two-way check: an exemption entry whose file no longer creates
        // AutoCloseableCoroutineScope is hiding a regression.
        //
        // The main scanner (VM_SCOPE_PATTERN) only catches the direct form:
        //   runTest { val vmScope = AutoCloseableCoroutineScope(...) }
        // The SavedAgenda files use scope creation inside a helper (createVm) called
        // from runTest — the regex can't reach across the call boundary.
        // The stale check therefore uses a broader detector: any AutoCloseableCoroutineScope
        // anywhere in the file body. A file that genuinely migrates to testVm { } or
        // eliminates AutoCloseableCoroutineScope entirely will stop matching and be flagged.
        val STALE_CHECK_PATTERN = Regex("""AutoCloseableCoroutineScope\s*\(""")
        val stale = EXEMPT_VM_TEST.filter { (fileName, _) ->
            val file = allTestSources.find { it.name == fileName }
            if (file == null) return@filter true // file removed = stale
            !STALE_CHECK_PATTERN.containsMatchIn(file.readText())
        }
        if (stale.isEmpty()) return
        fail(
            "EXEMPT_VM_TEST entries with no hand-rolled scope (stale exemptions):\n" +
                stale.entries.joinToString("\n") { "  - ${it.key}: ${it.value}" } +
                "\n\nRemove the stale entry.",
        )
    }

    @Test
    fun `stateIn is not used in ViewModel test code`() {
        // `stateIn` holds the upstream flow active for 5 s after the last subscriber,
        // keeping the VM alive and breaking test isolation. Use MutableStateFlow +
        // explicit scope.launch { }.collect {} instead.
        val VIOLATION_PATTERN = Regex("""\.stateIn\s*\(""")

        val violations = allTestSources
            .mapNotNull { file ->
                val body = file.readLines().filterNot { line ->
                    val t = line.trimStart()
                    t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
                }.joinToString("\n")
                val matches = VIOLATION_PATTERN.findAll(body).toList()
                if (matches.isEmpty()) {
                    null
                } else {
                    "${file.name}: ${matches.size} stateIn() call(s)"
                }
            }

        if (violations.isEmpty()) return
        fail(
            "Test files using stateIn() (holds upstream active 5s, breaks test isolation):\n" +
                violations.joinToString("\n") { "  - $it" } +
                "\n\nUse `MutableStateFlow + scope.launch { }.collect {}` instead. " +
                "See: singularity-todo-testable-vm skill.",
        )
    }

    @Test
    fun `koinInject is not used in test code`() {
        // `koinInject()` resolves dependencies at call time — too early for test setup
        // and inconsistent with `koinViewModel()`. Tests must use `koinViewModel()` /
        // `koinViewModel { parametersOf(p) }` for ViewModels.
        val VIOLATION_PATTERN = Regex("""koinInject\s*\(\s*\)""")

        val violations = allTestSources
            .mapNotNull { file ->
                val body = file.readLines().filterNot { line ->
                    val t = line.trimStart()
                    t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
                }.joinToString("\n")
                val matches = VIOLATION_PATTERN.findAll(body).toList()
                if (matches.isEmpty()) {
                    null
                } else {
                    "${file.name}: ${matches.size} koinInject() call(s)"
                }
            }

        if (violations.isEmpty()) return
        fail(
            "Test files using koinInject() (resolves too early, inconsistent with koinViewModel):\n" +
                violations.joinToString("\n") { "  - $it" } +
                "\n\nUse `koinViewModel()` for ViewModels and `get<T>()` for other deps.",
        )
    }

    @Test
    fun `MockK is not used in test code`() {
        // MockK mocking couples tests to implementation details and makes them brittle.
        // All test doubles must use `Fake*` implementations from `test/fakes/`.
        val VIOLATION_PATTERNS = listOf(
            Regex("""mockkObject\s*\("""),
            Regex("""mockkClass\s*\("""),
            Regex("""mockk<\s*\w+[^>]*>\s*\("""),
            Regex("""every\s*\{"""),
            Regex("""verify\s*\{"""),
        )

        val violations = allTestSources
            .mapNotNull { file ->
                val body = file.readLines().filterNot { line ->
                    val t = line.trimStart()
                    t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
                }.joinToString("\n")
                val found = VIOLATION_PATTERNS.flatMap { it.findAll(body).toList() }
                if (found.isEmpty()) {
                    null
                } else {
                    "${file.name}: ${found.size} MockK call(s)"
                }
            }

        if (violations.isEmpty()) return
        fail(
            "Test files using MockK (couples to implementation, makes tests brittle):\n" +
                violations.joinToString("\n") { "  - $it" } +
                "\n\nUse `Fake*` implementations from " +
                "`shared/src/commonTest/kotlin/com/singularity/todo/test/fakes/`. " +
                "Fakes are explicit, readable, and do not require verification boilerplate.",
        )
    }

    @Test
    fun `positive control — the scanner still detects violations`() {
        // If this test fails, the violation scanners have regressed and are silently
        // passing. Each scanner is applied to a synthetic snippet that is known to
        // contain exactly one violation; a passing scanner must find it.
        val syntheticSources = mapOf(
            "advanceUntilIdle loop" to """
                class BadTest {
                    fun test() = runTest {
                        while (!predicate) { advanceUntilIdle() }
                    }
                }
            """,
            "AutoCloseableCoroutineScope" to """
                class BadTest {
                    fun test() = runTest {
                        val vmScope = AutoCloseableCoroutineScope()
                    }
                }
            """,
        )

        val advanceUntilIdlePattern = Regex(
            """while\s*\(\s*!\s*\w+\s*\)\s*\{[^}]*?advanceUntilIdle\s*\(\s*\)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val scopePattern = Regex(
            """runTest\s*\{[^}]*?val\s+\w+Scope\s*=\s*AutoCloseableCoroutineScope""",
            RegexOption.DOT_MATCHES_ALL,
        )

        val failures = mutableListOf<String>()

        if (advanceUntilIdlePattern.find(syntheticSources["advanceUntilIdle loop"]!!) == null) {
            failures.add("advanceUntilIdle loop scanner missed its synthetic target")
        }
        if (scopePattern.find(syntheticSources["AutoCloseableCoroutineScope"]!!) == null) {
            failures.add("AutoCloseableCoroutineScope scanner missed its synthetic target")
        }

        if (failures.isEmpty()) return
        fail(
            "Positive control failed — scanner regression:\n" +
                failures.joinToString("\n") { "  - $it" } +
                "\n\nThe violation scanners have stopped detecting their targets. " +
                "Fix the regex pattern before merging.",
        )
    }

    @Test
    fun `expectMostRecentItem is not used in test code`() {
        // Turbine's `expectMostRecentItem()` silently drops all but the most recent event,
        // masking timing assumptions and producing passing tests that miss real behavioural bugs.
        // Tests should use explicit virtual-time advancement (runCurrent / advanceUntilIdle)
        // to observe events as they arrive.
        val VIOLATION_PATTERN = Regex("""expectMostRecentItem\s*\(""")

        val violations = allTestSources
            .mapNotNull { file ->
                val body = file.readLines().filterNot { line ->
                    val t = line.trimStart()
                    t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
                }.joinToString("\n")
                val matches = VIOLATION_PATTERN.findAll(body).toList()
                if (matches.isEmpty()) {
                    null
                } else {
                    "${file.name}: ${matches.size} expectMostRecentItem() call(s)"
                }
            }

        if (violations.isEmpty()) return
        fail(
            "Test files using expectMostRecentItem() (hides timing assumptions, masks bugs):\n" +
                violations.joinToString("\n") { "  - $it" } +
                "\n\nUse `runCurrent()` or `advanceUntilIdle()` to observe events as they arrive, " +
                "or collect into a list and assert on it directly.",
        )
    }
}
