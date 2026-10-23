# Тесты: как их писать и запускать

> **Reference, не процедура.** Этот документ отвечает на один вопрос: *как устроены
> тесты в этом репозитории и как их писать, чтобы они попадали в прогон и падали
> там, где надо*. Чем проверять правку целиком — в `docs/architecture/verification.md`;
> что делает CI — в `docs/architecture/ci.md`.

## Где живут тесты и как они запускаются

- `commonTest` (pure Kotlin) выполняется внутри `./gw :shared:jvmTest` — отдельного таска
  нет.
- `jvmTest` — Room + SQLite + Konsist arch tests.
- Desktop UI-тесты — отдельная задача: `./gw :desktopApp:test` (не `jvmTest`).
- Android host-тесты (Robolectric) — `./gw :shared:testAndroidHostTest`.
- Полный набор, как в CI: `./gw :shared:jvmTest -Ptest.population=full`.
- Одна категория медленных: `./gw :shared:jvmTest -Ptest.tags=slow:migration`.

**Fake вместо моков** — все двойники в `test/fakes/FakeRepositories.kt`. MockK остаётся
для исходящих вызовов, но state-тесты пишутся на фейках.

## Теги: fast и slow

Каждый тестовый класс обязан иметь `@Tag`, иначе `-Ptest.population=fast` в CI (и
`-Ptest.population=full` в ночном прогоне) молча его исключит (`TestTagCoverageTest`).

**`slow` = класс пересекает границу процесса** (Compose-харнесс, реальный файл/БД/часы,
Konsist-скан, spawn), не «долгий».

**Под-тег `slow` — что именно медленно.** Медленный класс несёт второй тег из
словаря: `slow:konsist`, `slow:migration`, `slow:compose`, `slow:data`, `slow:platform`.
Основной тег (`fast`/`slow`) остаётся: CI выбирает по нему, и класс без него не попал
бы ни в один прогон. Словарь закрытый — гейт `TestTagCoverageTest` проверяет и основной
тег, и под-тег.

**`@Tag("slow")` — тест, который локальный цикл не гоняет.** Дефолтный прогон исключает
`slow`, поэтому поломка в таком тесте видна только в CI: `McpServerEndToEndTest` лежал
сломанным в `main` несколько мержей. Полный прогон (`-Ptest.tags=fast,slow`) обязан
выполнить каждый `slow`-класс — это проверяет `scripts/check-slow-tests-ran.py` (шаг CI
сразу после прогона: следующие Gradle-шаги могут перезаписать отчёт). Модули `adr-cli`
и `adr-core` тоже гоняются в CI — иначе их `slow`-классы не выполнялись бы нигде.

Никаких skipped: `@Disabled` или упавший assumption guard валят `check-test-runs.py`.

## Тест ViewModel — через харнесс

`testVm` из `shared/src/commonTest/.../test/helpers/RunVmTest.kt` — и это гейт:
`SharedHarnessConventionsTest`, правило «runVmTest is used for ViewModel tests» — свой
scope руками = падение. Исключения и их причины — `EXEMPT_VM_TEST` в
`shared/src/jvmTest/.../arch/SharedHarnessConventionsTest.kt` (карта «файл → причина»;
типичная повестка: поток часов не кончается → виртуальное время не доводится до idle,
scope не может быть тестовым).

```kotlin
// Три формы теста (singularity-todo-test-helpers skill):
testVm({ vm: MyVm -> vm.state }) { MyVm(deps, scope = this) }
ctx.act { it.onIntent(Intent.Load) }
ctx.assertIs<UiState.Content>()
awaitState { vm.state.value is UiState.Content }   // виртуальное время, не delay()
```

## Coroutine-тесты

Виртуальное время вместо реального: `advanceUntilIdle()` доводит корутины до конца,
`delay(N)` в тесте означает реальное ожидание. Отмена проверяется явно: корутина,
которую нужно остановить, отменяется через scope теста, а не «перестаёт падать».

## Диагностика падений

Любой JVM-тест при падении пишет `build/diagnostics/<TestClass>/coroutines.txt`
(состояние, контекст, job-иерархия, стектрейлы) — читай **первым**: кадры приложения
раньше kotlinx указывают, где корутина была, умерла она там, где `lastObservedStackTrace`,
а одинаковые стектрейсы — не доказательство утечки. ADR
`2026-10-03-kotlinx-coroutines-debug.md`, разбор — шаг 5 `debugging-investigation`.

## BAN list

| Запрещено | Почему | Альтернатива |
|---|---|---|
| `stateIn` в ViewModel | держит upstream active 5s, ломает тесты без subscriber | `MutableStateFlow + scope.launch { }.collect {}` |
| `viewModelScope` в production | tight coupling, нетестируемо | инъектированный `AutoCloseableCoroutineScope` |
| `runBlocking` в production | blocking поток, deadlock risk | `scope.launch { }` |
| `delay(N)` в тестах | реальное время, не virtual time | `advanceUntilIdle()` + debounce mocking |
| `vm.state.launchIn(scope)` | костыль вокруг `stateIn` | canonical VM pattern |

### Canonical VM pattern

```kotlin
class MyViewModel(
    private val deps: MyDeps,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : ViewModel() {
    init { addCloseable(scope) }
    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()
    init { scope.launch { repo.observe().collect { _state.value = it } } }
}
```

Подробности: `singularity-todo-testable-vm`, `singularity-todo-vm-migration-playbook`.

## Testing notes

**`kotlin.test.assertTrue` does NOT accept a lambda as message** — use
`assertTrue(condition, "description")`.
**`import kotlin.io.path.*` bypasses detekt's `NoWildcardImports` rule** — use explicit
imports (`kotlin.io.path.exists`, `kotlin.io.path.readText`,
`kotlin.io.path.isRegularFile`).
**Stale-test-classes is NOT a thing (verified 2026-10-04)** — a plain rerun after editing
a test recompiles and re-executes; `--rerun-tasks` — только отладочная костыль. ADR
`2026-10-04-configuration-cache-hardening` §A3.

## Каталог тегов — золотой файл

**Добавил `TestTag` → пересобери каталог.** `Maestro/TAGS.md` — золотой файл: его
сгенерированная часть сверяется с `TestTags.kt`, и расхождение падает в
`TestTagsCatalogJvmTest` — то есть на полном JVM-прогоне (≈40 мин). Пересборка:

```bash
./gradlew :shared:jvmTest -PupdateGoldens=true   # переписывает сгенерированную часть TAGS.md
```

`scripts/check-test-tags-catalog.py` делает ту же сверку за секунды, без JVM, — он и
стоит в гейтах; полный прогон нужен, чтобы *переписать* файл, а не чтобы *узнать*, что
он устарел.

## Куда дальше

- Чем проверять правку: `docs/architecture/verification.md`.
- Что делает CI с этими тестами: `docs/architecture/ci.md`.
- Скиллы: `singularity-todo-test-helpers`, `singularity-todo-testable-vm`,
  `singularity-todo-test-tag-strategy`, `singularity-todo-desktop-compose-ui-tests`.
