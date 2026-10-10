---
created: 2026-10-10
status: accepted
deciders: mavis
impact: medium
review_after: 2026-11-01
---

# ADR: OpenSpec length-warning не является ошибкой в 1.14.0

## Context

`openspec validate --all --json --strict` используется как blocking gate в `ci.yml`.
Между версиями `1.14.0` и `1.14.1` поведение `--strict` изменилось:

| Версия | `--strict` при length-warning | Exit code |
|--------|-------------------------------|-----------|
| 1.14.0 | WARNING (exit 0) | 0 |
| 1.14.1 | превращает в FAIL (exit 1) | 1 |

37 из 53 items содержат только `Requirement text is very long (>500 characters)`.
При `--strict` в 1.14.1 эти WARNING становятся FAIL, хотя ни один из них не
является настоящей ошибкой валидации.

## Decision

**Пиним `OPENSPEC_VERSION` на `1.14.0`** в `.github/workflows/ci.yml`.

Параллельно открываем issue (TBD) на исправление 74 длинных требований и
переход на 1.14.1 с `--strict` когда контент будет соответствовать.

При следующем обновлении `OPENSPEC_VERSION`:

1. Проверить локально: `npx --yes "@fission-ai/openspec@<new-version>" validate --all --json --strict`
2. Если exit 1 при length-warning — править требования до перехода
3. Документировать результат в новом ADR или обновить этот

## Consequences

- main снова зелёный
- length-правило остаётся warning, а не error — соблюдение требований
  откладывается до отдельной работы
- AGENTS.md точнен: `exit 0 при WARNING'ax` верно для 1.14.0
