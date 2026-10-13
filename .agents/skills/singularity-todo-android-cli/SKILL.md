---
name: singularity-todo-android-cli
description: Use when working with Android CLI commands, Android Studio integration, or Journeys for Android app development. Covers android studio commands (analyze-file, find-declaration, find-usages, render-compose-preview, version-lookup, open-file), Journey creation and execution, skill management (list, add), and project analysis. Relevant when "android studio check", "android studio find-declaration", "render compose preview", "journey", "android init", "android skills list" is requested.
---

# Android CLI 1.0 — Android Studio Integration & Journeys

Android CLI provides a lightweight interface for AI agents to perform Android development tasks and access powerful Android Studio capabilities. This skill covers the key features relevant to agent-based Android development.

## Installation & Setup

```bash
# Install Android CLI (one of:)
winget install -e --id Google.AndroidCLI
apt-get install android-cli
brew install android-cli

# Initialize for agent use
android init

# Update to latest
android update
```

## Android Studio Integration

When a project is open in Android Studio (Quail or later), agents can establish a connection and access powerful IDE tools.

### Connection Check

```bash
android studio check
# Output:
# pid: 32942
# version: Android Studio
# Projects:
#     READY     JetSet /path/to/AndroidStudioProjects/jetset-main
```

### Core Commands

| Command | Purpose |
|---------|---------|
| `android studio analyze-file <path>` | Analyzes a file for errors and warnings using the editor's built-in inspections |
| `android studio find-declaration <symbol>` | Finds the exact definition site of a symbol (class, method, variable, field, constant, or Android resource/color) across the project using semantic resolution |
| `android studio find-usages <symbol>` | Finds all references and declarations of a symbol across the entire project using semantic analysis |
| `android studio render-compose-preview <path>` | Renders a Jetpack Compose UI Preview and returns a path to the image and UI hierarchy |
| `android studio version-lookup <dependency>` | Get the latest available versions for specified app dependencies from Google Maven repository |
| `android studio open-file <path>` | Opens a file directly in Android Studio (useful for Compose Previews, performance traces) |

### Example Workflows

**Analyze a file for issues:**
```bash
android studio analyze-file shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/TasksViewModel.kt
```

**Find a symbol definition:**
```bash
android studio find-declaration TaskEntity
```

**Find all usages:**
```bash
android studio find-usages ProjectRepository
```

**Render Compose Preview:**
```bash
android studio render-compose-preview feature/tasks/src/androidMain/kotlin/com/singularity/todo/feature/tasks/TasksScreen.kt
```

**Check dependency versions:**
```bash
android studio version-lookup androidx.room:room-runtime
```

**Open file in IDE:**
```bash
android studio open-file feature/tasks/src/androidMain/kotlin/com/singularity/todo/feature/tasks/TasksScreen.kt
```

## Journeys

Journeys are natural language descriptions of core user experiences that agents can create and execute to navigate apps like a user would.

### Creating a Journey

```bash
# Create a new journey (agent generates natural language description)
android journey create <journey-name>

# Example: creating a journey for task creation flow
android journey create "Create New Task"
# Agent records: tap add button → fill title → set priority → save
```

### Running a Journey

```bash
# Execute a journey to navigate the app like a user
android journey run <journey-name>

# Example
android journey run "Create New Task"
```

Journeys unlock new ways to:
- **Test** critical user flows automatically
- **Validate** UI states across experiences
- **Collect data** on user journey patterns
- All driven by natural language, executed by the agent

## Android Skills

Android CLI includes skills that help models understand and execute Android-specific patterns.

```bash
# List available skills
android skills list

# Install a specific skill
android skills add --skill=<skill-name>

# Available skills include:
# - display-glasses: Display Glasses and Jetpack Compose Glimmer for XR
# - camera-migration: Migration to CameraX from Camera1/Camera2
# - perfetto-sql: Natural language to Perfetto SQL queries
# - adaptive-ui: Instructions to make UI adapt to different devices
# - testing-setup: Creates a basic testing strategy
# - styles: Jetpack Compose Style API adoption and migration
# - app-functions: Analyze codebases for AppFunctions recommendations
```

## Integration with Android Build

Android CLI works alongside standard Gradle commands:

```bash
# Standard Gradle build (still primary)
./gradlew :androidApp:assembleDebug

# Android CLI provides IDE-aware analysis
android studio analyze-file $FILE

# Use android studio commands to:
# - Get deeper static analysis than gradle
# - Find semantic references across the project
# - Render previews without running the app
# - Check dependency versions programmatically
```

## Key Use Cases for Agents

1. **Code Analysis**: Use `find-declaration` and `find-usages` instead of grep for semantic-aware navigation
2. **Compose Preview Rendering**: `render-compose-preview` to verify UI without device/emulator
3. **Dependency Management**: `version-lookup` for programmatic version checking
4. **Journey-Based Testing**: Create journeys for critical user flows, run them to validate
5. **Static Analysis**: `analyze-file` for IDE-level inspections beyond what Gradle reports

## Relationship to ADB Workflow

- **ADB workflow** (`singularity-todo-adb-workflow`): Physical device interaction, screenshots, logcat
- **Android CLI**: IDE integration, semantic code analysis, Compose previews, Journeys
- Use ADB for runtime verification on device; use Android CLI for code-level intelligence
