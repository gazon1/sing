# CI: джобы, слои проверок, реестр гейтов

> **Reference, не процедура.** Этот документ отвечает на один вопрос: *что CI делает
> в этом репозитории и чем отличается от локального хука*. Как проверять правку
> локально — в `docs/architecture/verification.md`; как писать тесты — в
> `docs/architecture/tests.md`.

## Джобы основного workflow

`.github/workflows/ci.yml` — один workflow, джобы:

| Джоб | Имя в GitHub | Что делает |
|---|---|---|
| `changes` | changes | path-фильтр: какие джобы вообще запускать |
| `static` | Static gates | все скриптовые гейты (без JVM) + detekt auto-fix в отдельную ветку |
| `meta` | Gate tooling | юнит-тесты самих гейтов и probe «гейты живы» |
| `tests` | JVM tests, detekt, coverage | JVM-прогон, detekt, Kover-флоры, ADR-модули, flake-pass |
| `android` | Android (free / pro) | сборка и компиляция android-конфигураций |
| `ci-gate` | CI gate | агрегатор: required check, который видит branch protection |
| `adr-gate` | — | ADR и spec-kit проверки |
| `spec-create` | — | служебный джоб spec-kit |
| `nightly-alert` | nightly-alert | оповещение о ночных прогонах |

Отдельные workflow'ы: `e2e.yml` (Maestro-флоу), `android-device-tests.yml`
(инструментированные тесты на устройстве), `flake.yml` (rate флейков по расписанию),
`release.yml`, `codeql.yml`, `scorecard.yml`, `pr-cache-cleanup.yml`.

События: push в `main`, pull_request в `main` (в том числе по лейблу
`run-full-tests`), ручной запуск и ночной cron в 01:00 UTC — раньше e2e (02:00) и
android-device-tests (03:00).

## Слои проверок

Слои отличаются тем, что они могут увидеть и сколько стоят:

| Слой | Где | Стоимость | Что видит |
|---|---|---|---|
| Скриптовые гейты | `static` | ≈1 мин, без JVM | соглашения репозитория, ссылки в документах, реестры |
| Мета-гейты | `meta` | ≈4 мин | что сами гейты не развалились (`unittest discover -s scripts/tests`, liveness probe) |
| JVM-уровень | `tests` | ≈31 мин (измерированный прогон) | Room, SQLite, корутины, Konsist, покрытие |
| Android | `android` | ≈3–4 мин | что android-конфигурация компилируется |
| Устройство | `e2e.yml`, `android-device-tests.yml` | ≈10 мин + эмулятор | реальный Android-рантайм |
| CI gate | `ci-gate` | секунды | «все обязательные джобы зелёные» |

Подробности по уровням тестов — `docs/testing/what-each-tier-proves.md`; что чем
покрыто — `docs/testing/coverage-matrix.md`; цена запусков —
`docs/testing/gate-costs.md`.

## Реестр гейтов — один источник

Единственный список гейтов — **реестр** (`scripts/src/sgate/commands/_registry.py` +
`scripts/src/sgate/gate_manifest.py`); `scripts/ci/static-gates.sh` и
`docs/scripts/gate-registry.md` **генерируются** из него, руками их не правь:

```bash
PYTHONPATH=scripts/src python3 -m sgate render-static-gates --write
python3 -m sgate render-registry
```

Джоб `static` в `ci.yml` и `check.sh` берут тот же реестр: гейт не может жить на одной
поверхности и отсутствовать на другой.

Новый гейт = одна запись в реестре + строка `gate blocking|advisory <name> <cmd>`;
`|| true` на строке `gate` запрещён. Branch protection требует **один** check — `CI gate`.
Состав workflow'ев, частей A–G мета-гейта и рецепты — скилл
`singularity-todo-quality-tools`.

## Чем CI отличается от локального хука

- **pre-push hook** (`scripts/…` → `.githooks/pre-push`) проверяет **пушимое дерево**:
  структурные гейты + `detekt --auto-correct` + правила дерева (указатели `AGENTS.md`,
  реестр ченджа, вердикт ревью). Это быстрый фильтр перед отправкой; полный сьют
  гейтов в хук не входит намеренно.
- **CI** прогоняет всё, включая то, что не имеет смысла на каждый пуш: полную
  популяцию тестов, Kover-флоры, ADR-модули, flake-pass, ночные и device-прогоны.
- Хук даёт вердикт за минуты и ловит механику; CI даёт вердикт за десятки минут и
  ловит регрессии, которые локально просто не запускались.

## Гейты «меры», а не «булевы»

Все блокирующие; спека: `openspec/specs/test-execution-integrity/spec.md`, ADR
`2026-10-04-measurement-integrity`.

- `check-test-runs.py --require <set>` — прогнал меньше классов/тестов, чем floor, **или**
  пропустил хоть один.
- `:koverVerify` — INSTRUCTION/BRANCH/LINE покрытие `com.singularity.todo.*` ниже floor
  27/20/27.
- `check-flaky-tests.py --current DIR --previous DIR` — флейки между прогонами.
- `unittest discover -s scripts/tests` — регрессия в самих гейтах (~3 мин на 1750
  тестов).

Floor = **минимальный** легитимный прогон: падение — расследовать, не регенерировать.

## Инструменты гейтов ставятся, а не подкладываются в `PYTHONPATH`

На машине нескольких клонов `sgate` на PATH — editable-установка *чужого* клона, и
гейт проверит чужой код, назвав его своим. Ставится один раз на клон:

```bash
bash scripts/ci/setup-local-tools.sh          # uv-venv + editable-установка scripts/src
export PATH="$HOME/.venvs/singularity-todo/bin:$PATH"
sgate whereami                                 # путь должен совпасть с этим клоном
```

`bash scripts/ci/preflight-gate-tools.sh` проверяет это и говорит, что чинить.
Ручной `PYTHONPATH` в скриптах — не путь: юнит-тесты гейтов запускают их с минимальным
окружением (`env={'PATH': ...}`), и пакет, видимый только через `PYTHONPATH`, для них
невидим. Наш `sgate run-all` сам запускает гейты этого клона (`run_all.py` →
`_repo_env()`), поэтому чужая установка на PATH результат гейтов не портит — но и руками
`sgate` звать тогда нельзя.

## Ждёшь CI — одной командой

```bash
gh pr checks <pr> --watch --interval 60   # ждёт до конца, печатает вердикт
```

Десять `sleep 30 && gh pr checks` — это десять раундов контекста впустую, и ровно то,
что запрещает правило про долгие запуски в `docs/architecture/verification.md`.
`--watch` ждёт сам и отдаёт финальный вердикт одной строкой.

## Перед PR — ревью отдельным агентом

`code-review-pr-workflow`: он получает диф, а не код, и у него нет давления контекста
реализации — поэтому он ловит то, чего не ловит гейт (контракт состояния, алиас, запись
в реестр issues). Гейт ловит механику; ревью ловит смысл.

**Вердикт ревью — файл, а не воспоминание.** У каждого ченджа, который не в базлайне,
есть `.specify/reviews/<slug>.md`: чендж, вердикт (`pass`/`fail`), дата, PR и что нашёл
ревью. Имя файла — **slug без date-префикса**: у каталога `2026-10-22-the-read-surface`
вердикт лежит в файле с именем `the-read-surface`. Гейт `review verdicts cover every
change` требует его; ченджи, появившиеся до правила, — в
`config/docs/review-verdicts-baseline.txt` (список только сокращается: `--update-baseline`
подчищает, но не добавляет; добавить запись — ручная правка, видимая в диффе). Каркас,
который только что создал spec-kit, от правила свободен, пока его не написали.

## Куда дальше

- Локальная проверка: `docs/architecture/verification.md`.
- Тесты: `docs/architecture/tests.md`.
- Рецепты и качество: скилл `singularity-todo-quality-tools`, `docs/testing/gate-costs.md`.
