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
 * Replace `shared/build.gradle.kts` plugins block:
 * ```kotlin
 * // BEFORE (7 plugin aliases)
 * plugins {
 *     alias(libs.plugins.kotlinMultiplatform)
 *     alias(libs.plugins.androidMultiplatformLibrary)
 *     alias(libs.plugins.composeMultiplatform)
 *     alias(libs.plugins.composeCompiler)
 *     alias(libs.plugins.kotlinxSerialization)
 *     alias(libs.plugins.ksp)
 *     alias(libs.plugins.room3)
 *     alias(libs.plugins.detekt)
 *     alias(libs.plugins.kover)
 * }
 *
 * // AFTER (1 convention plugin + platform-specific)
 * plugins {
 *     id("kotlin-multiplatform-library-convention")
 *     alias(libs.plugins.ksp)        // Room KSP — needs platform-specific version
 *     alias(libs.plugins.detekt)
 *     alias(libs.plugins.kover)
 * }
 * ```
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
