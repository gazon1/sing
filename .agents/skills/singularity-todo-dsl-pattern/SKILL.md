---
name: singularity-todo-dsl-pattern
description: Kotlin DSL patterns used in this project. Covers @DslMarker, function literals with receiver, scope class with lateinit var, and the AgendaEngine DSL pattern. Use when creating a new DSL (like Selector, AgendaDefinition, or a configuration builder), when debugging "function literal with receiver" type errors, or when adding a new Selector variant to the agenda.
---

# Kotlin DSL Patterns

## The Core Pattern: `@DslMarker` + Function Literal with Receiver

Kotlin DSLs use two mechanisms: `@DslMarker` (prevents ambiguous `this`) and function literals with receiver (makes `a { }` read as "do something with `a`").

```kotlin
// 1. The scope class (holds accumulated state)
class SectionScope {
    lateinit var name: String
    var order: Int = 0
    var discard: Boolean = false
    private val _selectors = mutableListOf<Selector>()
    val selectors: List<Selector> get() = _selectors

    fun selector(s: Selector) { _selectors.add(s) }
}

// 2. The DSL marker (prevents outer `this` leaking into nested blocks)
@DslMarker
annotation class AgendaDslMarker

// 3. The builder function (function literal with receiver)
@AgendaDslMarker
fun section(
    name: String,
    vararg selectors: Selector,
    order: Int = 0,
    discard: Boolean = false,
    block: SectionScope.() -> Unit,
): Section {
    val scope = SectionScope().apply(block)
    return Section(
        name = scope.name,
        order = scope.order,
        discard = discard,
        selector = Selector.AllOf(scope.selectors),
    )
}
```

## Key Rules

### 1. `@DslMarker` is mandatory for nested DSLs

Without it, `this` in a nested lambda is ambiguous:

```kotlin
// Without @DslMarker — THIS: would resolve to the OUTER scope
agenda {
    section("Overdue") {
        // Inside here, `this` could refer to AgendaScope OR SectionScope
        // Without @DslMarker, the compiler picks the outer one
        selector(Selector.Overdue)  // ambiguous
    }
}

// With @DslMarker — compiler knows `this` = SectionScope
```

### 2. Use `lateinit var` in scope class for mutable, defaulted properties

```kotlin
class SectionScope {
    lateinit var name: String           // set by block parameter, required
    var order: Int = 0                 // has default, can skip
    var discard: Boolean = false        // has default, can skip
}
```

**Why not `var name: String? = null`?** Because a required property that is never set should fail at **build time** (via `lateinit`), not at **runtime** with a cryptic null check.

### 3. `block: SectionScope.() -> Unit` — trailing lambda with receiver

```kotlin
// Call site:
section("Overdue", Selector.Overdue, order = -1) {
    // inside this block, implicit receiver = SectionScope
    // so you can write: name = "Custom Name"  (mutates scope.name)
}

// Equivalent to:
section("Overdue", Selector.Overdue, order = -1, block = {
    this.name = "Custom Name"
})
```

### 4. Accumulators use `mutableListOf` + getter

```kotlin
class AgendaScope {
    private val _sections = mutableListOf<Section>()
    val sections: List<Section> get() = _sections

    fun section(block: SectionScope.() -> Unit) {
        _sections.add(SectionScope().apply(block).build())
    }
}
```

## Common Errors

### "Function literal with receiver has no concrete type"

```kotlin
// ERROR: block parameter type is not concrete
fun section(block: SectionScope.() -> Unit) { ... }

// FIX: add the parameter name 'block'
fun section(block: SectionScope.() -> Unit) { ... }
```

### "Unresolved reference 'name'" in DSL block

```kotlin
// ERROR: 'name' is a 'lateinit var', not initialized
section("Today") {
    // If the block doesn't assign 'name', it's never initialized
    // But here 'name' from SectionScope is being read...
    val label = this.name  // unresolved if lateinit not set
}

// FIX: make sure the outer call sets the required field:
// section("Today", order = 0) { ... }
// Here "Today" is the function parameter, not scope.name
```

### `@Serializable` sealed hierarchies in DSL

When a DSL builds types that are `@Serializable` sealed interfaces:

```kotlin
@Serializable
sealed interface Selector {
    @Serializable data class DateBucket(val bucket: RelativeBucket) : Selector
    @Serializable data object Overdue : Selector
    @Serializable data class AllOf(val children: List<Selector>) : Selector
}

@Serializable
data class Section(
    val name: String,
    val selector: Selector,   // sealed interface, needs @SerialName from subclasses
    val order: Int = 0,
    val discard: Boolean = false,
)
```

All concrete leaves MUST have `@SerialName` (added automatically by `@Serializable` on the leaf). If you get `classDiscriminator` errors in StableJson tests, check that every sealed leaf has the annotation.

## AgendaEngine DSL Reference

```kotlin
// Top-level: agenda(name) { }
agenda("Inbox") {
    section("Today", Selector.DateBucket(RelativeBucket.Today), order = 0)
    section("Overdue", Selector.Overdue, order = -1, discard = true)
}

// Section parameters:
// name: String — display name for section header
// selector: Selector — matching predicate
// order: Int — sort order (default 0)
// discard: Boolean — if true, matched tasks are removed from subsequent sections

// Selector variants (all @Serializable):
Selector.DateBucket(RelativeBucket.Today)       // Today, Yesterday, ThisWeek, etc.
Selector.DateRange(from, to)                  // absolute date range
Selector.Statuses(setOf(TaskStatus.Active))   // by completion status
Selector.Tag(tagId)                           // by single tag (MR1)
Selector.Projects(setOf(projectId))           // by project set
Selector.Pinned                               // pinned tasks
Selector.Completed                            // completed tasks
Selector.Overdue                             // overdue (no due date = not overdue)
Selector.Regexp("query")                     // title matches regex
Selector.AllOf(listOf(s1, s2))               // AND of children
Selector.AnyOf(listOf(s1, s2))               // OR of children
Selector.Not(child)                           // negation
Selector.Anything                            // matches everything
```

## Pure Evaluation Pattern

DSLs build a declarative structure; evaluation is a separate pure function:

```kotlin
// DSL builds:
val definition = agenda("Inbox") {
    section("Today", Selector.DateBucket(RelativeBucket.Today), order = 0)
    section("Overdue", Selector.Overdue, order = -1, discard = true)
}

// Pure evaluation (no side effects):
fun evaluate(tasks: List<Task>, def: AgendaDefinition, today: LocalDate): List<RenderedSection> {
    var remaining = tasks
    return def.sections
        .sortedBy { it.order }
        .mapNotNull { section ->
            val matched = remaining.filter { matches(it, section.selector, today) }
            if (matched.isEmpty() && section.discard) null
            else {
                if (section.discard) remaining = remaining - matched.toSet()
                RenderedSection(section.name, matched, section.badge)
            }
        }
}

fun matches(task: Task, selector: Selector, today: LocalDate): Boolean = when (selector) {
    is Selector.DateBucket -> matchesBucket(task, selector.bucket, today)
    is Selector.Overdue -> task.dueDate?.let { it < today && !task.isCompleted } ?: false
    is Selector.AllOf -> selector.children.all { matches(task, it, today) }
    // ...
}
```

**Key principle:** the DSL is a **data structure** (pure data, no side effects). Evaluation is a **pure function** (same inputs → same outputs). This makes the evaluator easy to test exhaustively with property-based tests.

## Related Skills

- `singularity-todo-feature-scaffold` — when to add a DSL vs a plain data class
- `singularity-todo-pure-formatters` — pure function extraction patterns
