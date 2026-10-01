package convention

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Convention plugin for the `androidApp` module.
 *
 * ## Migration (not yet applied — Gradle 9 included-build classpath complexity)
 *
 * Replace `androidApp/build.gradle.kts` plugins block:
 * ```kotlin
 * // BEFORE
 * plugins {
 *     alias(libs.plugins.androidApplication)
 *     alias(libs.plugins.composeCompiler)
 *     alias(libs.plugins.detekt)
 * }
 *
 * // AFTER
 * plugins {
 *     id("android-application-convention")
 * }
 * ```
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.plugins.apply("com.android.application")
        target.plugins.apply("org.jetbrains.kotlin.android")
        target.plugins.apply("org.jetbrains.kotlin.detekt")
    }
}
