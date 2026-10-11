---
title: "ADR: Миграция OpenSpec + ADR CLI pipeline → spec-kit"
date: 2026-10-11
status: accepted
tags: [tooling, workflow, spec-kit, openspec, adr]
---

# ADR: Миграция OpenSpec + ADR CLI pipeline → spec-kit

**Status:** accepted
**Date:** 2026-10-11
**Supersedes:** N/A
**Tags:** tooling, workflow, spec-kit, openspec, adr

---

## Context

Текущий pipeline построен на OpenSpec + кастомном Kotlin ADR CLI:

- **OpenSpec** `adr-workflow` schema: `proposal → specs → design → adr → tasks` (5 artifacts)
- **Kotlin ADR CLI** (`singularity-adr`): `doctor`, `context`, `validate`, `list`, `new`, `read`, `update`, `stale`
- **Python scripts** (`scripts/adr/`): corpus management, staleness checks
- **CI gate**: `gate blocking "ADR status policy" singularity-adr validate`
- **575 мигрированных ADR stubs** в `openspec/changes/YYYY-MM-DD-slug/`
- **config/docs/adr-corpus.json** — источник правды для corpus discovery

**Проблемы текущего pipeline:**

| Проблема | Описание |
|-----------|----------|
| Нет hooks | `adr validate` вызывается вручную или как пост-шаг в `apply.instruction` |
| Нет workflow automation | Каждый шаг требует ручного вызова `/speckit-*` или OpenSpec команд |
| Нет fan-out | Batch-операции (575 ADRs) делаются отдельным Python-скриптом миграции |
| Нет human checkpoints | apply-фаза не имеет механизма паузы для человеческого approval |
| Нет conditional logic | `skip_specs` работает только на уровне schema, не на уровне workflow |

**spec-kit** (github/spec-kit) предоставляет:

- **Workflows** — first-class автоматизация цепочек `/speckit-*` с conditional logic, loops, fan-out
- **Hooks** — shell-шаги в workflow (adr validate, digest update, git commit)
- **Presets** — переопределение шаблонов и команд без форка tooling
- **Extensions** — ADR-специфичные расширения
- **38 AI-агент интеграций** — generic integration для любого агента

---

## Decision

Мигрировать текущий OpenSpec + ADR CLI pipeline на spec-kit, сохраняя ADR CLI как инструмент валидации и используя spec-kit workflows для orchestration.

---

## Architecture

### High-Level Stack

```
┌─────────────────────────────────────────────────────────────┐
│                      AI Agent Chat                          │
│  /speckit-* slash commands (trigger workflow steps)         │
└────────────────┬────────────────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────────────────┐
│                  spec-kit (specify CLI)                     │
│  ┌─────────────┐  ┌──────────────┐  ┌─────────────────┐  │
│  │  Workflows  │  │   Presets    │  │   Extensions    │  │
│  │  .specify/ │  │  .specify/    │  │  .specify/exts/ │  │
│  │ workflows/  │  │  presets/     │  │                 │  │
│  └─────────────┘  └──────────────┘  └─────────────────┘  │
└────────────────┬────────────────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────────────────┐
│              Hooks (shell steps в workflows)                │
│  singularity-adr validate   │   ./refresh-digest.sh          │
│  git commit / push         │   openspec apply               │
└────────────────┬────────────────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────────────────┐
│         ADR CLI (Kotlin) + OpenSpec (unchanged)             │
│  singularity-adr [doctor|context|validate|list|...]        │
│  openspec [new|list|validate|apply|...]                    │
└─────────────────────────────────────────────────────────────┘
```

### Directory Structure After Migration

```
singularity_todo/
├── .specify/                          # spec-kit root (NEW)
│   ├── workflows/
│   │   ├── adr-create.yaml            # NEW: single ADR workflow
│   │   ├── adr-batch.yaml            # NEW: batch ADR creation
│   │   ├── adr-review.yaml           # NEW: review + converge
│   │   └── adr-ci.yaml               # NEW: CI-optimized (no human gates)
│   ├── presets/
│   │   └── singularity-adr/          # NEW: ADR-specific template overrides
│   │       ├── constitution.md
│   │       ├── specify.md
│   │       ├── plan.md
│   │       ├── tasks.md
│   │       └── converge.md
│   ├── extensions/                    # (пусто, ADR-specific hooks в workflows)
│   ├── constitutions/
│   │   └── singularity-todo.md       # NEW: project principles
│   └── .specify.yaml                 # NEW: spec-kit config
│
├── openspec/                          # (существующее, без изменений)
│   ├── config.yaml
│   ├── schemas/adr-workflow/
│   │   ├── schema.yaml
│   │   └── templates/
│   └── changes/                       # 575 мигрированных stubs
│       └── YYYY-MM-DD-*/
│
├── docs/decisions/                    # ADR corpus (существующее)
│   └── NNNN-slug.md
│
├── config/docs/adr-corpus.json        # (существующее)
│
└── scripts/
    ├── adr/                           # (существующее, для backwards compat)
    │   └── ...
    └── refresh-decisions-digest.sh    # (существующее)
```

---

## Workflow Definitions

### Workflow 1: `adr-create.yaml` — Single ADR Creation

Триггер: `specify workflow run adr-create --slug koin-4-migration --title "Koin 4.x migration"`

```yaml
# .specify/workflows/adr-create.yaml
name: adr-create
description: Create a single ADR via proposal → specs → design → adr → tasks → implement → converge

steps:
  # Phase 1: Planning artifacts
  - command: /speckit-specify
    prompt: "{{workflow.args.title}}"

  - command: /speckit-plan
    prompt: "KMP project: Android + JVM only. Koin 4.x, KSP, new DI module pattern."

  # Human checkpoint: review before implementation
  - checkpoint: "Review ADR before implementation"
    description: |
      ## Review Checklist
      - [ ] proposal.md: Why is clear, scope is bounded
      - [ ] spec.md: Requirements are testable, scenarios present
      - [ ] design.md: Decisions have rationale, risks identified
      - [ ] adr.md: All architectural decisions captured
    approve_label: "✅ Proceed to implementation"
    reject_label: "❌ Go back and revise"

  # Phase 2: Implementation
  - if: checkpoint.approved
    then:
      - command: /speckit-tasks

      # Hook: pre-implement validation
      - hook: singularity-adr validate --strict
        description: "Validate ADR corpus before starting implementation"

      - command: /speckit-implement
        # Runs until all tasks done or hits next checkpoint

      # Hook: post-implement validation
      - hook: singularity-adr validate
        description: "Validate ADR corpus after implementation"

      # Hook: update DIGEST.md
      - shell: ./scripts/refresh-decisions-digest.sh
        description: "Refresh DIGEST.md after ADR changes"

      - command: /speckit-converge

      # Hook: git commit
      - shell: |
          git add docs/decisions/ openspec/changes/
          git commit -m "docs: {{workflow.args.slug}} ADR

          Created via spec-kit workflow adr-create"
        description: "Commit ADR artifacts"

  - if: checkpoint.rejected
    then:
      - abort: "Revision required. Please address feedback and re-run."
```

### Workflow 2: `adr-batch.yaml` — Batch ADR Creation

Триггер: `specify workflow run adr-batch --csv adr-batch.csv`

```yaml
# .specify/workflows/adr-batch.yaml
name: adr-batch
description: Create multiple ADRs in sequence with validation after each

steps:
  # Read CSV of slugs/titles
  - shell: |
      while IFS=, read -r slug title; do
        echo "$slug|$title"
      done < "{{workflow.args.csv}}"
    export: ADR_BATCH_ITEMS

  # Fan-out: iterate over each ADR
  - for_each: "{{ADR_BATCH_ITEMS}}"
    separator: "|"
    do:
      - name: "Create ADR: {{item.slug}}"
        command: /speckit-specify
          prompt: "{{item.title}}"
          slug: "{{item.slug}}"

      - command: /speckit-plan
      - command: /speckit-tasks

      # Hook after each ADR
      - hook: singularity-adr validate
        description: "Validate after {{item.slug}}"

      - hook: singularity-adr doctor --json > /tmp/doctor-{{item.slug}}.json
        description: "Capture health check for {{item.slug}}"

  # Final: update digest + converge all
  - shell: ./scripts/refresh-decisions-digest.sh
  - shell: |
      echo "Batch complete: {{ADR_BATCH_ITEMS.length}} ADRs"
```

### Workflow 3: `adr-ci.yaml` — CI-Optimized (No Human Gates)

Триггер: GitHub Actions на push в `docs/decisions/` или `openspec/changes/`

```yaml
# .specify/workflows/adr-ci.yaml
name: adr-ci
description: Validate ADR corpus in CI — no human checkpoints, exit codes only

steps:
  # Hook: adr validate (non-zero = fail)
  - hook: singularity-adr validate
    description: "ADR corpus validation"

  # Hook: adr stale check
  - hook: singularity-adr stale --days 30
    description: "Check for stale ADRs"

  # Hook: openspec validate
  - hook: openspec validate --changes
    description: "Validate OpenSpec changes"

  # Hook: update DIGEST if needed
  - shell: |
      if git diff --quiet docs/decisions/; then
        echo "No ADR changes, skipping DIGEST refresh"
      else
        ./scripts/refresh-decisions-digest.sh
        git add docs/decisions/DIGEST.md
      fi
    description: "Conditionally refresh DIGEST.md"

  # Exit with appropriate code
  - shell: |
      echo "CI gate complete: all checks passed"
      exit 0
```

---

## Hooks Detail

Hooks — это shell-шаги внутри workflow. Определяются inline или выносятся в отдельные файлы.

| Hook | Command | When | Exit Code Handling |
|------|---------|------|-------------------|
| `adr-validate` | `singularity-adr validate` | После каждого создания/изменения ADR | Non-zero → abort workflow |
| `adr-doctor` | `singularity-adr doctor --json` | После `specify init`, в CI pre-flight | Non-zero → warn + continue |
| `adr-stale` | `singularity-adr stale --days 30` | В CI workflow | Non-zero → fail CI gate |
| `digest-refresh` | `./scripts/refresh-decisions-digest.sh` | После batch или apply | Non-zero → warn + continue |
| `adr-context` | `singularity-adr context` | При старте workflow | Используется для logging |

---

## Presets

Presets переопределяют шаблоны spec-kit для Singularity Todo conventions.

```yaml
# .specify/presets/singularity-adr/singularity-adr.yaml
name: singularity-adr
description: Singularity Todo ADR workflow preset — KMP, Koin 4.x, MVI pattern

overrides:
  templates:
    constitution.md: |
      # Constitution: Singularity Todo

      ## Guiding Principles

      1. **KMP First**: All architectural decisions must consider Android + JVM.
         iOS is out of scope.
      2. **ADR Before Code**: Every architectural decision > 1 week effort requires an ADR.
      3. **Validate Early**: Run `singularity-adr validate` after every ADR change.
      4. **Immutable ADRs**: Never edit accepted ADRs. Supersede with new ADR.
      ...

    specify.md: |
      # Spec: {{spec.title}}

      ## Why
      {{spec.why}}

      ## What Changes
      {{spec.changes}}

      ## KMP Constraints
      - Android + JVM only (no iOS)
      - Compose Multiplatform for UI
      - Koin 4.x for DI
      ...

    plan.md: |
      # Plan: {{plan.title}}

      ## Context
      KMP project: Android + JVM Desktop only.

      ## Technical Decisions
      {{plan.decisions}}

      ## Module Structure
      - shared/src/commonMain/kotlin/com/singularity/todo/
      - androidApp/
      - desktopApp/
      ...

    tasks.md: |
      # Tasks: {{tasks.title}}

      ## Implementation Order

      1. **Setup** — build.gradle.kts, KSP, DI modules
      2. **Shared Layer** — domain, repository interfaces
      3. **Platform Layer** — androidMain, jvmMain implementations
      4. **UI Layer** — Compose screens, ViewModels
      5. **Testing** — unit tests, integration tests

      ## Verification
      Each task group MUST land its own tests. No late-stage test aggregation.
      ...

    converge.md: |
      # Converge: {{converge.title}}

      ## Verification Checklist

      - [ ] All tasks marked done in tasks.md
      - [ ] singularity-adr validate passes
      - [ ] DIGEST.md updated
      - [ ] New ADRs have sequence numbers
      - [ ] supersedes links are correct
      - [ ] No orphaned references in adr-corpus.json
      ...
```

---

## Extension: ADR-Specific Commands

Если preset недостаточно, можно написать extension-команду:

```python
# .specify/extensions/adr-commands/speckit_adr_validate.py
"""speckit-adr-validate — run adr validate as a spec-kit command."""

import subprocess
import sys

def run(args: list[str], cwd: str) -> int:
    result = subprocess.run(
        ["singularity-adr", "validate"] + args,
        cwd=cwd,
        capture_output=True,
        text=True
    )
    print(result.stdout)
    if result.stderr:
        print(result.stderr, file=sys.stderr)
    return result.returncode

if __name__ == "__main__":
    sys.exit(run(sys.argv[1:], cwd="."))
```

---

## Constitution

```markdown
# Constitution: Singularity Todo

## Guiding Principles

1. **KMP Architecture**: Android + JVM Desktop only. Compose Multiplatform for UI,
   Koin 4.x for DI, Room for persistence, Nav3 for navigation.
2. **ADR Lifecycle**: Every architectural decision > 1 week effort requires an ADR.
   ADRs live in `docs/decisions/`, validated via `singularity-adr validate`.
3. **Immutable Historical Record**: Accepted ADRs are never edited. Changes create
   new ADRs with `supersedes` links.
4. **CI Gates**: All ADR changes must pass `singularity-adr validate` and
   `openspec validate --changes` before merge.
5. **Digest Maintenance**: `docs/decisions/DIGEST.md` reflects current in-force
   decisions. Updated automatically after any ADR change.
6. **MVI ViewModels**: All ViewModels follow canonical pattern: MutableStateFlow
   + scope.launch + collect {}. No stateIn, no viewModelScope.
7. **Test Fakes**: Use fakes from `test/fakes/` instead of mocks. Virtual time
   via advanceUntilIdle().

## Decision Criteria

An ADR is required when:
- Architectural pattern change (DI, navigation, data layer)
- New cross-cutting concern
- External dependency upgrade with behavioral change
- Deprecation of existing capability

An ADR is NOT required for:
- Bug fixes with no behavior change
- Test-only changes
- Dependency version updates
- Detekt/ktlint auto-fixes
- Doc-only updates
- Purely additive, low-risk changes following existing patterns
```

---

## CI/CD Integration

### GitHub Actions

```yaml
# .github/workflows/adr-spec-kit.yml
name: ADR + OpenSpec Gate

on:
  push:
    paths:
      - 'docs/decisions/**'
      - 'openspec/changes/**'
      - 'openspec/schemas/**'
  pull_request:
    paths:
      - 'docs/decisions/**'
      - 'openspec/changes/**'
      - 'openspec/schemas/**'

jobs:
  gate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Install spec-kit
        run: uv tool install specify-cli

      - name: Install singularity-adr
        run: |
          ./gradlew :adr-cli:installDist
          export PATH="$PATH:$PWD/adr-cli/build/install/singularity-adr/bin"

      - name: ADR validation
        run: singularity-adr validate

      - name: OpenSpec validation
        run: openspec validate --changes

      - name: spec-kit workflow (CI mode)
        run: |
          specify workflow run adr-ci --ci || {
            echo "spec-kit workflow failed"
            exit 1
          }

  spec-create:
    if: github.event_name == 'pull_request'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v4
        with:
          python-version: '3.11'

      - name: Install spec-kit
        run: uv tool install specify-cli

      - name: Run adr-create workflow
        run: |
          specify workflow run adr-create \
            --slug "${{ github.event.pull_request.title }}" \
            --title "${{ github.event.pull_request.body }}"
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

---

## Migration Path

### Phase 0: Assessment (Day 0)

- [x] Verify current state: `openspec validate`, `singularity-adr validate`
- [x] Count existing ADRs: `singularity-adr list | wc -l` (577 + 14 archived + 129 backlog)
- [x] Check OpenSpec changes: `openspec list --changes | wc -l` (575 stubs)
- [x] Review `config/docs/adr-corpus.json` for dangling references (~200 pre-existing)

### Phase 1: Install spec-kit (Day 1) ✅ DONE

```bash
# Install spec-kit CLI
uv tool install specify-cli

# Initialize in project (generic integration = any AI agent)
cd /path/to/singularity_todo
specify init . --integration generic --integration-options="--commands-dir .agent/commands/"
```

**Installed files:**
- `.specify/` — spec-kit root (workflows, templates, constitutions, extensions)
- `.agent/commands/` — `/speckit-*` slash commands for AI agent
- `.github/workflows/adr-gate.yml` — GitHub Actions CI pipeline

### Phase 2: Create Constitution + Preset (Day 1) ✅ DONE

- [x] Create `.specify/constitutions/singularity-todo.md`
- [x] Create `.specify/workflows/adr-create.yaml`
- [x] Create `.specify/workflows/adr-batch.yaml`
- [x] Create `.specify/workflows/adr-ci.yaml`
- [x] Create `.specify/extensions.yml` (ADR validation hooks)
- [x] Test: `specify workflow run adr-ci --input ci_mode=true` ✓ PASSED

### Phase 3: Wire into CI (Day 2) ✅ DONE

- [x] Create `.github/workflows/adr-gate.yml`
- [x] `adr-validate` step already in GitHub Actions via spec-kit workflow
- [x] Keep old Python ADR gate in `scripts/ci/static-gates.sh` as backup (local dev)
- [x] GitHub Actions triggers on push/PR to `docs/decisions/` or `openspec/changes/`

### Phase 4: Migrate Existing Stubs (Day 3) — DECIDED: NOT NEEDED

575 stubs in `openspec/changes/` stay in OpenSpec format. spec-kit workflows use `openspec` CLI as hooks.
No migration needed — old stubs and new workflows coexist.

```bash
# No migration needed — stubs are snapshots
# New ADRs created via: specify workflow run adr-create --input slug=...,title=...
```

### Phase 5: Deprecate Old Tools (Day 7) — ONGOING

- [x] Keep `singularity-adr` CLI (used as hooks in workflows)
- [x] Keep `scripts/adr/` Python tooling (backwards compat for external consumers)
- [x] Keep `openspec` CLI alongside `specify` (both used: openspec for changes, spec-kit for automation)
- [x] Update `AGENTS.md` with spec-kit commands section

---

## Strategy Decision: OpenSpec + spec-kit Coexistence

**Decision: OpenSpec and spec-kit coexist, each with distinct role.**

| Layer | Tool | Role |
|-------|------|------|
| **Schema/Planning** | OpenSpec | `adr-workflow` schema, 5 artifacts (proposal→specs→design→adr→tasks) |
| **Orchestration** | spec-kit | Workflows, hooks, AI agent slash commands |
| **Validation** | `singularity-adr` | ADR corpus validation, stale checks, doctor |
| **Execution** | Both OpenSpec + spec-kit | OpenSpec commands called as spec-kit workflow steps |

**Why not replace OpenSpec with spec-kit:**
- OpenSpec schema (`adr-workflow`) is the source of truth for 575 existing stubs
- OpenSpec has deep ADR corpus knowledge (status vocabulary, supersedes graph)
- Migrating stubs = rewriting 575 files unnecessarily
- spec-kit workflows call `openspec validate` and `adr validate` as shell hooks

**Why not replace spec-kit with OpenSpec:**
- OpenSpec has no workflow automation (no hooks, no human checkpoints, no fan-out)
- spec-kit's AI agent integration (`/speckit-*` commands) is the primary interface
- 38 native integrations vs OpenSpec's generic approach

---

## Rationale

### Why spec-kit over staying with OpenSpec

| Factor | OpenSpec | spec-kit |
|--------|----------|----------|
| Hooks | Manual (apply.instruction) | First-class (workflow steps) |
| Automation | Scripts + Python | Native workflows + YAML |
| Human checkpoints | Not supported | checkpoint directive |
| Fan-out | External script | for_each in workflow |
| AI integration | Generic | 38 native integrations |
| Community | Small | 140k stars, 157 extensions |
| Extension model | Schema customization | Extensions + Presets + Bundles |

### Why Keep ADR CLI

`singularity-adr` — это специализированный инструмент для ADR lifecycle, который spec-kit не заменяет напрямую. Он используется как **hook** внутри spec-kit workflows. Это сохраняет инвестиции в Kotlin CLI и не требует переписывания.

### Why Keep OpenSpec

OpenSpec — это источник truth для `adr-workflow` schema и 575 existing stubs. spec-kit **дополняет** OpenSpec, не заменяет его. ADR-specific workflow в spec-kit вызывает `openspec validate` и `adr validate` как hooks.

---

## Consequences

### Positive

1. **Full automation** — batch ADR creation без ручных slash commands
2. **Human checkpoints** — approve/reject на critical phases
3. **Hooks as first-class** — adr validate, digest refresh, git commit автоматически
4. **Fan-out** — 575 ADRs обрабатываются одним workflow
5. **CI-native** — `adr-ci.yaml` работает в GitHub Actions с exit codes
6. **Future-proof** — 157 community extensions, active development (140k stars)

### Negative

1. **New tooling** — spec-kit CLI требует установки и настройки
2. **Migration effort** — нужно перенести 575 stubs, переписать CI
3. **Learning curve** — workflows YAML syntax, presets, extensions
4. **Dual tooling** — OpenSpec + spec-kit + ADR CLI (3 инструмента)

### Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| spec-kit upstream changes break workflow | Low | Pin version in CI |
| 575 stubs несовместимы с spec-kit format | Medium | Keep existing stubs as-is, only new ones use spec-kit |
| AI agent не понимает new workflow | Low | Provide /speckit-* reference card in AGENTS.md |

---

## Alternatives Considered

### Alternative 1: Stay with OpenSpec + enhance ADR CLI

Оставить текущий pipeline, добавить в ADR CLI команды типа `adr apply --auto-validate`. Не решает проблему hooks и workflow automation.

### Alternative 2: Full rewrite ADR CLI in TypeScript

Переписать ADR CLI как spec-kit extension. Отклонено пользователем.

### Alternative 3: Build custom orchestration layer

Написать свой Python-оркестратор для OpenSpec + ADR CLI. Повторное изобретение колеса.

---

## Links

- [spec-kit GitHub](https://github.com/github/spec-kit)
- [spec-kit Documentation](https://github.github.com/spec-kit/)
- [spec-kit Workflows Reference](https://github.github.com/spec-kit/reference/overview.html#workflows)
- [spec-kit Presets](https://github.github.com/spec-kit/guides/customization.html#presets)
- [OpenSpec CLI](https://github.com/openspec-project/openspec)
- [Singularity Todo ADR CLI](./adr-cli/)
- [ADR corpus config](../config/docs/adr-corpus.json)
