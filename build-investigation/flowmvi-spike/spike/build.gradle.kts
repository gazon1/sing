// The scenario comparison for #3234: one contract, two implementations, one suite.
//
// JVM only. Variant A must run the REAL in-repo `MviViewModel` and `EventBus`, not a
// lookalike: a copy that somebody forgets to re-copy turns the comparison into a
// comparison of a fork. So the eleven production files are staged by a `Sync` task
// whose inputs ARE those files — editing production invalidates the staging, and
// nothing here can silently diverge from what the app actually runs.
//
// A symlinked source directory was the first attempt and does not work: Gradle's file
// snapshot refuses a source that escapes the project directory.
plugins {
    id("org.jetbrains.kotlin.multiplatform") version "2.4.10"
}

kotlin {
    jvmToolchain(21)

    jvm()

    sourceSets {
        commonMain {
            // Populated by `stageProductionSources` below.
            kotlin.srcDir(layout.buildDirectory.dir("staged-production"))

            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                implementation("co.touchlab:kermit:2.2.0")
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel:2.11.0")
                implementation("org.jetbrains.compose.runtime:runtime:1.12.2")
                implementation("androidx.annotation:annotation:1.9.1")

                // Variant B.
                implementation("pro.respawn.flowmvi:core:3.2.1")
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit5"))
        }
    }
}

/** Exactly the files the MVI loop needs, and nothing else from `:shared`. */
val productionSources = mapOf(
    "core/ui" to listOf("MviViewModel.kt", "EventBus.kt", "Markers.kt"),
    "core/error" to listOf("AppError.kt", "RunCatching.kt", "UserMessage.kt"),
    "core/coroutines" to listOf("AutoCloseableCoroutineScope.kt", "BackgroundScope.kt", "testScope.kt"),
    "core/observability" to listOf("CrashReportingPort.kt", "BackgroundCrashReporting.kt"),
)

val appCommonMain = rootProject.layout.projectDirectory.dir(
    "../../shared/src/commonMain/kotlin/com/singularity/todo",
)

val stageProductionSources = tasks.register<Sync>("stageProductionSources") {
    description = "Copy the production MVI files into this probe, so variant A runs the real loop."
    into(layout.buildDirectory.dir("staged-production"))
    productionSources.forEach { (targetDir, files) ->
        files.forEach { file ->
            from(appCommonMain.file("$targetDir/$file")) {
                into(targetDir)
            }
        }
    }
}

tasks.named("compileKotlinJvm") { dependsOn(stageProductionSources) }
tasks.named("compileTestKotlinJvm") { dependsOn(stageProductionSources) }

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
