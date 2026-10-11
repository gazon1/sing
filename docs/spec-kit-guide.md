# spec-kit Guide — Workflow Automation for Singularity Todo

> spec-kit is the **primary** workflow engine for this project. It automates multi-step
> change processes: proposal → specs → design → ADR → tasks → implementation → verification.

---

## Архитектура

```
┌─────────────────────────────────────────────────────────────────┐
│                     AI Agent / Human                            │
│                                                                   │
│   Chat: /speckit.specify  /speckit.plan  /speckit.implement …  │
│   Terminal: specify workflow run openspec-change --input …      │
└──────────┬──────────────────────────────────┬──────────────────┘
           │                                  │
           ▼                                  ▼
┌──────────────────────┐           ┌─────────────────────────────┐
│  .specify/issues/    │           │   hooks (extensions.yml)    │
│  633 issue records   │◄──────────│   singularity-adr validate   │
│  (AI-регистратура)   │           │   refresh-decisions-digest   │
└──────────┬──────────┘           └─────────────────────────────┘
           │
           │ references
           ▼
┌──────────────────────────────────────────────────────────────────┐
│                     openspec/changes/                              │
│  proposal.md · design.md · adr.md · tasks.md · specs/            │
│  622 active stubs + 13 archived changes                           │
│                                                                   │
│  ADR-файлы НЕ здесь — они в docs/decisions/YYYY-MM-DD-<slug>.md  │
└──────────────────────────────────────────────────────────────────┘
```

**Ключевое:**

- **Issue registry** (`.specify/issues/`) — **регистратура для AI-агентов**, не хранилище документов
- **OpenSpec документы** остаются в `openspec/changes/<slug>/` — никуда не перемещаются
- **ADR-файлы** — в `docs/decisions/YYYY-MM-DD-<slug>.md`
- **Hooks** — автоматически валидируют ADR-корпус и обновляют DIGEST после implement

---

## Когда что использовать

| Ситуация | Workflow | Пример |
|---|---|---|
| Новая фича с поведенческими изменениями | `openspec-change` | Добавить sync-резолвер |
| Рефакторинг архитектуры / ADR-only | `adr-create` | Koin 4.x миграция |
| CI-прогон без вопросов | `adr-ci` | В CI/CD джобе |
| Несколько ADR из CSV | `adr-batch` | Bulk-миграция |
| Багфикс, тесты, dep update, doc-only | **skip workflow** | Исправить баг |
| Спека уже есть в `openspec/specs/` | `openspec-change` с `has_existing_spec=true` | calendar-sync |
| Анализ консистентности | `/speckit.analyze` | Чат-команда |

---

## Slash-команды (в чате с агентом)

Все начинаются с `/`. Работают в любой точке диалога.

| Команда | Фаза | Что делает |
|---|---|---|
| `/speckit.specify` | Requirements | Определить требования и user stories |
| `/speckit.plan` | Design | Создать технический план |
| `/speckit.tasks` | Tasks | Сгенерировать список задач |
| `/speckit.implement` | Implementation | Выполнить задачи из tasks.md |
| `/speckit.converge` | Verification | Проверить реализацию против спеки |
| `/speckit.checklist` | Gate | Quality gate — чеклист требований |
| `/speckit.analyze` | Any | Кросс-артефактная проверка консистентности |

Это **bundled commands** — идут из коробки spec-kit, не требуют аргументов.

---

## Полные workflows (терминал)

### openspec-change — полный цикл

```bash
specify workflow run openspec-change \
  --input slug=my-feature,title="My Feature",has_existing_spec=false
```

**Параметры:**

| Параметр | Обязательный | Описание |
|---|---|---|
| `slug` | ✅ | Идентификатор (kebab-case, уникальный) |
| `title` | ✅ | Человеческое название |
| `has_existing_spec` | ❌ | `true` если spec уже лежит в `openspec/specs/<slug>/` |
| `ci_mode` | ❌ | `true` — пропускает интерактивные вопросы |

**Фазы и гейты:**

```
1. proposal   ── [gate: review] ──►
2. specs      ── [gate: review] ──►
3. design     ── [gate: review] ──►
4. adr        ── [gate: singularity-adr validate] ──►
5. tasks      ── [gate: singularity-adr validate] ──►
6. implement  ── [gate: singularity-adr validate + refresh DIGEST] ──►
7. converge   ── [final review]
```

После каждого `implement` автоматически:
1. `singularity-adr validate` — валидирует корпус ADR
2. `./scripts/refresh-decisions-digest.sh` — обновляет DIGEST.md

---

### adr-create — только ADR

Для рефакторингов и архитектурных изменений без полного цикла spec → design.

```bash
specify workflow run adr-create \
  --input slug=koin-4-migration,title="Koin 4.x migration"
```

Гейт после создания ADR: `singularity-adr validate`.

---

### adr-ci — CI-валидация

Без интерактивных вопросов. Выходит с кодом 0/1.

```bash
specify workflow run adr-ci --input ci_mode=true
```

Проверяет:
- Корпус ADR валиден (`singularity-adr validate`)
- Реестр issue консистентен

---

### adr-batch — несколько ADR

```bash
# dry-run — посмотреть план
specify workflow run adr-batch --input csv_path=./adr-batch.csv,dry_run=true

# реальный запуск
specify workflow run adr-batch --input csv_path=./adr-batch.csv,dry_run=false
```

CSV-формат:

```csv
slug,title,tags
koin-4-migration,Koin 4.x migration,di,koin
room-3-migration,Room 3 migration,database,room
```

---

## Issue Registry

**Расположение:** `.specify/issues/<slug>.yaml`
**Формат:** одна запись на change

```yaml
---
version: '1.0'
type: openspec-change          # или adr-only
slug: add-log-export
date: 2026-09-15
title: add-log-export
status: accepted               # proposed | accepted | implemented | rejected
change_type: active            # active | archived | stub
speckit_imported: true
adr_refs:                     # ссылки на ADR в docs/decisions/
  - 2026-09-15-add-log-export
tags: []
documents:                    # пути к OpenSpec-документам
  proposal: .../proposal.md
  design:    .../design.md
  adr:       .../adr.md
  tasks:     .../tasks.md
  specs_dir: .../specs/
is_stub: false                # true если нет полного набора документов
openspec_path: ...            # исходный каталог в openspec/changes/
```

**Статусы:**

| Status | Значение |
|---|---|
| `proposed` | В работе, ещё не принято |
| `accepted` | Принято, в процессе или выполнено |
| `rejected` | Отклонено |
| `implemented` | Полностью завершено |

**change_type:**

| type | Значение |
|---|---|
| `active` | Есть документы в `openspec/changes/` |
| `archived` | В `openspec/changes/.archived/` |
| `stub` | Только slug-каталог, документов нет |

---

## Bulk import

Импортирует все 633 OpenSpec change в registry.

```bash
# dry-run — показать что будет
python3 scripts/openspec-import-all.py --dry-run

# реальный импорт (idempotent)
python3 scripts/openspec-import-all.py --replace
```

При импорте:
- Для каждого `<slug>` в `openspec/changes/` создаётся/обновляется `.specify/issues/<slug>.yaml`
- Статус и `is_stub` определяются по фактическому наличию документов
- `--replace` обновляет существующие записи, `--dry-run` только показывает план

---

## Hooks (автоматизация через extensions.yml)

```yaml
hooks:
  after_tasks:
    - singularity-adr validate          # после генерации tasks
  before_implement:
    - singularity-adr validate         # перед implement
  after_implement:
    - singularity-adr validate         # после implement
    - ./scripts/refresh-decisions-digest.sh  # обновить DIGEST.md
```

Все hooks **optional=false** кроме `refresh-digest`.

---

## CI/CD интеграция

### Где прописано

- `.github/workflows/adr-gate.yml` — триггерится на `.specify/**` и `docs/decisions/**`
- `scripts/ci/static-gates.sh` — заменяет `openspec stale` на `spec-kit issue registry current`

### adr-gate.yml джобы

| Джоб | Триггер | Что делает |
|---|---|---|
| `validate` | push/PR | `adr-ci` workflow |
| `check-registry` | PR | `check-speckit-issue-registry.py` |
| `pr-checks` | PR | `openspec-change` с `ci_mode=true` |

### static-gates.sh

```bash
gate advisory spec-kit-issue-registry-current \
  python3 scripts/check-speckit-issue-registry.py
```

Advisory gate — предупреждает о неконсистентности registry, не блокирует.

---

## Конституция

`.specify/constitutions/singularity-todo.md` — принципы проекта для spec-kit:

- Когда использовать `openspec-change` vs `adr-create`
- Когда skip workflow (багфикс, dep update, doc-only)
- Роль issue registry как AI-регистратуры
- Правила поведения при конфликтах ADR

---

## Быстрый старт для новой фичи

### 1. Чат с агентом

```
/speckit.specify
> Нужна фича X
```

Агент задаст уточняющие вопросы, создаст proposal.

### 2. План и задачи

```
/speckit.plan
> После spec в openspec/changes/my-feature/

/speckit.tasks
> После design в openspec/changes/my-feature/
```

### 3. Реализация

```
/speckit.implement
> После tasks.md в openspec/changes/my-feature/
```

Агент итеритивно выполняет задачи. После каждого implement срабатывает hook:
`singularity-adr validate` + `refresh-digest`.

### 4. Верификация

```
/speckit.converge
> Проверить что реализация соответствует spec
```

---

## Быстрый старт для ADR-only

### 1. Терминал

```bash
specify workflow run adr-create \
  --input slug=refactor-auth,title="Refactor Auth",ci_mode=false
```

Workflow создаст:
- `docs/decisions/YYYY-MM-DD-refactor-auth.md`
- `.specify/issues/refactor-auth.yaml`

### 2. Чат

Агент напишет ADR, задаст вопросы. После завершения:
- Hook: `singularity-adr validate`
- Hook: `refresh-digest`

---

## Файлы и каталоги

```
.specify/
  issues/                 # 633 записи — реестр для AI
    <slug>.yaml           # одна запись на change
  workflows/
    adr-create/           # локальный workflow
    adr-batch/
    adr-ci/
    openspec-change/      # полный 7-фазный workflow
  extensions.yml          # hooks для валидации
  constitutions/
    singularity-todo.md   # принципы проекта
  templates/              # шаблоны документов
  commands/               # slash-команды для чата

openspec/
  changes/                # документы всех changes
    <slug>/
      proposal.md
      design.md
      adr.md
      tasks.md
      specs/
    .archived/            # архивные changes
  specs/                  # capability specs

docs/decisions/           # ADR-файлы (НЕ в openspec/)
  YYYY-MM-DD-<slug>.md

scripts/
  openspec-import-all.py  # bulk import в registry
  check-speckit-issue-registry.py  # advisory gate
  refresh-decisions-digest.sh       # обновление DIGEST
```

---

## Отладка

```bash
# Посмотреть все зарегистрированные workflows
specify workflow list

# Посмотреть статус конкретного issue
cat .specify/issues/<slug>.yaml

# Валидировать ADR корпус вручную
singularity-adr validate

# Проверить registry на консистентность
python3 scripts/check-speckit-issue-registry.py

# Посмотреть последние runs workflows
ls .specify/runs/
```
