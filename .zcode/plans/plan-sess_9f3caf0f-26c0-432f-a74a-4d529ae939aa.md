# Финальный план: починить компиляцию + правильно подключить detekt и ktlint

## Часть C1. Исправить 9 классов компиляционных ошибок

После рефакторинга в коде остались разорванные импорты, остаточные ссылки на удалённые use-кейсы и продублированные объявления. Исправляем точечно — без переписывания логики.

### 1. `Mappers.kt` — импорты ID-типов
`shared/src/commonMain/kotlin/com/singularity/todo/core/database/Mappers.kt:3-5` — заменить:
```kotlin
import com.singularity.todo.feature.tasks.NoteId
import com.singularity.todo.feature.tasks.ProjectId
import com.singularity.todo.feature.tasks.TagId
```
на правильные пакеты:
```kotlin
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.tags.TagId
```

### 2. `TaskRepository.kt` — TagId + suspend
`shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/TaskRepository.kt:35,38` — заменить импорт:
```kotlin
import com.singularity.todo.feature.tags.TagId
```
(вместо `com.singularity.todo.feature.tasks.TagId`, которого нет).

В `setTags` (строки ~112-120) убрать `.first()` из `forEach`-блока (suspend нельзя в non-suspend лямбде `forEach`):
```kotlin
override suspend fun setTags(taskId: TaskId, tagIds: List<TagId>): Result<Unit> = runCatching {
    val existing = taskDao.getTagIdsForTask(taskId.value).first()
    existing.forEach { tagId ->
        taskDao.removeTagRef(taskId.value, tagId)
    }
    tagIds.forEach { tagId ->
        taskDao.upsertTagCrossRef(TaskTagCrossRef(taskId = taskId.value, tagId = tagId.value))
    }
}
```
(первая строка с `.first()` уже вынесена — это правильно; нужно проверить структуру и убрать `.first()` отовсюду, где он случайно попал внутрь `forEach`.)

### 3. `NotesViewModel.kt` — удалить дубликаты, починить currentUserId
`shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/NotesViewModel.kt:73-78` — удалить дублирующийся блок `_notes`/`state`. Оставить только одну пару.

Строка 119: `store.create(currentUserId, id, "", "")` — `currentUserId` не существует. Заменить на `userId.first()` (импортировав `kotlinx.coroutines.flow.first`), либо собрать `uid` из существующего `userId: Flow<UserId>`:
```kotlin
fun createNote(): String {
    val id = NoteId.generate()
    viewModelScope.launch {
        val uid = userId.first()  // suspend внутри launch — корректно
        store.create(uid, id, "", "")
    }
    _editorState.value = EditorState.Editing(id = id.value, title = "", html = "", isDirty = false)
    return id.value
}
```

### 4. `NotesScreen.kt` — каскад
После фикса NotesViewModel `state` будет `StateFlow<NotesUiState>`, и `val state by viewModel.state.collectAsState()` заработает.

### 5. `ProjectsViewModel.kt` — softDelete → delete
`shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/ProjectsViewModel.kt:42` — `projectRepo.softDelete(id)` → `projectRepo.delete(id)`.

### 6. `SyncEngine.kt` — sync.serverVersion → serverVersion
`shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncEngine.kt:191` — `entity.sync.serverVersion` → `entity.serverVersion` (так как `SyncableEntity` объявляет `serverVersion: Long` напрямую, не через embedded SyncColumns).

### 7. `SearchScreen.kt` — добавить импорт first
`shared/src/commonMain/kotlin/com/singularity/todo/feature/search/SearchScreen.kt:45` — `value = settingsRepo.userId.first()` нужно `import kotlinx.coroutines.flow.first`.

### 8. `NotesRepository.kt` / `ProjectsRepository.kt` — каскад
После фикса Mappers.kt (пункт 1) extension-функции `toEpochMillis`, `toIsoOrNull` и т.п. разрешатся сами.

### 9. `FakeRepositories.kt` — каскад
После фикса TaskRepository (пункт 2) ошибки в `FakeTaskRepository` исчезнут.

---

## Часть C2. Подключить detekt + ktlint

### Шаг 1. `gradle/libs.versions.toml`

**`[versions]`** — добавить перед `# Testing`:
```toml
# Static analysis / Formatting
detekt = "1.23.7"
ktlint = "12.1.2"
```

**`[libraries]`** — добавить в конце:
```toml
# detekt rules
detekt-formatting = { module = "io.gitlab.arturbosch.detekt:detekt-formatting", version.ref = "detekt" }
```

**`[plugins]`** — добавить в конце:
```toml
detekt = { id = "io.gitlab.arturbosch.detekt", version.ref = "detekt" }
ktlint = { id = "org.jlleitschuh.gradle.ktlint", version.ref = "ktlint" }
```

### Шаг 2. Root `build.gradle.kts`

В блок `plugins { }` добавить (после kotlinMultiplatform):
```kotlin
alias(libs.plugins.detekt) apply false
alias(libs.plugins.ktlint) apply false
```

### Шаг 3. `shared/build.gradle.kts`

В блоке `plugins { }` добавить (apply, не false):
```kotlin
alias(libs.plugins.detekt)
alias(libs.plugins.ktlint)
```

В конце файла добавить блоки:
```kotlin
detekt {
    toolVersion = libs.versions.detekt.get()
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    allRules = false
    ignoreFailures = true  // временно, на первое время
    source.setFrom(
        "src/commonMain/kotlin",
        "src/commonTest/kotlin",
        "src/jvmMain/kotlin",
        "src/jvmTest/kotlin",
    )
}

dependencies {
    detektPlugins(libs.detekt.formatting)
}

ktlint {
    version.set(libs.versions.ktlint.get())
    outputToConsole.set(true)
    ignoreFailures.set(true)  // временно
    filter {
        exclude("**/build/**")
    }
}
```

### Шаг 4. `desktopApp/build.gradle.kts`

Аналогично:
```kotlin
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}
```

В конце:
```kotlin
detekt {
    toolVersion = libs.versions.detekt.get()
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    ignoreFailures = true
    source.setFrom("src/jvmMain/kotlin", "src/jvmTest/kotlin")
}

dependencies {
    detektPlugins(libs.detekt.formatting)
}

ktlint {
    version.set(libs.versions.ktlint.get())
    outputToConsole.set(true)
    ignoreFailures.set(true)
    filter {
        exclude("**/build/**")
    }
}
```

### Шаг 5. Отключить formatting в `config/detekt/detekt.yml`

В соответствии с решением пользователя: чтобы избежать дублирования жалоб, отключить `formatting:` группу в detekt. Ktlint владеет форматированием/импортами/wildcards, detekt — логикой/сложностью/потенциальными багами.

В `config/detekt/detekt.yml` найти секцию `formatting:` и установить `active: false` (либо удалить секцию целиком, так как detekt-formatting plugin мы НЕ подключаем как `detektPlugins`, и форматирование-группа в detekt не нужна).

Уточнение: поскольку мы подключаем `libs.detekt.formatting` через `detektPlugins`, это плагин detekt, который добавляет правила форматирования в сам detekt. Чтобы избежать дублирования — НЕ подключать `detekt-formatting` plugin. Тогда ktlint — единственный владелец форматирования.

Финальный план: НЕ подключать `detekt-formatting` plugin. Использовать только базовый detekt (complexity, potential-bugs, naming, performance, exceptions, style).

### Шаг 6. `check.sh` уже настроен

`check.sh` уже вызывает `:shared:detekt :desktopApp:detekt` и `:shared:ktlintCheck :desktopApp:ktlintCheck`. Ничего менять не нужно.

---

## Часть C3. Финальная проверка

1. `./gradlew :shared:compileKotlinJvm` — все 9 классов ошибок устранены, компиляция зелёная.
2. `./gradlew :shared:jvmTest :desktopApp:jvmTest` — тесты проходят.
3. `./gradlew :shared:detekt :desktopApp:detekt` — detekt запускается без падения (с `ignoreFailures=true` — логирует warning'и, но не валит).
4. `./gradlew :shared:ktlintCheck :desktopApp:ktlintCheck` — ktlint запускается без падения.
5. `./gradlew :androidApp:assembleDebug` — Android сборка не сломана.

После зелёной проверки — коммит:
```
fix(compile+lint): рефакторинг финализация + detekt 1.23.7 + ktlint 12.1.2

- R1+R5+R6+R10+R11: убрано ~500 строк boilerplate, исправлен баг require-throw
- Исправлены 9 классов компиляционных ошибок после рефакторинга
- Подключены detekt 1.23.7 + ktlint 12.1.2 в shared+desktopApp
- ignoreFailures=true временно (baseline после первого прогона)
- ktlint владеет форматированием, detekt — логикой (formatting plugin не подключён)
```

---

## Файлы для редактирования

| Файл | Изменения |
|---|---|
| `shared/src/commonMain/kotlin/com/singularity/todo/core/database/Mappers.kt` | 3 строки импортов |
| `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/TaskRepository.kt` | 1 импорт + правка `setTags` |
| `shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/NotesViewModel.kt` | удалить дубль, починить `currentUserId` |
| `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/ProjectsViewModel.kt` | softDelete → delete |
| `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncEngine.kt` | entity.sync.serverVersion → entity.serverVersion |
| `shared/src/commonMain/kotlin/com/singularity/todo/feature/search/SearchScreen.kt` | добавить `import kotlinx.coroutines.flow.first` |
| `gradle/libs.versions.toml` | +5 строк (versions, libraries, plugins) |
| `build.gradle.kts` | +2 строки (root apply false) |
| `shared/build.gradle.kts` | +2 plugin + detekt/ktlint блоки + 1 dep |
| `desktopApp/build.gradle.kts` | +2 plugin + detekt/ktlint блоки + 1 dep |

Итого: 10 файлов, ~50 строк изменений.