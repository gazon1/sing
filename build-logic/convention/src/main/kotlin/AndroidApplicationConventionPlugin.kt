package convention

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Convention plugin for the `androidApp` module.
 *
 * ## Migration (not yet applied — Gradle 9 included-build classpath complexity)
 *
 * The migration plan lives in `build-logic/README.md` — one home for it, so the
 * plan cannot drift away from the tree it describes.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.plugins.apply("com.android.application")
        target.plugins.apply("org.jetbrains.kotlin.android")
        target.plugins.apply("org.jetbrains.kotlin.detekt")
    }
}
