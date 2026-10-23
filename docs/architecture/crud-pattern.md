# Канонический CRUD-паттерн (новой фичи)

> **Reference, not a procedure.** Это форма новой фичи в слоистой архитектуре:
> `Ids.kt` → `*Domain.kt` → `*Repository.kt` → `*RepositoryImpl.kt` → `*UseCase.kt`
> → `*ViewModel.kt` → `*Screen.kt`. Каждый слой отвечает за своё; следующий не знает
> о предыдущем, кроме случаев, перечисленных ниже.

## Цепочка слоёв

| Слой | Файл | Что в нём живёт |
|---|---|---|
| Идентификатор | `Ids.kt` | `@JvmInline value class` на типе-идентификаторе — типизация вместо `String` |
| Домен | `*Domain.kt` | Валидация: правила предметной области, инварианты, отказоустойчивость к невалидному вводу |
| Репозиторий (контракт) | `*Repository.kt` | Интерфейс: `Result<T>`, `suspend`-функции, `Flow` для наблюдаемых списков |
| Репозиторий (реализация) | `*RepositoryImpl.kt` | Room: DAO, маппинг entity ↔ domain, транзакции |
| Use case | `*UseCase.kt` | **Только реальная логика** |
| ViewModel | `*ViewModel.kt` | `StateFlow<SealedUiState>`, sealed `Intent`, инъектированный scope |
| Экран | `*Screen.kt` | Compose: рендер состояния, отправка интентов |

## Правила, которые паттерн навязывает

- **Use case несёт логику, а не проксирует.** Pass-through use case (только
  `repository.doSomething()` без своей логики) не нужен: ViewModel инжектит репозиторий
  напрямую. Enforced by `PassThroughUseCase` detekt rule.
- **Репозиторий — `suspend` + `Result<T>`.** Методов `*Blocking()` в репозиториях нет:
  блокирующий вызов держит поток и приводит к дедлокам.
- **ViewModel — `StateFlow` + sealed `Intent`**, scope инъектирован (`AutoCloseableCoroutineScope`),
  а не `viewModelScope`: собственный scope ломает тестируемость.
- **Экран не знает о Room.** Экран работает с `UiState` и шлёт интенты.

## Где взять скелет

Процедура добавления фичи целиком (создание файлов, регистрация в DI, тесты) —
скилл `singularity-todo-feature-scaffold`. Тестовый каркас ViewModel —
скилл `singularity-todo-testable-vm`, харнесс тестов — `singularity-todo-test-helpers`.
