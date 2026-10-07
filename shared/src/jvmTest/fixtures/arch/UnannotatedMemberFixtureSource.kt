package com.singularity.todo.arch.fixtures

/**
 * A deliberately broken test class, read by the positive control of
 * `UnannotatedTestMemberTest` and by nothing else.
 *
 * ## Why a file that is wrong on purpose is committed
 *
 * A gate that has never failed is not known to work. The defect this gate exists
 * for is silent by construction — `looksExactlyLikeATest` compiles, reads like a
 * test, and JUnit never runs it, so the suite is green while a check is missing.
 * The only way to prove the gate detects that shape is to keep one instance of it
 * on disk and assert the gate finds it.
 *
 * Reconstructing it from history instead was tried and is not equivalent: the
 * scanner that found the two real occurrences of this defect also *failed* to find
 * the second one in the same file, because a multi-line raw JSON fixture threw its
 * brace count off and the class body appeared to end early. A control synthesized
 * from git history at review time would have reproduced the same blindness,
 * because the blindness was in the detector, not in the sample.
 *
 * ## Two constraints on this file
 *
 * **It must never compile.** It sits in `src/jvmTest/fixtures/`, which is not a
 * Kotlin source path, so the compiler, detekt and the coverage gates never see it.
 * If it ever starts compiling, a tree that exists to be broken has been wired into
 * the build and the positive control has become a real failing test.
 *
 * **Its file name must not end in the test suffix.** `ClassBodyScannerAgreementTest`
 * walks all of `src/jvmTest` — not only its `kotlin` subdirectory — and every
 * file it recognizes as a class contributes to its `compared > 200` count. A
 * file matching that suffix here would make that gate answer a question about a
 * fixture.
 *
 * **The prose must not spell that suffix in backticks either.**
 * `check-doc-dead-refs.py` reads backticked paths as references, and this file was
 * the first NEW dead reference in the repository: it parsed the suffix as a path,
 * did not find it, and failed the gate. Found by running the gate rather than by
 * reasoning about it — the constraint was written a paragraph above this one and
 * still broke a check.
 *
 * Every member below carries an annotation except one. That one is the sample.
 */
@Suppress("unused")
class UnannotatedMemberFixtureTest {

    /**
     * The defect, exactly as it was written twice in `SingularityCatalogTest`
     * (PR #223): a test-shaped function that no annotation ever wires to JUnit.
     */
    fun looksExactlyLikeATest() = Unit

    /** What the same function looks like once someone notices. */
    @org.junit.jupiter.api.Test
    fun aProperlyAnnotatedTest() = Unit

    /**
     * A lifecycle callback is not a test, but it *is* a declaration with intent, and
     * an earlier draft of this gate flagged all 32 of them across the repository.
     */
    @org.junit.jupiter.api.BeforeEach
    fun setUp() = Unit

    /** Private members are helpers by definition and are out of scope. */
    private fun aPrivateHelper() = Unit

    private companion object {
        private const val NOT_A_TEST = "a constant, not a function"
    }
}
