import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.detekt)
    application
}

kotlin {
    jvmToolchain(21)

    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        freeCompilerArgs.add("-Xskip-metadata-version-check")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

application {
    mainClass.set("com.singularity.todo.adr.cli.MainKt")
    applicationName = "singularity-adr"
}

/**
 * Compose Multiplatform pulls `savedstate-compose-desktop` onto the runtime classpath
 * by two routes, and Gradle refuses to copy a distribution that lists the same jar
 * twice. One copy is the correct outcome either way — this is not a way of hiding a
 * real version conflict, it is two paths to one artefact.
 */
distributions {
    main {
        contents {
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }
    }
}

/**
 * Deliberately no Koin, no Compose, no Room.
 *
 * The CLI's entire dependency surface used to be the desktop app, which meant that
 * `adr list` booted a Swing application and resolved a database graph to read 566
 * markdown files — and, because `user.dir` under Gradle is the daemon's directory, put
 * the results in `~/.singularity-todo` where they are real but not in git (#522).
 *
 * `AdrStorage` takes its three dependencies as constructor parameters precisely so
 * that this module can hand them over directly and skip the container.
 */
dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.datetime)
    // The --json contract (#524) is kotlinx-serialization for the same reason the MCP
    // tools are: the shapes are declared once and encoded, never assembled by hand into
    // a string. `shared` declares this as `implementation`, so it is not transitive.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.params)
}

// ---------------------------------------------------------------------------
// detekt — same rules as every other module, no exceptions
// ---------------------------------------------------------------------------
detekt {
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = rootProject.file("config/detekt/baseline-adr-cli.xml")
    ignoreFailures = false
    source.setFrom("src/main/kotlin", "src/test/kotlin")
}

tasks.withType<Test>().configureEach {
    failOnNoDiscoveredTests = true

    useJUnitPlatform {
        // Same split as :shared and :desktopApp — a default run excludes `slow`,
        // and `-Ptest.tags=fast,slow` measures both. See `failOnNoDiscoveredTests`
        // above: discovering zero tests is a configuration error, not a pass.
        val tags = (project.findProperty("test.tags") as String?)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        when {
            tags.isEmpty() -> excludeTags("slow")
            tags == listOf("all") -> Unit
            else -> includeTags(*tags.toTypedArray())
        }
    }
    maxHeapSize = "1g"
}

dependencies {
    detektPlugins(libs.detekt.formatting)
    detektPlugins(project(":detekt-rules"))
}

// ---------------------------------------------------------------------------
// generateDocs — write the "ADR tools" section of the agent skills (#526)
//
// This is a thin wrapper, and deliberately so. The rendering lives in
// `scripts/render-adr-tools-section.py` because `scripts/check-skill-drift.py` has to
// run the *same* rendering to compare, and the gate registry runs without a JVM. If
// the task rendered the section itself, the writer and the checker would be two
// implementations of one document — and the checker would be the one nobody notices
// when they disagree, because it is the one that reports green.
val generateDocs by tasks.registering(Exec::class) {
    group = "documentation"
    description = "Regenerate the generated 'ADR tools' block in the agent skills."
    workingDir = rootProject.projectDir
    commandLine("python3", "scripts/render-adr-tools-section.py")
    inputs.file(rootProject.file("adr-cli/src/main/kotlin/com/singularity/todo/adr/cli/AdrSurface.kt"))
    inputs.file(rootProject.file("shared/src/commonMain/kotlin/com/singularity/todo/mcp/McpToolCatalog.kt"))
    inputs.files(
        rootProject.file(".agents/skills/singularity-todo-decisions-workflow/SKILL.md"),
        rootProject.file(".agents/skills/singularity-todo-mcp-server/SKILL.md"),
    )
    // Never up to date: the task's inputs do not include `AdrSurface.kt` transitively
    // through `shared`, and a documentation task that silently decides it has nothing
    // to do is how the skill drifts back.
    outputs.upToDateWhen { false }
}