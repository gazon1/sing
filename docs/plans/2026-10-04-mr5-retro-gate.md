# MR-5 Retro-Gate

**Phase 5 / MR-5** — 4 новых Maestro journeys, committed as `7b910290`; hotfixes — `4176c5cf`.

---

## Gate Results

| Gate | Result |
|------|--------|
| `MaestroFlowTagsTest` (id-селекторы) | ✅ BUILD SUCCESSFUL |
| `TAGS.md` golden | ✅ без изменений (`TestTags.kt` не трогали на первом проходе) |
| Прогон journeys 05–08 на эмуляторе | ❌ 4 бага продукта, найденных и починенных |
| `:shared:jvmTest --tests "*agenda.*"` с пин-тестами | ✅ 12/12 в `SavedAgendaViewsRepositoryImplTest` |
| Пин-тесты против reintroduced бага | ✅ оба падают (проверено временным revert'ом) |

---

## Problems Found & Fixed

### 1. `duplicateForProfile` писал копию в namespace **исходного** профиля (CRITICAL)

**Files:** `SavedAgendaViewsRepositoryImpl.kt:68`, `SavedAgendaListViewModel.kt`, `ProfileAwareCurrentUser.kt`

**Проблема.** Journey 07 (`copy-to-profile`) не мог закрыться: после копирования карточка
не появлялась у целевого профиля, а список источника оставался с одной карточкой.

Разбор дал две независимые ошибки, наложенные друг на друга:

1. `duplicateForProfile` вызывал `upsert(copy)`, а `upsert` по контракту ре-штампует
   `userId = currentUser.scopedUserId.value` (`SavedAgendaViewsRepositoryImpl.kt:53`).
   Копия физически возвращалась в namespace источника.
2. `SavedAgendaListViewModel` резолвил id **строки профиля** (`"work"`), а не её
   **scoped** userId (`"work/test-user"`). Даже если бы `upsert` не пере-штамповывал,
   копия оказалась бы в несуществующем namespace.

**Почему это не поймали unit-тесты:** `SavedAgendaListViewModelTest` работает на
`FakeRepo`, а `FakeRepo.duplicateForProfile` честно делает то, что просят, — то есть
обеспечивает корректное поведение, которого реальная реализация не имеет.

**Фикс.** `duplicateForProfile` пишет напрямую через `agendaViewDao.upsert(copy.toEntity())`,
с KDoc, объясняющим, что обход `assertCanWrite` здесь намеренный (операция кросс-user по
определению, а guard существует чтобы ловить *случайные* кросс-user записи). Маппинг
профиль→userId вынесен в top-level `scopedUserIdFor()`, который теперь используют и
`ProfileAwareCurrentUser`, и copy-flow — два call-site'а больше не могут разойтись.

**Тест.** Два пин-теста в `SavedAgendaViewsRepositoryImplTest`, работающие на реальном
DAO (`AgendaViewDao.listAllForUser`), потому что API репозитория отдаёт только текущего
пользователя и физически не может выразить «строка лежит в чужом namespace»:

- `duplicateForProfile_writesTheCopyIntoTheTargetProfileNamespace` — копия в целевом
  namespace, с новым id;
- `duplicateForProfile_doesNotRestampTheCopyIntoTheSourceNamespace` — в источнике
  по-прежнему ровно одна строка, оригинал нетронут.

Проверено на «зубцы»: с возвратом `return upsert(copy)` оба падают.

---

### 2. `ProfileBootstrapper` не запускался ни Android-, ни desktop-приложением

**Files:** `desktopApp/.../main.kt`, `androidApp/.../SingularityApp.kt`

**Проблема.** Пикер copy-to-profile на свежей установке пуст: в таблице `profiles` нет ни
одной строки, а `ProfileBootstrapper.run()` (idempotent, создаёт дефолтный `Personal`)
вызывался только из `mcp-server`.

**Почему это не поймали тесты.** Ни один тест не проходит цикл «холодный старт →
открыть пикер профилей»: harness пересоздаёт Koin-граф на каждый тест и не запускает
Application/тот же `main()`. Это дыра именно в покрытии точки входа, а не логики.

**Фикс.** `ProfileBootstrapper.run()` на старте обоих приложений, в фоновом scope.

---

### 3. Строки `ListPickerSheet` были недоступны для UI-автоматизации

**Files:** `ListPickerDsl.kt`, `ListPickerSheet.kt`, `SavedAgendaListScreen.kt:213`

**Проблема.** У `ListPickerItem` не было `testTag` вообще — общий пробел компонента,
а не agenda-специфичный. Journey 07 пытался тапать `id: profile_item_personal`.

**Фикс (частичный).** `testTag` добавлен в DSL и в sheet, `ProfilePickerSheet` размечает
свои строки. Journey при этом всё равно переведён на селектор по тексту `"Personal"`:
строки `ModalBottomSheet` не отдают resource-id в semantics-дереве UIAutomator, поэтому
`testTag` там не спасает. Это осознанный компромисс — селектор по тексту менее устойчив,
и это отмечено ниже как долг.

---

### 4. Фикстуры Maestro: предзаполненное поле имени и tab-зависимый FAB

**Files:** `Maestro/helpers/seed-task.yaml`, journeys 06/07/08

**Проблемы.**

- `inputText` дописывал текст к предзаполненному имени («Journey View» +
  «Copy Source» → мусор). Фикс — `- eraseText` перед вводом.
- `seed-task` создавал задачу с активного таба. FAB на табе **Today** префиллит
  `dueDate = today` (`FabActionResolver`), и «задача без даты» туда не попадала вовсе.
  Фикс — явный переход на `nav_tab_inbox` перед тапом по FAB; заодно поправлен
  комментарий, который утверждал, что Inbox группирует по дате (на деле он относит
  такие задачи к секции «No Date»).

**Наблюдение.** Обе проблемы — общие для всего `Maestro/helpers/`, а не только для
agenda: любой flow, полагающийся на `seed-task`, был на грани. Фикс сделан в хелпере,
поэтому чинит всех потребителей.

---

## Deferred to ADR / Backlog

| Item | Reason | Where |
|------|--------|-------|
| Journey 07 выбирает строку пикера по тексту, а не по `testTag` | `ModalBottomSheet` не отдаёт resource-id в UIAutomator; нужен либо testTag на уровне окна, либо переход на `testTag`-пикинг через contentDescription | backlog |
| Холодный старт приложения (Application/main → Koin → bootstrapper) не покрыт ни одним тестом | Harness пересоздаёт граф на тест; обе находки этого ретро (профиль, пикер) живут именно в этой зоне | backlog |
| `assertCanWrite` обходится в `duplicateForProfile` сознательно | Документировано KDoc'ом; механического enforcement «никаких прямых DAO-записей из репозитория» нет. Тот же класс долга, что и ledger #2 в `2026-09-27-write-layer-soundness.md` | ADR-worthy, отложено |
| Плановый §5 «ретро-гейт после каждой фазы» не был выполнен как артефакт для MR-5 | Фаза 5 шла начиная с прогона journeys, и ретро накопился здесь; до этого момента journeys ни разу не проходили зелёными, поэтому «прогон и ретро» разделились | этот документ |

---

## MR-5 Summary

Journeys 05–08 добавлены и закоммичены; прогон на эмуляторе выявил 4 бага, из них один
критический (тихая потеря копии при cross-profile операции — ровно тот класс, который
невозможно заметить без реального прогона на устройстве). Все четыре починены в `4176c5cf`
и закрыты пин-тестами там, где это возможно.

Главный урок фазы: **юнит-тесты на VM давали ложное зелёное** на `FakeRepo`.
Реальный DAO-путь не был покрыт ничем. Добавлены два теста, работающих на настоящем SQLite.
