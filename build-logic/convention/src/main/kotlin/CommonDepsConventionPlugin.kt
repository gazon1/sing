package convention

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension

/**
 * Centralises version-pinning and [org.gradle.api.artifacts.ResolutionStrategy] for all
 * Kotlin and kotlinx libraries across the whole build.
 *
 * The versions come from the version catalog, the single source of truth. They are
 * read through [VersionCatalogsExtension] rather than the `libs.versions.*` accessors,
 * because the `kotlin-serialization` version key shadows `libs.versions.kotlin`.
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
        val versionCatalog = target.extensions.getByType<VersionCatalogsExtension>().named("libs")
        val kotlinVersion: String = versionCatalog.findVersion("kotlin").get().requiredVersion
        val serializationVersion: String = versionCatalog.findVersion("kotlin-serialization").get().requiredVersion
        val collectionsImmutableVersion: String =
            versionCatalog.findVersion("kotlinxCollectionsImmutable").get().requiredVersion

        target.allprojects {
            configurations.all {
                resolutionStrategy.eachDependency {
                    if (requested.group == "org.jetbrains.kotlin") {
                        useVersion(kotlinVersion)
                    }
                    if (requested.group == "org.jetbrains.kotlinx") {
                        if (requested.name == "kotlinx-serialization-json" ||
                            requested.name == "kotlinx-serialization-core" ||
                            requested.name == "kotlinx-serialization-bom"
                        ) {
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
