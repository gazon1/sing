package convention

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Convention plugin for JVM application modules (`desktopApp`, `mcp-server`).
 *
 * ## Migration (not yet applied — Gradle 9 included-build classpath complexity)
 *
 * The migration plan lives in `build-logic/README.md` — one home for it, so the
 * plan cannot drift away from the tree it describes.
 */
class JvmApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.plugins.apply("org.jetbrains.kotlin.jvm")
        target.plugins.apply("org.jetbrains.kotlin.detekt")
        target.plugins.apply("org.jetbrains.kotlin.kover")
    }
}
