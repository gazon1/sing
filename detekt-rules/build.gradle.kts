plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // detekt-api 2.0.0-alpha.3 — same version as the project's detekt plugin
    // so the rule API is binary-compatible with :shared's :desktopApp's detekt runs.
    implementation("dev.detekt:detekt-api:2.0.0-alpha.3")
    // detekt-test for writing tests against the rule (optional, add as needed)
    testImplementation("dev.detekt:detekt-test:2.0.0-alpha.3")
    testImplementation("dev.detekt:detekt-test-utils:2.0.0-alpha.3")
    testImplementation(kotlin("test"))
}

// JUnit Platform — same configuration as shared/desktopApp/mcp-server.
// Bumped heap to 2 GB for consistency; detekt-test loads compiled rule classes.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "3g"
    jvmArgs(
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=build/test-heap-dumps",
    )
}
