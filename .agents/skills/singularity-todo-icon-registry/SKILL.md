---
name: singularity-todo-icon-registry
description: KMP-native pattern for user-facing icon selection registries (project icons, tag emoji, priority icons, note color icons). Covers object-based ImageVector registry with named keys, ModalBottomSheet picker UI with LazyVerticalGrid, DB storage as String (not ImageVector), and the 18-icon v1 budget rule.
---

# Icon Registry Pattern

## The Problem

When adding a project icon picker, tag emoji picker, or any user-facing icon selector, how do you:
1. Define the available icons in a type-safe, KMP-compatible way?
2. Store the selected icon in the database?
3. Render the icon in a picker UI?

## The Pattern: `object XxxIconRegistry`

### Registry definition

```kotlin
// shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/ProjectIconRegistry.kt
package com.singularity.todo.feature.projects

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Curated set of 18 icons for project customisation.
 * Storage: [icon] column stores the String KEY (e.g. "Work"), NOT the ImageVector.
 * Lookup: [iconByKey] returns the ImageVector, or null if unknown (fallback to Folder).
 */
object ProjectIconRegistry {

    val Work: ImageVector = Icons.Filled.Work
    val Personal: ImageVector = Icons.Filled.Home
    val Shopping: ImageVector = Icons.Filled.ShoppingCart
    val Fitness: ImageVector = Icons.Filled.FitnessCenter
    val Travel: ImageVector = Icons.Filled.Flight
    val Food: ImageVector = Icons.Filled.Restaurant
    val Health: ImageVector = Icons.Filled.LocalHospital
    val Finance: ImageVector = Icons.Filled.AccountBalance
    val Creative: ImageVector = Icons.Filled.Lightbulb
    val Tech: ImageVector = Icons.Filled.Devices
    val Learning: ImageVector = Icons.Filled.School
    val Social: ImageVector = Icons.Filled.Group
    val Home: ImageVector = Icons.Filled.Home
    val Star: ImageVector = Icons.Filled.Star
    val Favorite: ImageVector = Icons.Filled.Favorite
    val Bookmark: ImageVector = Icons.Filled.Bookmark
    val Pets: ImageVector = Icons.Filled.Pets
    val Nature: ImageVector = Icons.Filled.Park
    val Celebration: ImageVector = Icons.Filled.Cake

    /**
     * All icons for the picker UI.
     * Each entry: key (String, used for DB storage) → ImageVector.
     * Key format: lowercase, no spaces (e.g. "fitness_center" not "Fitness Center").
     */
    val all: List<Pair<String, ImageVector>> = listOf(
        "work" to Work,
        "personal" to Personal,
        "shopping" to Shopping,
        "fitness" to Fitness,
        "travel" to Travel,
        "food" to Food,
        "health" to Health,
        "finance" to Finance,
        "creative" to Creative,
        "tech" to Tech,
        "learning" to Learning,
        "social" to Social,
        "home" to Home,
        "star" to Star,
        "favorite" to Favorite,
        "bookmark" to Bookmark,
        "pets" to Pets,
        "nature" to Nature,
        "celebration" to Celebration,
    )

    private val keyToVector: Map<String, ImageVector> = all.toMap()

    /**
     * Lookup ImageVector by storage key.
     * Returns null if key is null, empty, or unknown (caller decides fallback).
     */
    fun iconByKey(key: String?): ImageVector? = key?.let { keyToVector[it.lowercase()] }

    /**
     * Human-readable names for the picker labels.
     */
    val names: List<String> = all.map { it.first.replaceFirstChar { c -> c.uppercase() } }
}
```

### Why NOT Painter resources

```kotlin
// ❌ WRONG — painterResource fails in commonTest (JVM target has no Android resources)
@Composable
fun IconFromResource(key: String) {
    Image(
        painter = painterResource("drawable/ic_project_$key.xml"),
        contentDescription = key,
    )
}

// ❌ WRONG — enum with painterResource() in companion object fails the same way
enum class ProjectIcon { Work, Home, ... }

// ✅ CORRECT — ImageVector is resolved at compile time, works in commonTest
val icon = ProjectIconRegistry.iconByKey("work") // ImageVector, no Compose runtime needed
```

`painterResource` requires an Android `Resources` object, which doesn't exist in `commonTest` on the JVM target. `ImageVector` references (like `Icons.Filled.Work`) are resolved at compile time — they work everywhere.

### DB storage

```kotlin
// ProjectEntity — icon column stores the String key
@ColumnInfo("icon")
val icon: String? = null, // e.g. "work", "personal", null

// Project domain model — icon is String?
data class Project(
    val id: ProjectId,
    val icon: String? = null, // NOT ImageVector — String is platform-agnostic
    // ...
)

// Repository mapper — entity ↔ domain
fun ProjectEntity.toDomain(): Project = Project(
    id = ProjectId.fromString(id),
    icon = icon, // String — platform-agnostic
    // ...
)
```

## Picker UI: ModalBottomSheet + LazyVerticalGrid

```kotlin
// feature/projects/components/ProjectIconPickerSheet.kt
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectIconPickerSheet(
    selectedKey: String?,
    onSelect: (String) -> Unit,      // emits the String key, not ImageVector
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                "Choose icon",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(200.dp),
            ) {
                items(ProjectIconRegistry.all) { (key, vector) ->
                    val selected = key == selectedKey
                    Surface(
                        onClick = { onSelect(key) },
                        shape = CircleShape,
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            vector,
                            contentDescription = key,
                            modifier = Modifier.padding(12.dp),
                            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
```

## Rendering the Icon

```kotlin
@Composable
fun ProjectIcon(
    iconKey: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val vector = ProjectIconRegistry.iconByKey(iconKey) ?: Icons.Filled.Folder
    Icon(
        imageVector = vector,
        contentDescription = iconKey,
        modifier = modifier,
        tint = tint,
    )
}
```

Default fallback: `Icons.Filled.Folder` (not `Home` — Folder is more universal for projects).

## v1 Budget: 18 Icons Maximum

Cognitive load studies show that icon pickers with >20 items cause decision paralysis. Start with 18:

| Category | Icons |
|---|---|
| Work | Work, Business, School, AccountBalance |
| Personal | Home, Personal, Favorite, Star, Bookmark |
| Health | FitnessCenter, LocalHospital, Restaurant |
| Life | Flight, ShoppingCart, DirectionsCar, Beach, Park, Pets |
| Creative | Lightbulb, Brush, MusicNote, Code |
| Social | Group, Cake, EmojiEvents |

**Adding more icons** is a follow-up decision — don't do it in the initial PR.

## Testability

The registry is pure Kotlin — testable without Compose:

```kotlin
@Test
fun `iconByKey returns correct vector`() {
    assertEquals(ProjectIconRegistry.Work, ProjectIconRegistry.iconByKey("work"))
    assertEquals(ProjectIconRegistry.Personal, ProjectIconRegistry.iconByKey("personal"))
}

@Test
fun `iconByKey returns null for unknown key`() {
    assertNull(ProjectIconRegistry.iconByKey("nonexistent"))
    assertNull(ProjectIconRegistry.iconByKey(null))
}

@Test
fun `all contains all named icons`() {
    assertEquals(18, ProjectIconRegistry.all.size)
    assertTrue(ProjectIconRegistry.all.all { it.second != Icons.Filled.Warning })
}
```

## Reuse for Other Features

**Status check first.** As of 2026-10-04, `ProjectIconRegistry` is the **only**
registry that exists in the codebase. The pattern below is real and worth reusing;
the three applications listed after it are **not implemented** — they were
documented as if they were, which sent an agent looking for types that were never
written.

- [x] **Project icons** — `ProjectIconRegistry` (`core/ui/`). Real; use it as the
  reference implementation.
- [ ] **Tag emoji icons** — would be a `TagIconRegistry`. **Does not exist.**
  Tag icons are currently stored as a plain emoji `String` on the tag itself.
- [ ] **Priority icons** — would be a `PriorityIconRegistry`. **Does not exist.**
  Priority renders from a fixed `when` over `TaskPriority` using Material icons.
- [ ] **Note color accents** — would be a `NoteColorRegistry`. **Does not exist.**

If you are adding one of the missing registries, the pattern is: store a String
key on the domain model, resolve to `ImageVector` at render time in `core/ui/`,
and pin the public list with a test asserting the expected size. Do not assume a
registry already exists because this file used to claim it did.
