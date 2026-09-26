---
name: singularity-todo-stable-json
description: When adding kotlinx.serialization to a KMP project, always use StableJson instead of local `Json { ... }` blocks. Covers why the three config flags matter, how to migrate an existing local Json block, and how to add @Serializable to a new type.
---

# StableJson — Centralized Serialization Config

Every `Json { ... }` block in this codebase must use `StableJson` from `core/serialization/StableJson.kt`. No local copies.

## Why these three flags

```kotlin
val StableJson: Json = Json {
    classDiscriminator = "_type"   // polymorphic sealed class → "_type" field
    encodeDefaults = true          // serialize fields at default value
    ignoreUnknownKeys = true       // tolerate unknown fields on decode
}
```

| Flag | Why it matters |
|---|---|
| `classDiscriminator = "_type"` | Sealed classes (e.g. `Either`, `UiState`) serialize their concrete subtype name. Without this the JSON has no way to reconstruct the right type on decode. |
| `encodeDefaults = true` | Drafts, cache entries, backup records must encode all fields — even `null`/`false`/`0`. Otherwise a restored draft appears partially empty. |
| `ignoreUnknownKeys = true` | Schema evolves. A draft saved with field `foo` decoded after `foo` is renamed to `bar` throws without this flag. |

## When to use StableJson

Use `StableJson` for **any** persistent serialization:
- Draft stores (`DraftStore<T>`)
- Backup/restore payloads
- Sync payloads
- Any `kotlinx.serialization` encode/decode in `core/` or `feature/`

Do **not** use `StableJson` for:
- Network API responses (those have their own schema requirements)
- Ephemeral in-memory caching that doesn't survive process death

## Adding @Serializable to a new type

Place the annotation on the **primary constructor** for `data class`, or on the companion object for `@JvmInline value class`:

```kotlin
@Serializable
data class TaskDraft(
    val title: String = "",
    val description: String = "",
    val priority: TaskPriority = TaskPriority.None,
    val dueDate: DueDateOption = DueDateOption.None,
    val dueTime: LocalTime? = null,
)

@Serializable
@JvmInline
value class TaskId(val value: String)
```

**Always use `@Serializable` on `enum class`** when the enum is stored in a field of a serializable class:
```kotlin
@Serializable
enum class TaskPriority { None, Low, Medium, High, Urgent }
```

## Migrating a local Json block

**Before:**
```kotlin
private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}
```

**After:**
```kotlin
import com.singularity.todo.core.serialization.StableJson
// use StableJson directly
```

**Files that had copies (already migrated):**
- `core/sync/SyncEngine.kt`
- `core/backup/BackupImporter.kt`
- `core/backup/BackupExporter.kt`

**If you find a new local `Json { ... }` block**, replace it with `StableJson` and delete the local copy.

## Testing serialization

Test that a type round-trips through JSON correctly:

```kotlin
class TaskDraftSerializationTest {
    @Test
    fun taskDraft_roundTrips() {
        val draft = TaskDraft(title = "Test", priority = TaskPriority.High)
        val json = StableJson.encodeToString(TaskDraft.serializer(), draft)
        val decoded = StableJson.decodeFromString(TaskDraft.serializer(), json)
        assertEquals(draft, decoded)
    }

    @Test
    fun taskDraft_encodesDefaults() {
        val draft = TaskDraft()
        val json = StableJson.encodeToString(TaskDraft.serializer(), draft)
        // must contain all fields, not just non-defaults
        assertTrue(json.contains("title"))
        assertTrue(json.contains("priority"))
    }
}
```

Tests live in `shared/src/commonTest/kotlin/com/singularity/todo/core/serialization/`.

## Sealed interface hierarchies with `classDiscriminator`

`StableJson` uses `classDiscriminator = "_type"`. This means every sealed interface leaf MUST have a unique discriminator value. `@Serializable` generates this from the class name by default, but when using `@SerialName` explicitly, the discriminator is the `@SerialName` value:

```kotlin
@Serializable
sealed interface Selector {
    @Serializable
    @SerialName("date_bucket")
    data class DateBucket(val bucket: RelativeBucket) : Selector

    @Serializable
    @SerialName("all_of")
    data class AllOf(val children: List<Selector>) : Selector

    @Serializable
    data object Overdue : Selector  // auto-discriminator: "Overdue"
}
```

**If you get `classDiscriminator` errors** when encoding a sealed interface:
1. Check that ALL concrete leaves are `@Serializable` (abstract leaves can't be instantiated)
2. Check that `Selector` itself is NOT `@Serializable` (only the leaves)
3. For `data class` leaves inside sealed interfaces, `@SerialName` must be explicit if the class name is ambiguous

**AgendaEngine `Selector` test:** `AgendaPresetsTest` uses `StableJson.encodeToString()` to verify all preset definitions round-trip correctly. If a new `Selector` variant is added without `@Serializable`, the test fails.

## AgendaEngine `@Serializable` checklist

When adding a new `Selector` variant:
1. Add `@Serializable` to the data class/data object
2. Add `@SerialName("...")` if the class name is compound (e.g. `DateRange` → `@SerialName("date_range")`)
3. Add a test in `AgendaPresetsTest`:
   ```kotlin
   @Test
   fun `Selector.DateRange round-trips`() {
       val s = Selector.DateRange(LocalDate(2024, 1, 1), LocalDate(2024, 12, 31))
       val json = StableJson.encodeToString(Selector.serializer(), s)
       val decoded = StableJson.decodeFromString(Selector.serializer(), json)
       assertEquals(s, decoded)
   }
   ```


## Value classes in @Serializable models

A `@JvmInline value class` used as a property of a `@Serializable` class serializes
as the underlying primitive (plain string — wire-format friendly), but only when
the value class itself is annotated `@Serializable`. Otherwise the compiler fails
with "Serializer has not been found". See `UserId` in `core/ids/`.

## SyncableEntity checklist

Any class implementing `SyncableEntity` MUST be `@Serializable` — `toJson()` uses
`serializer<T>()` reflection, which throws SerializationException at runtime
(not compile time) for non-annotated classes. Regression test:
`TaskSyncSerializationTest` (asserts the wire format + no-throw). Add the same
per-entity test when a new entity type lands.
