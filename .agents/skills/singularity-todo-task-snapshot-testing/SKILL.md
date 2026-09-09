---
name: singularity-todo-task-snapshot-testing
description: Roborazzi snapshot testing for Compose Multiplatform — Maven coordinates, plugin setup, Compose capture API, CI integration, and known issues.
---

# Snapshot Testing for Compose Multiplatform with Roborazzi

This skill documents how to add pixel-diff snapshot tests to the project's `androidHostTest` source set using Roborazzi.

## Maven Coordinates (verified 2026-09-08)

**Correct coordinates** (verified on Maven Central):
```
Group:    io.github.takahiom.roborazzi
Artifact: roborazzi
Version:  1.74.0 (latest stable)
```

**In `libs.versions.toml`:**
```toml
[versions]
roborazzi = "1.74.0"

[libraries]
roborazzi = { module = "io.github.takahiom.roborazzi:roborazzi", version.ref = "roborazzi" }

[plugins]
roborazzi = { id = "io.github.takahirom.roborazzi", version.ref = "roborazzi" }
```

**Common mistake:** The old group `io.github.nickid` does not exist on Maven Central.

## Gradle Plugin vs Library

Roborazzi has two parts:
1. **Gradle plugin** (`io.github.takahirom.roborazzi`) — applies `roborazzi` DSL to build scripts
2. **Library** (`io.github.takahiom.roborazzi:roborazzi`) — provides test APIs

Both must be added:
```kotlin
// shared/build.gradle.kts
plugins {
    alias(libs.plugins.roborazzi)  // Gradle plugin
}

androidHostTest.dependencies {
    implementation(libs.roborazzi)  // Library
}
```

## Basic Test Setup

```kotlin
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)  // required for Compose snapshotting
class TaskHeroSectionSnapshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<Activity>()

    @Test
    fun taskHero_defaultTask() {
        composeRule.setContent {
            MaterialTheme {
                TaskHeroSection(
                    title = "Buy groceries",
                    description = "Milk, eggs",
                    isCompleted = false,
                    kind = TaskKind.Task,
                    isSomeday = false,
                    actions = TaskDetailActions.Empty,
                )
            }
        }
        // Captures bitmap — compare with baseline in CI
        composeRule.onRoot()
            .captureToBitmap()
            .use { bitmap ->
                val diff = compareBitmaps(baselineBitmap, bitmap)
                assertThat(diff.pixelDiffPercentage).isLessThan(1.0f)
            }
    }
}
```

## Compose Capture API

The `captureToBitmap()` extension is on `AndroidComposeRule`. The exact API depends on the Roborazzi version:

### For `createAndroidComposeRule()` (Robolectric)
```kotlin
import androidx.compose.ui.test.captureToBitmap

composeRule.onRoot()
    .captureToBitmap()
    .compareWith(baselinePath = "test/assets/baseline.png")
```

### Alternative: using `captureFromInstrumentation()` (older API)
```kotlin
import androidx.compose.ui.test.junit4.createComposeRule
import org.roborazzi.captureFromInstrumentation

val file = File.createTempFile("snapshot", ".png")
captureFromInstrumentation(outputFile = file)
```

## CI Integration

```yaml
# .github/workflows/snapshot-tests.yml
name: Snapshot Tests
on: [push, pull_request]
jobs:
  snapshot:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: 21
      - name: Run snapshot tests
        run: ./gradlew :shared:testAndroidHostTest
      - name: Upload diffs on failure
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: snapshot-diffs
          path: build/reports/roborazzi/**/*.png
```

## When to Use Snapshot Tests

Snapshot tests are appropriate for:
- **Pure presentational components** with no state (e.g., `TaskHeroSection`, `KindSheet`)
- **Regression detection** when making mechanical refactors (color, spacing, typography)
- **Cross-platform verification** — same test runs on both Android and JVM

Snapshot tests are NOT appropriate for:
- **Stateful screens** — prefer widget tests with assertions on state
- **Logic-heavy components** — unit tests with fake repositories
- **Rapidly-changing UI** — every change requires baseline update

## Target Components (from Phase 6 plan)

| Component | Test count | Variants |
|---|---|---|
| `TaskHeroSection` | 3 | default, completed, note+someday |
| `TaskMetaChipsRow` | 3 | default, overdue, future |
| `TaskChecklistSection` | 3 | default, empty, max-items |
| `TaskSubtasksSection` | 3 | default, empty, mixed |
| `RemindersSection` | 3 | default, empty, 1-reminder |
| `AttachmentsSection` | 3 | file, link, image |
| `KindSheet` | 2 | Task selected, Note selected |
| `ConfirmDeleteSheet` | 1 | default |

## Anti-Patterns

1. **No `captureToBitmap()` without `GraphicsMode(GRAPHICS_MODE_NATIVE)`** — Skia/LayoutLib rendering produces different pixels than real Android
2. **No hardcoded baseline paths** — use `File.createTempDir()` and compare in CI artifact
3. **No `Bitmap.getPixel()` manual comparison** — use Roborazzi's built-in diff with configurable threshold
4. **No snapshot tests for screens with state** — widget tests are more reliable

## Known Issues

- Roborazzi version `1.25.0` referenced in older ADR used the wrong group (`io.github.nickid`). Always use `io.github.takahiom.roborazzi` and check Maven Central for the latest version.
- The `captureToBitmap()` Compose extension API may vary between versions — verify with a minimal test before writing many tests.
- `androidHostTest` runs on Robolectric (JVM), not a real device — pixel-perfect rendering differences between Robolectric and a real device are expected. Use a >1% threshold.
