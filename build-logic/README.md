# build-logic/convention

Reference implementations for convention plugins. **Not yet wired into the main build** — Gradle 9's included-build classpath isolation makes wiring a `kotlin-dsl` convention build non-trivial.

## Trigger conditions

Connect `build-logic/convention` to the main build when **both** are true:

1. **(a) ≥ 10 modules** share the same convention plugin (kotlin-multiplatform-library,
   android-application, jvm-application, android-library). Count current convention
   candidates: `shared`, `androidApp`, `desktopApp`, `mcp-server`, `detekt-rules` —
   five modules. A new shared convention is worth the overhead when a sixth module
   would otherwise copy the same plugin block.

2. **(b) Gradle closed the included-build isolation gap.** Track [gradle/gradle#34896](https://github.com/gradle/gradle/issues/34896). When a stable Gradle release ships
   a resolution that eliminates the `pluginManagement` + classpath-isolation conflict,
   the migration steps below apply.

**Do not wire preemptively.** The convention build adds a second Gradle invocation
to every `includeBuild` refresh in CI. The current 5-module flat structure compiles
in one daemon pass; the convention build buys consistency at the cost of build latency.

## Convention plugin checklist

Before wiring, verify all four plugins exist and are documented:

| Plugin | File | Covers |
|---|---|---|
| `common-deps-convention` | `plugins/common-deps-convention.gradle.kts` | Dependency versions + platform-agnostic global settings |
| `kotlin-multiplatform-library-convention` | `plugins/kotlin-multiplatform-library-convention.gradle.kts` | KMP targets, Room, Koin, KSP |
| `android-application-convention` | `plugins/android-application-convention.gradle.kts` | Android app + Compose compiler |
| `jvm-application-convention` | `plugins/jvm-application-convention.gradle.kts` | JVM/desktop targets + Compose compiler |

## Why not yet applied

Gradle 9's classpath isolation for included builds means:

1. The `kotlin-dsl` plugin needs its own `pluginManagement` block with all plugin repositories
2. Convention plugins published via `gradlePlugin { }` can't be resolved from the root build without additional configuration
3. The resolution order creates a chicken-and-egg problem: the root build needs the convention plugins, but they can't be built until the root build resolves plugin dependencies

## Current status

- ✅ 4 convention plugin source files written (documented, compilable)
- ✅ README with migration path documented
- ⏳ `build.gradle.kts` + `settings.gradle.kts` NOT wired (blocks main build)

## Nothing compiles these files

The convention build is not wired, so **no Gradle task in this repository compiles
these sources** — not locally, not in CI. A change here can therefore rot in
silence: nothing turns red, and the breakage surfaces only on the day the
build is wired.

The fix for a review of this directory is to compile it by hand before saying
it is correct:

```bash
# A scratch project with the `kotlin-dsl` plugin is enough — the sources only
# need Gradle's API on the classpath.
mkdir -p /tmp/convention-check/src/main/kotlin
cp build-logic/convention/src/main/kotlin/*.kt /tmp/convention-check/src/main/kotlin/
printf 'plugins { `kotlin-dsl` }\n' > /tmp/convention-check/build.gradle.kts
./gradlew --project-dir /tmp/convention-check compileKotlin
```

Two errors this catches, both of which the tree carried for a while:

- `getByType<VersionCatalogsExtension>()` needs `import org.gradle.kotlin.dsl.getByType`.
  The Kotlin DSL script files get that import implicitly; a plain Kotlin source
  file does not. Without it the compiler reports the failure at the *call site*
  ("no value passed for parameter 'type'") and never at the missing import, so
  the error reads like a Gradle API problem rather than an import problem.
- `versionCatalog.findVersion(key).get()` compiles, but it throws
  `NoSuchElementException: No value present` when the key is missing — and the
  message names nothing. `orElseThrow { … }` names the key instead.

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
