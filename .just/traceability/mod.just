# ==============================================================================
# 🔗 TRACEABILITY — сценарии пользовательского поведения
#    (scripts/src/traceability, корпус спек — scripts/src/traceability/scenarios)
#
# Ответ на вопрос «какие сценарии мы проверяем, на чём и с каким результатом» —
# производный артефакт, а не таблица, которую ведут руками. Git хранит спеки и
# связь (@DisplayName / scenario: теги), CI — факт прохождения.
#
# Пакет устанавливается из scripts/src/ (`pip install -e scripts/src/`), поэтому
# ни PYTHONPATH, ни поднятый стенд здесь не нужны: каждый рецепт работает с
# файлами репозитория и с XML прогонов.
#
# Kiwi TCMS удалён 2026-10-12 — вместе с проекцией сценариев в кейсы. Рецепты
# kiwi-seed / kiwi-publish исчезли вместе с целью; покрытие никогда не жило в
# Kiwi, оно в docs/testing/coverage-matrix.md.
# ==============================================================================

[group('traceability')]
[doc('Validate scenario specs and their links to automation (no stand, no network)')]
[working-directory(justfile_directory())]
trace-validate:
    #!/bin/bash
    set -euo pipefail
    python3 -m traceability validate

[group('traceability')]
[doc('Regenerate the committed coverage index + scenario details from specs + links')]
[working-directory(justfile_directory())]
trace-coverage:
    #!/bin/bash
    set -euo pipefail
    python3 -m traceability coverage

[group('traceability')]
[doc('Fail if the committed coverage documents differ from the regenerated ones (CI)')]
[working-directory(justfile_directory())]
trace-coverage-check:
    #!/bin/bash
    set -euo pipefail
    python3 -m traceability coverage --check

[group('traceability')]
[doc('Generate a reachability probe for a scenario: `just trace-carrier TASK-CHECK-01 [--dry-run]`')]
[working-directory(justfile_directory())]
trace-carrier scenario:
    #!/bin/bash
    set -euo pipefail
    python3 -m traceability carrier {{scenario}}

[group('traceability')]
[doc('Normalise JUnit/Maestro XML into build/traceability/results.json + a result matrix')]
[working-directory(justfile_directory())]
trace-results maestro="":
    #!/bin/bash
    set -euo pipefail
    # --partial because the documented local cycle runs @Tag("fast") only
    # (./gradlew :desktopApp:test with no -Ptest.tags excludes "slow"), and
    # every scenario carrier is slow. Without the flag the per-scenario rule
    # reads the absent slow tests as a defect on every local run. CI does not
    # pass it on the nightly: that run measures -Ptest.tags=fast,slow, and there
    # the rule is the point. CI's pull-request run measures the fast population
    # only, so it passes --partial for the same reason this recipe does.
    if [ -n "{{maestro}}" ]; then
        python3 -m traceability results --maestro --partial
    else
        python3 -m traceability results --partial
    fi

[group('traceability')]
[doc('Fail when a speckit spec and the scenario YAML it declares disagree')]
[working-directory(justfile_directory())]
trace-scenarios:
    #!/bin/bash
    set -euo pipefail
    python3 -m traceability scenarios --check