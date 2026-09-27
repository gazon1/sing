---
name: singularity-todo-di-graph-testing
description: Catch Koin DI missing bindings before the app reaches a device. Use when adding new repositories, ViewModels, AI tools, or platform ports to the DI graph, or after any NoDefinitionFoundException fix. Covers DiGraphTest on JVM (covers coreDomainModule + JVM platformModule + JVM aiToolsModule) and AndroidDiGraphTest on Robolectric (covers Android-specific platformModule + Android-stub aiToolsModule + Robolectric Context).
---

# Singularity TODO — DI Graph Testing

**Problem this prevents:** Koin `NoDefinitionFoundException` only surfaces at runtime when a Composable first tries to resolve the missing type — i.e. on the user's device. JVM unit tests pass because they don't exercise Android-only stubs; androidHostTest was empty (just `assertEquals(3, 1+2)`). Result: a `KoogAgentService` missing-binding crash shipped to a real device in production.

## Two-layer graph verification

| Source set                    | Test                 | What it covers                                                                                     |
|-------------------------------|----------------------|----------------------------------------------------------------------------------------------------|
| `shared/src/jvmTest/`         | `DiGraphTest`        | `coreDomainModule()` + `PlatformModule.jvm` + `jvmAiToolsModule()`                                 |
| `shared/src/androidHostTest/` | `AndroidDiGraphTest` | `coreDomainModule()` + `PlatformModule.android` + `androidAiToolsModule()` + Robolectric `Context` |

Both call `checkModules { modules(...) }` from `org.koin.test.check.checkModules` which walks the entire graph and fails fast on any missing binding.

## Reference implementation

### `shared/src/jvmTest/kotlin/com/singularity/todo/test/KoinGraphValidationTest.kt`

```kotlin
package com.singularity.todo.core.di

import org.junit.Test
import org.koin.test.check.checkModules

class DiGraphTest {
    @Test
    fun `core domain graph verifies on JVM`() {
        checkModules {
            modules(coreDomainModule(), platformModule())
        }
    }

    @Test
    fun `AI tools require API key — validated by integration test`() {
        // AI tools (OpenAI client) need a real API key — checkModules()
        // throws ExceptionInInitializerError from OpenAILLMClient("")
        // during singleton creation. Validated by integration tests instead.
    }
}
```

### `shared/src/androidHostTest/kotlin/com/singularity/todo/test/KoinGraphValidationTest.kt`

```kotlin
package com.singularity.todo.core.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.singularity.todo.core.security.FakeSecureStorage
import com.singularity.todo.core.security.SecureStoragePort
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.dsl.module
import org.koin.test.check.checkModules
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])  // Robolectric 4.16 maxSdkVersion=36
class AndroidDiGraphTest {

    @Test
    fun `android graph verifies on Robolectric`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        checkModules {
            modules(
                module {
                    single<Context> { context }
                    // EncryptedSharedPreferences fails under Robolectric
                    single<SecureStoragePort> { FakeSecureStorage() }
                },
                coreDomainModule(),
                platformModule(),
                aiToolsModule(),
            )
        }
    }
}
```

## Required setup

### `gradle/libs.versions.toml`

```toml
# Testing
junit = "4.13.2"
robolectric = "4.16"   # maxSdkVersion=36
```

```toml
# Libraries — Robolectric JUnit runner
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
androidx-test-core = { module = "androidx.test:core", version.ref = "androidx-testExt" }
```

### `shared/build.gradle.kts`

```kotlin
android {
    withHostTest {
        isIncludeAndroidResources = true
    }
}

sourceSets {
    jvmTest.dependencies {
        implementation(libs.koin.test)
    }

    getByName("androidHostTest").dependencies {
        implementation(libs.kotlin.test)
        implementation(libs.kotlin.testJunit)
        implementation(libs.koin.test)
        implementation(libs.androidx.testExt.junit)
        implementation(libs.androidx.test.core)
        implementation(libs.robolectric)
    }
}
```

**Gotcha:** `getByName("androidHostTest")` is required — direct `androidHostTest { ... }` doesn't work in KMP Kotlin DSL.

## Running

```bash
./gradlew :shared:jvmTest                    # DiGraphTest
./gradlew :shared:testAndroidHostTest        # AndroidDiGraphTest
```

## Critical patterns when adding new bindings

### 1. AI tools that depend on `ai.koog.prompt.executor.model.PromptExecutor`

This is the **single most common missing-binding bug** because:
- JVM `aiToolsModule()` registers the real Koog executor
- Android `aiToolsModule()` cannot (Koog is JVM-only)
- An AI tool that depends on `PromptExecutor` will fail `checkModules()` on Android

**Fix:** Make AI dependencies nullable in ViewModels:

```kotlin
// commonMain — AI use cases are nullable so Android works without Koog
class TasksViewModel(
    private val taskRepo: TaskRepository,
    private val refineTask: RefineTaskUseCase? = null,  // null on Android
    // ...
) : ViewModel() {
    fun refineTaskTitle(task: Task) = viewModelScope.launch {
        refineTask?.invoke(task.title, task.description)
            ?.onSuccess { ... }
            ?: _aiResult.emit(AiActionResult.Error("AI not available"))
    }
}
```

### 2. Platform-specific implementations that are JVM-only

`JvmPromptExecutorPort` exposes `val executor` so AI tools can use it.
Don't `as JvmPromptExecutorPort` cast in commonMain — register in JVM `platformModule()`.

### 3. Bindings that exist in JVM `platformModule()` but not Android

**Always run BOTH tests after touching the DI graph.** The JVM test passes if Android `platformModule()` is missing a binding — they have separate registrations. Example: `NotesStore` (Android `RoomNotesStore`, JVM `JdbcNotesStore`) was missing on Android and only caught by `AndroidDiGraphTest`.

### 4. Robolectric + EncryptedSharedPreferences

`AndroidSecureStorage` uses `EncryptedSharedPreferences` which fails under Robolectric (Tink + Keystore). Override in the test module:

```kotlin
modules(
    module {
        single<SecureStoragePort> { FakeSecureStorage() }
    },
    coreDomainModule(),
    platformModule(),
    aiToolsModule(),
)
```

## How `checkModules()` fails

It instantiates every `single`/`factory` definition and recursively resolves `get<T>()` calls. Three failure modes:

1. **NoDefinitionFoundException** — type not registered → missing binding (the main bug)
2. **InstanceCreationException** — type registered but its constructor threw (e.g. `OpenAILLMClient("")`)
3. **KoinApplicationAlreadyStartedException** — called twice in same JVM (use `@Test` per case)

## Anti-patterns to avoid

- **Don't use `@Inject` constructor in Koin Annotations** — Koin resolves the primary constructor directly. `@Inject` is for Dagger/Hilt.
- **Don't call `checkModules { modules(X) }` and `checkModules { modules(Y) }` in same test** — `KoinApplicationAlreadyStartedException`. Use one test per assertion.
- **Don't `factory { get<X>() }` when X doesn't exist yet** — JVM test will catch this, Android test might not.

## Files

| File | Role |
|---|---|
| `shared/src/jvmTest/.../test/KoinGraphValidationTest.kt` | JVM graph test |
| `shared/src/androidHostTest/.../test/KoinGraphValidationTest.kt` | Robolectric graph test |
| `shared/src/commonMain/.../core/di/Modules.kt` | `coreDomainModule()`, `expect fun aiToolsModule()` |
| `shared/src/jvmMain/.../core/di/PlatformModule.jvm.kt` + `AiToolsModule.jvm.kt` | JVM platform |
| `shared/src/androidMain/.../core/di/PlatformModule.android.kt` + `AiToolsModule.android.kt` | Android platform + AI stubs |
