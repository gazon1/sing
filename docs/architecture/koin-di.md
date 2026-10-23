# DI: Koin 4.x (pure DSL)

> **Reference, not a procedure.** Формы Koin DSL, которые используются в проекте,
> и места, где лежат биндинги. Сам граф собирается Koin'ом — этот файл отвечает на
> «как зарегистрировать» и «где это лежит», а не на «что зарегистрировано».

## DI-валидация

**`koin-compiler-plugin` 1.2.1** (`gradle/libs.versions.toml`, подключение в `build.gradle.kts`)
даёт compile-time проверку графа: опечатка в `get()` или незарегистрированная зависимость
валится на компиляции, а не в рантайме.

Аннотации (`@Single`, `@Factory`) **не используются** — только чистый DSL.

## Сборка модулей: список, а не `includes()`

**Никогда** не используй `*domainModule().toTypedArray()` в `modules()` — это даёт
KOIN-W003 (graph unverifiable): плагин не может проверить граф, собранный из массива.
Используй list composition:

```kotlin
modules(listOf(...) + domainModule() + listOf(...))
```

Список, а не `includes()`: `includes()` создаёт child scope, и биндинги из него не видны
соседним модулям на parent level.

## Формы DSL

| DSL | Когда |
|---|---|
| `single { Repo(get()) }` | singleton |
| `viewModelOf(::Vm)` | все VM без runtime-параметров (**prefer**) |
| `viewModel { (p: Param) -> Vm(get(), p) }` | VM с runtime-параметрами |
| `koinViewModel()` / `koinViewModel { parametersOf(p) }` | инъекция VM в Composable (не `koinInject()`) |
| `factory { Vm(...) }` | **Never** для VM — memory leak |
| `koinInject()` | репозитории и сервисы (не VM) |

## Где лежат биндинги

`core/di/Modules.kt` — **фасад-агрегатор** (`coreLoggingModule()` +
`domainModule(): List<Module>`), не источник истины. Реальные биндинги — в per-domain
модулях, каждая отдаёт свою `*Module()`:

- `core/di/CoreDiModule.kt`
- `core/di/CalendarDiModule.kt`
- `core/di/NotesDiModule.kt`
- `core/di/ProjectsDiModule.kt`
- `core/di/TagsDiModule.kt`
- `core/di/TasksDiModule.kt`
- `feature/*/*DiModule.kt` — по одному модулю на фичу
- `core/di/PlatformModule.android.kt`, `core/di/PlatformModule.jvm.kt` — платформенные биндинги

## Ссылки

- ADR `2026-09-27-di-module-aggregator-narrative.md` — почему список модулей, а не `includes()`.
- Скилл `singularity-todo-koin-di` — процедура регистрации нового биндинга.
