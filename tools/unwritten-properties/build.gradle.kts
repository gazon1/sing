// The analysis behind the "read but never written" check, and nothing else yet.
//
// The decision logic ([UnwrittenPropertyAnalysis]) has no dependency on KSP or on the
// Kotlin compiler, so all of it is covered by plain JUnit tests that run in
// milliseconds. That separation is the point: the false positives live in the rules,
// not in the symbol plumbing, and this way they are tested without a compiler.
//
// NOT wired into the main build. See
// docs/decisions/2026-10-07-reading-a-state-property-is-not-writing-one.md — the KSP
// adapter is unwritten because KSP 2.3.11's `symbol-processing-api` artifact ships
// declarations only, and the expression API a reference walk needs is a separate
// artifact this repository does not yet depend on.

plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
