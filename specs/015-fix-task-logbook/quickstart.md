# Quickstart: Validating This Feature End to End

**Feature**: [spec.md](./spec.md) · **Plan**: [plan.md](./plan.md)
**Date**: 2026-10-25

Как проверить, что логбук работает. Каждый пункт — проверяемый факт, а не «код выглядит
правильно».

---

## Prerequisites

- JDK 21, `./gw` (Gradle с приватным `GRADLE_USER_HOME`).
- Python 3 с `scripts/src` в `PYTHONPATH` (пакет `traceability` ставится из `scripts/src/`).
- Для Q4: подключённый Android-телефон, `adb` в PATH, Maestro CLI (`maestro` ≥ 2.x).

## Ground rules

- Gradle только через `./gw`; два Gradle-вызова одновременно не запускать.
- Долгий запуск — одна фоновая задача: лог в уникальный файл
  (`build/diagnostics/<что>-<метка>.log`), ожидание одним `./wait-for <лог> <паттерн>`.
- Перед работой с телефоном — `./sg device-holders` (устройство должно быть свободно).
- Компиляция — сразу после связной правки: `./gw :shared:compileKotlinJvm`.

---

## Q1. Привязка выживает (репозиторный тест, реальный SQLite)

```bash
./gw :shared:jvmTest --tests "*NotesRepositoryLogbookAttachmentTest*"
```

Ожидается: заметка, созданная через `createForTask`, остаётся в `watchForTask(taskId)` и после
точечного `updateContent`; заметка без привязки и заметка чужой задачи в `watchForTask` не появляются.
Отдельно закреплено, что `createWithContent` поверх существующей строки привязку **теряет** —
это whole-row write и он же механизм бага.

## Q2. Поведение редактора (VM-тест)

```bash
./gw :shared:jvmTest --tests "*NoteEditorLogbookAttachmentTest*"
```

Ожидается: после `createNoteForTask(taskId)` в репозитории нет заметок; после правки
заголовка и автосейва появляется заметка с `taskId` и набранным заголовком; после второй
правки `taskId` сохраняется; незатронутый черновик не снимает флаг `isNew`.

## Q3. Чтение логбука (VM-тест коллектора)

```bash
./gw :shared:jvmTest --tests "*TaskLogbookCollectorTest*"
```

Ожидается: пустой список → `Empty`; прикреплённая заметка → `Loaded`; чужая задача → не
попадает; отмена scope → коллектор завершается.

## Q4. E2E на подключённом телефоне

```bash
./sg device-holders                      # устройство свободно?
./gw :androidApp:installDebug -Pandroid.device=0O65321F32204DD5
adb shell am start -a android.intent.action.VIEW -d "todo-debug://seed?reset=content"
maestro test Maestro/flows/tasks/logbook/TASK-LOG-01.yaml
```

Ожидается: флоу зелёный; скриншот `tasks/logbook-note` показывает карточку «Logbook (1)» с
заметкой, заголовок которой совпадает с `${RUN_ID} Logbook note`.

Штатный рецепт (если `run-maestro` есть в PATH) делает установку и прогон сам:

```bash
just tests::gate-maestro regression 0O65321F32204DD5
```

## Q5. Регрессия до фикса

Прогнать Q1–Q3 на исходном `HEAD` (до правки): тесты обязаны **падать**. Зелёные тесты до
фикса означают, что они не фиксируют дефект, и бесполезны как регрессия.

## Q6. Гейты репозитория

```bash
just lint-changed                        # detekt по изменённым файлам
SKIP_ADB=1 ./check.sh                    # статические гейты, компиляция, тесты, detekt
python3 scripts/run-gate-tests.py        # сьют гейтов целиком
```

Точечные гейты, которых касается фича:

```bash
python3 scripts/check-maestro-flow-catalogue.py
python3 scripts/check-test-tags-catalog.py
python3 scripts/check-ui-wait-budgets.py
python3 scripts/ci/validate-maestro-selectors.py
python3 -m traceability validate
just trace-coverage && just trace-coverage-check
python3 scripts/check-traceability-ratchet.py
python3 scripts/check-adr-references.py
```

Ожидается: все — код 0. Матрица покрытия (`docs/testing/coverage-matrix.md`) должна быть
перегенерирована и лежать в том же коммите, что и новый сценарий.

## Q7. Сверка с решением пользователя

- В логбуке — только прикреплённые заметки. Заметка, которая лишь ссылается на задачу через
  `task://`, остаётся в карточке «Связанное» и в логбук не попадает.
- Пустых «(untitled)»-строк в логбуке не бывает: открытие «+» и выход без записи не оставляют
  ничего.
