package convention

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Centralises version-pinning and [org.gradle.api.artifacts.ResolutionStrategy] for all
 * Kotlin and kotlinx libraries across the whole build.
 *
 * ## Migration (not yet applied — Gradle 9 included-build classpath complexity)
 *
 * 1. Add to `settings.gradle.kts` pluginManagement:
 *    `includeBuild("build-logic/convention")`
 * 2. Replace root `build.gradle.kts` plugins block:
 *    `id("common-deps-convention") apply false`
 * 3. Remove `allprojects { configurations.all { resolutionStrategy { ... } } }` from root.
 *
 * Applied in root `build.gradle.kts` via `id("common-deps-convention")`.
 */
class CommonDepsConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val kotlinVersion: String = target.property("version.kotlin") as String
        val serializationVersion: String = target.property("version.kotlinSerialization") as String
        val collectionsImmutableVersion: String = target.property("version.kotlinxCollectionsImmutable") as String

        target.allprojects {
            configurations.all {
                resolutionStrategy.eachDependency {
                    if (requested.group == "org.jetbrains.kotlin") {
                        useVersion(kotlinVersion)
                    }
                    if (requested.group == "org.jetbrains.kotlinx") {
                        if (requested.name == "kotlinx-serialization-json" ||
                            requested.name == "kotlinx-serialization-core" ||
                            requested.name == "kotlinx-serialization-bom") {
                            useVersion(serializationVersion)
                        }
                        if (requested.name == "kotlinx-collections-immutable") {
                            useVersion(collectionsImmutableVersion)
                        }
                    }
                }
            }
        }
    }
}
