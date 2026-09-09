---
name: singularity-todo-kotlin-idioms
description: Kotlin boilerplate-reduction catalog for Kotlin 2.4. Covers reified, sealed interface, value class, KClass.callBy (Python **kwargs equivalent), context parameters, Result<T> chains, class delegation, Sequence, scope functions, typeOf<T>(), and expect/actual patterns. Use when refactoring, writing new code, or reducing ceremony in this KMP project.
---

# Kotlin Idioms — Boilerplate Reduction Catalog

Covers Kotlin 2.4 features and established patterns relevant to this KMP project.

## 1. `vararg` + spread

```kotlin
// Before: manual array
fun asList(vararg ts: Int): List<Int> = ts.toList()
val r = asList(1, 2, 3)           // direct vararg
val arr = arrayOf(1, 2, 3)
val r2 = asList(0, *arr, 4)        // spread operator
```

- One `vararg` per function; non-trailing params must use named args.
- Primitive arrays need `.toTypedArray()` before spread.

## 2. Named arguments

```kotlin
fun connect(host: String, port: Int = 443, tls: Boolean = true)
connect("api.example.com", tls = false)  // skip port, name tls
```

- Fail with Java interop (unless compiled with `-parameters`).
- After skipping a defaulted param, all subsequent params must be named.

## 3. KClass.callBy — Kotlin's **kwargs equivalent

**`Map<String, Any?>` → constructor args at runtime.** Closest to Python `**kwargs`.

```kotlin
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.primaryConstructor

data class Config(val host: String, val port: Int = 8080, val tls: Boolean = true)

fun instantiate(kClass: KClass<*>, args: Map<String, Any?>): Any {
    val ctor = kClass.primaryConstructor
        ?: error("No primary constructor")
    val params = ctor.parameters.associateBy { it.name }
    val bound = args.mapKeys { (k, _) -> params[k] ?: error("Unknown: $k") }
    return ctor.callBy(bound)  // missing optional params use defaults
}

val c = instantiate(Config::class, mapOf("host" to "localhost"))
// → Config(host=localhost, port=8080, tls=true)
```

**Works in `commonMain`** — `kotlin.reflect.full.primaryConstructor`, `callBy`, `KParameter.isOptional` are all in `kotlin-reflect` and compile in commonMain on JVM targets.
Limitations: `kotlin.reflect.jvm.*` extensions are JVM-only; type erasure still applies to generic types.

## 4. kotlinx-serialization — biggest boilerplate killer

```kotlin
@Serializable
data class User(val name: String, val age: Int)

val json = Json { ignoreUnknownKeys = true }
val s = json.encodeToString(User("Alice", 30))   // {"name":"Alice","age":30}
val u = json.decodeFromString<User>(s)              // User(...)
```

- `@Serializable` + compiler plugin = zero manual serializer code.
- Works in `commonMain` — single dep covers all platforms.
- Use `reified` + `inline` for generic `encodeToString<T>()` / `decodeFromString<T>()`.

## 5. Context parameters (Kotlin 2.4, stable)

```kotlin
context(Clock, Settings)
fun now(): Instant = context<Clock>().now()

// Call site (implicit):
with(clock) with settings {
    val ts = now()  // Clock + Settings in scope
}
```

- Context params replace the removed context receivers.
- Explicit context args still require opt-in (`-Xexplicit-context-arguments`).
- Use `contextOf<Clock>()` for disambiguation when multiple contexts of same type.

## 6. Sealed interface (better than sealed class for states)

```kotlin
// Before (sealed class — requires else branch)
sealed class UiState
class Loading : UiState()
class Content(val data: List<Item>) : UiState()

// After (sealed interface — exhaustive, no else)
sealed interface UiState {
    data object Loading : UiState
    data class Content(val data: List<Item>) : UiState
    data class Error(val message: String) : UiState
}

fun render(s: UiState) = when (s) {
    UiState.Loading -> spinner()
    is UiState.Content -> list(s.data)
    is UiState.Error -> error(s.message)
    // No else needed — compiler enforces exhaustiveness
}
```

- `sealed interface` (Kotlin 1.5+) — when you have no shared constructor state.
- `sealed class` — when subclasses need a shared `val message: String` or similar.

## 7. `@JvmInline value class` — zero-cost wrapper

```kotlin
// Before: manual wrapper with boilerplate
class UserId(val value: String) {
    override fun equals(other: Any?) = other is UserId && other.value == value
    override fun hashCode() = value.hashCode()
    override fun toString() = "UserId($value)"
}

// After: zero-cost, auto equals/hashCode/toString
@JvmInline
value class UserId(val value: String)

// Interface delegation (the underlying Int is auto-boxed as interface)
@JvmInline
value class TaskId(val value: String) : Comparable<String> by value
```

- Exactly one constructor property; always final.
- Compiles to plain `String`/`Int` where possible (unboxed); boxed when used as `T` or nullable.

## 8. Class delegation with `by`

```kotlin
interface Logger { fun info(s: String); fun error(s: String, t: Throwable?) }

// Before: 10 lines of forwarding
class ConsoleLogger(private val d: Logger) : Logger {
    override fun info(s: String) = d.info(s)
    override fun error(s: String, t: Throwable?) = d.error(s, t)
}

// After: one line
class ConsoleLogger(private val d: Logger) : Logger by d
```

- All interface methods auto-forwarded. Override only the custom members.

## 9. Result<T> chains with mapCatching / recoverCatching

```kotlin
// Before: nested try-catch
val result: Int = try {
    try { Integer.parseInt(input) } catch (e: NumberFormatException) { 0 }
} catch (e: Exception) { -1 }

// After: functional chain
val result = runCatching { Integer.parseInt(input) }
    .mapCatching { it * 2 }               // transform, flatMap errors
    .recoverCatching { 0 }                // recover from failure
    .getOrElse { -1 }

// Sealed domain error (for domain-relevant failures)
sealed interface PaymentError {
    data class InsufficientFunds(val available: Double) : PaymentError
    data class NetworkError(val cause: Throwable) : PaymentError
}
```

- Use `Result<T>` at **transport boundary** (network, parse).
- Map to sealed domain errors **once** back in domain code.
- `getOrNull()` / `getOrElse()` for simple fallbacks.

## 10. reified + typeOf<T>()

```kotlin
inline fun <reified T> parseJson(json: String): T =
    Json.decodeFromString(serializer<T>(), json)

// Captures generic type at call site (not erased)
val users: List<User> = parseJson(jsonString)  // correct List<User> serialization

// typeOf<T>() captures nullability and generics (unlike T::class)
import kotlin.reflect.typeOf
inline fun <reified T> typeName() = typeOf<T>().toString()
typeName<List<String?>>()
// → java.util.List<java.lang.String>
```

- `reified` requires `inline`. Can't use `T::class.java` (use `typeOf<T>()` instead).
- Stable since Kotlin 1.8.

## 11. Sequence (lazy, not eager)

```kotlin
// Before: 3 intermediate lists
words.filter { it.length > 3 }.map { it.uppercase() }.take(4)

// After: one element at a time, early termination
words.asSequence()
    .filter { it.length > 3 }
    .map { it.uppercase() }
    .take(4)
    .toList()
```

- Use for large collections, multi-step pipelines, or when you need early termination.
- Small collections (< 10 elements) — eager `List` is faster.

## 12. expect / actual minimum boilerplate via typealias

```kotlin
// commonMain: minimal expect
expect class AtomicInt {
    fun incrementAndGet(): Int
}

// jvmMain: one line
actual typealias AtomicInt = java.util.concurrent.atomic.AtomicInteger
// All members inherited automatically — no manual overrides
```

- Use `typealias` as `actual` when the platform type already provides the API.
- Only use explicit `actual class` when you need to wrap or transform.

## 13. Scope functions — when to use each

| Function | `this`/`it` | Returns | Use when |
|---|---|---|---|
| `let` | `it` | lambda result | Null-check + transform: `x?.let { it.length }` |
| `also` | `it` | context object | Side effects (logging) where object continues |
| `apply` | `this` | context object | Configure object, return it: `File("x").apply { writeText("y") }` |
| `run` | `this` | lambda result | Execute block, return result |
| `with` | `this` | lambda result | Call multiple methods on same object |

**Never nest** scope functions — `this`/`it` confusion is the #1 bug.

## 14. Compose Content Slot Idioms

### Receiver scope: `RowScope.() -> Unit` vs plain `() -> Unit`

```kotlin
// Plain slot — no layout context
@Composable
fun EmptyState(
    title: String,
    actions: @Composable () -> Unit = {},  // ColumnScope if you need Column-specific content
)

// RowScope slot — gives access to Row/Column layout modifiers
@Composable
fun SettingsRow(
    title: String,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row {
        Text(title)
        Spacer(Modifier.weight(1f))
        trailing()  // has RowScope receiver → Modifier.weight() available
    }
}
```

**When to use `RowScope.() -> Unit`:** only when the slot content needs `Modifier.weight()`, `Modifier.fillMaxWidth()`, or other `RowScope`-scoped modifiers.

**When to use plain `() -> Unit`:** for everything else — simpler, more reusable.

### `@Composable inline fun If` / `IfElse`

Avoids `if (cond) { Content() }` at the top level of a composable body:

```kotlin
@Composable
inline fun If(condition: Boolean, content: @Composable () -> Unit) {
    if (condition) content()
}

@Composable
inline fun IfElse(
    condition: Boolean,
    ifTrue: @Composable () -> Unit,
    ifFalse: @Composable () -> Unit,
) { if (condition) ifTrue() else ifFalse() }

// Usage — no top-level if branches
@Composable
fun SomeScreen(state: State) {
    Column {
        If(state.isLoading) { CircularProgressIndicator() }
        IfElse(
            condition = state.error != null,
            ifTrue = { ErrorView(state.error) },
            ifFalse = { ContentView(state.data) },
        )
    }
}
```

`inline` means zero runtime overhead — the lambda is inlined at compile time, just like a macro.

### `typealias` for long slot signatures

```kotlin
typealias EmptyStateActions = @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
typealias SettingsRowTrailing = @Composable RowScope.() -> Unit

@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: EmptyStateActions = {},
)
```

Without the typealias, the signature is harder to read and `ColumnScope.() -> Unit` must be repeated at every call site.

### Packed Actions: `@JvmInline value class` + `sealed class Action`

When a composable needs 4+ callbacks, group them into a value class instead of adding individual parameters:

```kotlin
@JvmInline
value class NoteCardActions(val block: (Action) -> Unit) {
    sealed class Action {
        data class NavigateToNote(val id: NoteId) : Action()
        data class Delete(val id: NoteId) : Action()
        data class TogglePin(val id: NoteId) : Action()
    }
    fun onNavigateToNote(id: NoteId) = block(Action.NavigateToNote(id))
    fun onDelete(id: NoteId) = block(Action.Delete(id))
    fun onTogglePin(id: NoteId) = block(Action.TogglePin(id))
    companion object { val Empty = NoteCardActions {} }
}
```

**Why `sealed class` (not `enum`):** actions with payload (`data class NavigateToNote(val id: NoteId)`) require `sealed class`. `enum class` is only correct for no-payload actions.

**Why `data object`:** for actions with no payload (`data object OpenPriorityPicker : Action()`), use `data object`, not bare `object`.

### Default empty `Actions` companion

Always provide a zero-action singleton for backward compatibility:

```kotlin
companion object { val Empty = NoteCardActions {} }

// Call site — can omit trailing slot:
NoteCard(note = note, actions = NoteCardActions.Empty)
NoteCard(note = note)  // same, using default = {}
```

### `Modifier` parameter last

```kotlin
// ✅ Correct — Modifier is always last
@Composable
fun TaskCard(
    task: Task,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,  // last
)

// ❌ Wrong — Modifier in the middle
@Composable
fun TaskCard(
    task: Task,
    modifier: Modifier = Modifier,  // wrong position
    onClick: () -> Unit = {},
)
```

This matches Compose convention and allows trailing lambda syntax.

## When NOT to use marker interfaces — the BaseEntity trap

Kotlin makes it easy to write `interface Xxx { val id: Id }` — but just because it's possible doesn't mean it's useful.

### The anti-pattern: "simple boundary class" (Effective Kotlin, Rask)

```kotlin
// ❌ Marker interface with no behaviour — a "simple boundary class"
interface BaseEntity<ID> {
    val id: ID
    val createdAt: Instant
    val updatedAt: Instant
}

// Each model "implements" it
data class Task(...) : BaseEntity<TaskId>
data class Project(...) : BaseEntity<ProjectId>
```

**Why it's wrong:** The interface adds no behaviour — it's purely a marker. Effective Kotlin item "Avoid simple boundary classes": a class whose only purpose is to share code between subclasses is a code smell, not an abstraction. In Kotlin, composition + utility functions beat inheritance for this use case.

### The other anti-pattern: `FakeStoreRepository<E>`

```kotlin
// ❌ Abstract class that only holds state — same problem
abstract class FakeStoreRepository<E : BaseEntity<*>> {
    protected val state = MutableStateFlow<Map<String, E>>(emptyMap())
    open fun seed(items: Collection<E>) { ... }
}
```

Every subclass overrides everything meaningful anyway. The base covers only the boilerplate, not the domain logic.

### What to do instead

**For test fakes:** Use `InMemoryStore<E>` as a composition helper:
```kotlin
class FakeTaskRepository : TaskRepository {
    private val store = InMemoryStore<Task>(keyOf = { it.id.value })
    // domain methods (toggleComplete, watchSubtasks) stay here
    suspend fun toggleComplete(id: TaskId) = ...
}
```

**For typed IDs:** Use `@JvmInline value class` directly — no shared interface needed:
```kotlin
@JvmInline value class TaskId(val value: String)
@JvmInline value class ProjectId(val value: String)
```

**For validation:** Use `Either<AppError.Validation, T>` in a domain object, not a marker interface:
```kotlin
object TasksDomain {
    fun validate(input: CreateTaskInput): Either<AppError.Validation, CreateTaskInput> { ... }
}
```

**Rule of Three:** Only introduce a shared abstraction when ≥3 features need it and the abstraction has real behaviour (not just shared fields).

## When to use which pattern

| Situation | Kotlin tool |
|---|---|
| Constructor args from Map (Python **kwargs) | `KClass.primaryConstructor.callBy(mapOf(...))` |
| Reduce JSON mapping boilerplate | `@Serializable` + `Json.encodeToString` |
| Type-safe IDs | `@JvmInline value class` |
| Exhaustive state (UiState) | `sealed interface` |
| Test doubles | `Fake*` + constructor injection, NOT MockK |
| Cross-platform platform impl | `expect`/`actual` + `typealias` |
| Inject time/clock | Constructor param `clock: Clock` (not `Clock.System` direct) |
| Validation errors | `require(name.isNotBlank()) { "msg" }` — NOT `require(...) { throw ... }` |
| 4+ composable callbacks | `@JvmInline value class` + `sealed class Action` |
| Slot needs `Modifier.weight()` | `RowScope.() -> Unit` |
| Empty branch composable | `inline fun If(condition, content)` |
| Long slot type signature | `typealias` |
