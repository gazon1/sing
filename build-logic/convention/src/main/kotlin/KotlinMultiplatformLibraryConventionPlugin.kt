package convention

import com.android.build.api.dsl.MultiplatformExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Convention plugin for the `shared` KMP library module.
 *
 * ## Migration (not yet applied — Gradle 9 included-build classpath complexity)
 *
 * The migration plan lives in `build-logic/README.md` — one home for it, so the
 * plan cannot drift away from the tree it describes.
 *
 * This file does not compile as written: it imports
 * `com.android.build.api.dsl.MultiplatformExtension`, which AGP does not ship,
 * and it applies the plugin id `com.android.multiplatform-library`, which AGP
 * does not publish. The README says so and says what the real names are.
 */
class KotlinMultiplatformLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.plugins.apply("org.jetbrains.kotlin.multiplatform")
        target.plugins.apply("com.android.multiplatform-library")
        target.plugins.apply("org.jetbrains.kotlin.plugin.compose")
        target.plugins.apply("org.jetbrains.kotlin.plugin.serialization")
        target.plugins.apply("io.room.incremental")

        val multiplatform = target.extensions.getByType(MultiplatformExtension::class.java)

        multiplatform.jvm {
            compilerOptions {
                jvmTarget = JvmTarget.JVM_11
                freeCompilerArgs.add("-Xskip-metadata-version-check")
                freeCompilerArgs.add("-Xbinary=allow-kotlin-metadata-version-mismatch=true")
                freeCompilerArgs.add("-Xopt-in=kotlin.time.ExperimentalTime")
                freeCompilerArgs.add("-Xexpect-actual-classes")
            }
        }
    }
}
