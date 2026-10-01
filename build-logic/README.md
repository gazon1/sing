# build-logic/convention

Reference implementations for convention plugins. **Not yet wired into the main build** — Gradle 9's included-build classpath isolation makes wiring a `kotlin-dsl` convention build non-trivial.

## Why not yet applied

Gradle 9's classpath isolation for included builds means:
1. The `kotlin-dsl` plugin needs its own `pluginManagement` block with all plugin repositories
2. Convention plugins published via `gradlePlugin { }` can't be resolved from the root build without additional configuration
3. The resolution order creates a chicken-and-egg problem: the root build needs the convention plugins, but they can't be built until the root build resolves plugin dependencies

## Current status

- ✅ 4 convention plugin source files written (documented, compilable)
- ✅ README with migration path documented
- ⏳ `build.gradle.kts` + `settings.gradle.kts` NOT wired (blocks main build)

## Migration (when Gradle 9 included-build classpath is resolved)

### Step 1: Wire convention build in `settings.gradle.kts`

Add to `pluginManagement` block:
```kotlin
includeBuild("build-logic/convention")
```

### Step 2: Apply `common-deps-convention` in root `build.gradle.kts`

Replace plugins block:
```kotlin
// BEFORE
plugins {
    alias(libs.plugins.androidApplication) apply false
    // ... 7 more
}

// AFTER
plugins {
    id("common-deps-convention") apply false
    alias(libs.plugins.androidApplication) apply false
    // ... remaining non-convention plugins
}
```

Remove `allprojects { configurations.all { resolutionStrategy { ... } } }` block.

### Step 3: Migrate module build files

**`shared/build.gradle.kts`**:
```kotlin
// BEFORE
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

// AFTER
plugins {
    id("kotlin-multiplatform-library-convention")
    alias(libs.plugins.ksp)      // Room KSP — needs platform-specific version
    alias(libs.plugins.detekt)    // custom rules need local plugin
    alias(libs.plugins.kover)
}
```

**`androidApp/build.gradle.kts`**:
```kotlin
// BEFORE
plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.detekt)
}

// AFTER
plugins {
    id("android-application-convention")
}
```

**`desktopApp/build.gradle.kts`**:
```kotlin
// BEFORE
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

// AFTER
plugins {
    id("jvm-application-convention")
    alias(libs.plugins.composeMultiplatform)  // compose compiler needs explicit
}
```

**`mcp-server/build.gradle.kts`**:
```kotlin
// BEFORE
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
    application
}

// AFTER
plugins {
    id("jvm-application-convention")
    alias(libs.plugins.kotlinxSerialization)
}
application
```

### Step 4: Verify

```bash
./gradlew :shared:compileKotlinJvm :androidApp:assembleDebug :desktopApp:run --dry-run
./gradlew :shared:jvmTest
```
