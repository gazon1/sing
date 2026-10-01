package convention

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Convention plugin for JVM application modules (`desktopApp`, `mcp-server`).
 *
 * ## Migration (not yet applied — Gradle 9 included-build classpath complexity)
 *
 * Replace `desktopApp/build.gradle.kts` and `mcp-server/build.gradle.kts` plugins block:
 * ```kotlin
 * // BEFORE (desktopApp)
 * plugins {
 *     alias(libs.plugins.kotlinJvm)
 *     alias(libs.plugins.composeMultiplatform)
 *     alias(libs.plugins.composeCompiler)
 *     alias(libs.plugins.detekt)
 *     alias(libs.plugins.kover)
 * }
 *
 * // AFTER
 * plugins {
 *     id("jvm-application-convention")
 *     alias(libs.plugins.composeMultiplatform)  // compose compiler needs explicit apply
 * }
 * ```
 */
class JvmApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.plugins.apply("org.jetbrains.kotlin.jvm")
        target.plugins.apply("org.jetbrains.kotlin.detekt")
        target.plugins.apply("org.jetbrains.kotlin.kover")
    }
}
