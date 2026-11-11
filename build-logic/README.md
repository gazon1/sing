# build-logic/convention

Reference implementations for convention plugins. **Not yet wired into the main build** — Gradle 9's included-build classpath isolation makes wiring a `kotlin-dsl` convention build non-trivial.

## Trigger conditions

Connect `build-logic/convention` to the main build when **both** are true:

1. **(a) ≥ 10 modules** share the same convention plugin (common-deps,
   kotlin-multiplatform-library, android-application, jvm-application). Count
   current convention candidates: `shared`, `androidApp`, `desktopApp`,
   `mcp-server` — four of them, out of the twelve modules the free build
   includes (`:pro` is the thirteenth, and only when `-PwithPro=true`).
   `detekt-rules` is not a candidate: it is a library of detekt rules, and
   none of the four conventions is about what a module contains. A new shared
   convention is worth the overhead when a fifth module would otherwise copy
   the same plugin block.

2. **(b) Gradle closed the included-build isolation gap.** Track [gradle/gradle#34896](https://github.com/gradle/gradle/issues/34896). When a stable Gradle release ships
   a resolution that eliminates the `pluginManagement` + classpath-isolation conflict,
   the migration steps below apply.

**Do not wire preemptively.** The convention build adds a second build to configure
and compile on every run — Gradle composes an included build into the same
invocation, but it configures it separately. The current flat structure compiles
in one daemon pass; the convention build buys consistency at the cost of build latency.

## Convention plugin checklist

Before wiring, verify all four plugins exist and are documented:

| Plugin | Source | Covers |
|---|---|---|
| `common-deps-convention` | `convention/src/main/kotlin/CommonDepsConventionPlugin.kt` | Dependency versions + platform-agnostic global settings |
| `kotlin-multiplatform-library-convention` | `convention/src/main/kotlin/KotlinMultiplatformLibraryConventionPlugin.kt` | KMP targets, the Compose compiler, serialization, Room |
| `android-application-convention` | `convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt` | Android app, Kotlin, detekt |
| `jvm-application-convention` | `convention/src/main/kotlin/JvmApplicationConventionPlugin.kt` | JVM/desktop targets, detekt, Kover |

The ids are the ones the migration steps below use. Nothing registers them
yet: `build-logic/` has no build file, so there is no `gradlePlugin { }` block
that would publish an id.

## Why not yet applied

Gradle 9's classpath isolation for included builds means:

1. The `kotlin-dsl` plugin needs its own `pluginManagement` block with all plugin repositories
2. Convention plugins published via `gradlePlugin { }` can't be resolved from the root build without additional configuration
3. The resolution order creates a chicken-and-egg problem: the root build needs the convention plugins, but they can't be built until the root build resolves plugin dependencies

## Current status

- ✅ 4 convention plugin source files written (documented; three compile, the
  fourth does not — see below)
- ✅ README with migration path documented
- ⏳ `build.gradle.kts` + `settings.gradle.kts` NOT wired (blocks main build)

## Nothing compiles these files

The convention build is not wired, so **no Gradle task in this repository compiles
these sources** — not locally, not in CI. A change here can therefore rot in
silence: nothing turns red, and the breakage surfaces only on the day the
build is wired. (`ktlint` does read these files — it lists them through
`git ls-files` — so a formatting slip turns red. It says nothing about
whether the code compiles, which is the failure that matters here.)

The fix for a review of this directory is to compile it by hand before saying
it is correct:

```bash
# A scratch project with the `kotlin-dsl` plugin is enough for the three
# sources that need nothing but Gradle's API on the classpath.
#
# The scratch directory is a fresh one every run, not a fixed path that is
# cleared: a leftover file from an earlier run — the KMP source of an older
# revision of this recipe, say — stays in a fixed directory and fails the
# compile for a reason that has nothing to do with the change under review.
# A per-run directory also keeps two worktrees from clearing each other's.
SCRATCH_ROOT="${TMPDIR:-$HOME/.cache/singularity-todo-tmp}"
mkdir -p "$SCRATCH_ROOT"
SCRATCH="$(mktemp -d "$SCRATCH_ROOT/convention-check-XXXXXX")"
mkdir -p "$SCRATCH/src/main/kotlin"
cp build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt \
   build-logic/convention/src/main/kotlin/CommonDepsConventionPlugin.kt \
   build-logic/convention/src/main/kotlin/JvmApplicationConventionPlugin.kt \
   "$SCRATCH/src/main/kotlin/"
# The `kotlin-dsl` plugin needs a repository for its own stdlib and reflect
# dependencies — without one the build fails before it reaches the compiler.
cat > "$SCRATCH/settings.gradle.kts" <<'EOF'
dependencyResolutionManagement {
    repositories { mavenCentral() }
}
rootProject.name = "convention-check"
EOF
printf 'plugins { `kotlin-dsl` }\n' > "$SCRATCH/build.gradle.kts"
./gw --project-dir "$SCRATCH" compileKotlin
```

`KotlinMultiplatformLibraryConventionPlugin.kt` is not in that list, and it
is not only because it needs more on the classpath: **it does not compile as
written.** Three of its references name things that do not exist —

- it imports `com.android.build.api.dsl.MultiplatformExtension`, and the AGP
  type for that plugin is `com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension`;
- it applies the plugin id `com.android.multiplatform-library`, and AGP ships
  `com.android.kotlin.multiplatform.library` — the id this repository's own
  catalog uses;
- it applies the plugin id `io.room.incremental`, and Room 3 publishes only
  `androidx.room3` — also the id this repository's own catalog uses.

Only the first is a compile error. The other two are plugin ids that fail when
the plugin is applied, and nothing applies it.

Adding AGP and KGP to the classpath does not make it compile. It is a
reference implementation of a migration that has not happened yet, and
nothing compiles it.

Two defects this tree carried for a while, the first of which the compile
catches:

- `getByType<VersionCatalogsExtension>()` needs `import org.gradle.kotlin.dsl.getByType`.
  The Kotlin DSL script files get that import implicitly; a plain Kotlin source
  file does not. Without it the compiler reports the failure at the *call site*
  ("no value passed for parameter 'type'") and never at the missing import, so
  the error reads like a Gradle API problem rather than an import problem.
- `versionCatalog.findVersion(key).get()` compiled, but it threw
  `NoSuchElementException: No value present` when the key was missing — and the
  message named nothing. `orElseThrow { … }` names the key instead. This one is
  a runtime difference, so the compile step does not catch it. Both defects are
  fixed; the second one is why the file now says
  `the version catalog has no \`kotlin\` version`.

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
    // ... 8 more
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
    alias(libs.plugins.koin.compiler)
    alias(libs.plugins.detekt)
}

// AFTER
plugins {
    id("kotlin-multiplatform-library-convention")
    alias(libs.plugins.composeMultiplatform) // the convention applies the compose COMPILER, not this
    alias(libs.plugins.koin.compiler)
    alias(libs.plugins.detekt) // custom rules need the local plugin
}
```

**`androidApp/build.gradle.kts`**:

```kotlin
// BEFORE
plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.detekt)
    alias(libs.plugins.koin.compiler)
    `maven-publish`
}

// AFTER
plugins {
    id("android-application-convention")
    alias(libs.plugins.composeCompiler) // the convention does not apply it
    alias(libs.plugins.koin.compiler)
    `maven-publish`
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
    alias(libs.plugins.koin.compiler)
    `maven-publish`
}

// AFTER
plugins {
    id("jvm-application-convention")
    alias(libs.plugins.composeMultiplatform) // the convention does not apply it
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.koin.compiler)
    `maven-publish`
}
```

**`mcp-server/build.gradle.kts`**:

```kotlin
// BEFORE
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.detekt)
    alias(libs.plugins.koin.compiler)
    application
}

// AFTER
plugins {
    id("jvm-application-convention")
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.koin.compiler)
}
application
```

### Step 4: Verify

```bash
./gw :shared:compileKotlinJvm :androidApp:assembleDebug :desktopApp:run --dry-run
./gw :shared:jvmTest
```
