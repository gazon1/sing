import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.detekt)
}

/**
 * `:adr-core` — how an ADR is read, written and validated. No transport.
 *
 * This module is the whole reason `:adr-cli` used to ship 127 MB. It contained
 * `AdrStorage` and `AdrCorpusConfig`, and it sat downstream of `:shared`, which brings
 * Compose Material3, `material-icons-extended`, Room, SQLite, Koin and the Koog agent
 * stack. So `singularity-adr list` — reading markdown files — carried an application
 * runtime into its distribution, and started in 0.30 s where the Python gate it wraps
 * takes 0.08 s.
 *
 * The MCP tool adapters stayed in `:shared` (`AdrTools.kt`) because `SimpleTool` and
 * `TypeToken` are Koog types, and Koog is what pulls in the LLM client stack. That
 * split is the one place where "where does this belong" and "what does this cost"
 * point in opposite directions, and cost won.
 *
 * **Both front doors read this module.** `:shared`'s MCP tools and `:adr-cli`'s
 * subcommands are adapters over the same [AdrStorage], so they cannot disagree about
 * what an ADR may contain. That is not a convention — there is one implementation.
 */
kotlin {
    jvm()

    android {
        namespace = "com.singularity.todo.adrcore"
        compileSdk = libs.versions.sdk.compile.get().toInt()
        minSdk = libs.versions.sdk.min.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
            freeCompilerArgs.add("-Xskip-metadata-version-check")
            freeCompilerArgs.add("-Xbinary=allow-kotlin-metadata-version-mismatch=true")
            freeCompilerArgs.add("-Xopt-in=kotlin.time.ExperimentalTime")
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core-base"))
            implementation(libs.kotlinx.datetime)
            // `AdrSummary` and `AdrFile` are the `--json` contract (#524) and the MCP
            // tool payloads at once, so serialization is part of this module's API.
            api(libs.kotlinx.serialization.json)
        }
    }
}
// detekt — same rules as every other module. The baseline is empty on purpose: this
// module is new, so anything it reports is a finding about code written today.
detekt {
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = rootProject.file("config/detekt/baseline-adr-core.xml")
    ignoreFailures = false
    source.setFrom("src/commonMain/kotlin")
}

dependencies {
    detektPlugins(libs.detekt.formatting)
    detektPlugins(project(":detekt-rules"))
}

/**
 * `:adr-core:koverGenerateArtifact` consumes `:adr-core:compileKotlinJvm`'s output
 * directory, and Gradle 9 reports that as an implicit dependency unless one side
 * declares it. CI hit it on 2026-10-16 during `koverMarkdownTable`:
 *
 *     Task ':adr-core:koverGenerateArtifact' uses this output of task
 *     ':adr-core:compileKotlinJvm' without declaring an explicit or implicit dependency
 *
 * The step that surfaced it is `|| true`, so the failure was reported and then
 * ignored — which is how a real Gradle validation error sat in a green run.
 *
 * It is not reliably reproducible locally: the validation fires only when the
 * output directory does not already exist, so a warm `build/` hides it. That is
 * why it is fixed rather than investigated further — Gradle's own remedy is to
 * declare the dependency, and it costs three lines.
 *
 * `matching { }.configureEach {}` rather than `tasks.named(...)`: Kover is applied
 * at the settings level, so its tasks are not guaranteed to exist at the point this
 * script's body runs. The lazy form cannot fail configuration if that ordering
 * changes.
 */
tasks.matching { it.name == "koverGenerateArtifact" }.configureEach {
    dependsOn(tasks.named("compileKotlinJvm"))
}
