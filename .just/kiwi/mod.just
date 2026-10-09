# ==============================================================================
# 🧪 KIWI — локальный стенд тест-кейсов (infra/kiwi)
# Kiwi хранит кейсы и результаты прогонов; запускает тесты Gradle.
#
# 🛠 DEACTIVATED 2026-10-09: Kiwi is not currently in use. The sabotage control
# for `kiwi-gaps` has been removed from check-gate-wiring.py. To re-enable:
#   1. Restore the kiwi-gaps ScriptGate block from git history
#   2. Uncomment the recipes below
#   3. Run `just kiwi-up` to bring up the stand
# ==============================================================================

# [group('kiwi')]
# [doc('Start Kiwi TCMS in background and return immediately (DB not yet initialised)')]
# [working-directory(justfile_directory())]
# start:
#     #!/bin/bash
#     set -euo pipefail
#     ./infra/kiwi/up.sh --detach
#
# [group('kiwi')]
# [doc('Block until Kiwi is ready and initialise the DB (idempotent)')]
# [working-directory(justfile_directory())]
# wait:
#     #!/bin/bash
#     set -euo pipefail
#     ./infra/kiwi/up.sh --wait-only
#
# [group('kiwi')]
# [doc('Start Kiwi and wait until ready (start + init, the usual path)')]
# [working-directory(justfile_directory())]
# up:
#     #!/bin/bash
#     set -euo pipefail
#     ./infra/kiwi/up.sh
#
# [group('kiwi')]
# [doc('Stop Kiwi TCMS containers (keeps data)')]
# [working-directory(justfile_directory())]
# stop:
#     #!/bin/bash
#     set -euo pipefail
#     ./infra/kiwi/down.sh
#
# [group('kiwi')]
# [doc('Stop and delete Kiwi DB + uploads (asks for confirmation)')]
# [working-directory(justfile_directory())]
# purge:
#     #!/bin/bash
#     set -euo pipefail
#     ./infra/kiwi/down.sh --purge
#
# [group('kiwi')]
# [doc('Restart Kiwi TCMS in the foreground and wait until ready')]
# [working-directory(justfile_directory())]
# restart:
#     #!/bin/bash
#     set -euo pipefail
#     ./infra/kiwi/down.sh
#     ./infra/kiwi/up.sh
#
# [group('kiwi')]
# [doc('Show Kiwi container status and whether HTTP answers')]
# [working-directory(justfile_directory())]
# status:
#     #!/bin/bash
#     set -euo pipefail
#     ./infra/kiwi/status.sh
#
# [group('kiwi')]
# [doc('Tail Kiwi logs (lines defaults to 50)')]
# [working-directory(justfile_directory())]
# logs lines="50":
#     #!/bin/bash
#     set -euo pipefail
#     docker compose -f infra/kiwi/docker-compose.yml logs --tail={{lines}} -f kiwi
#
# [group('kiwi')]
# [doc('Sync test cases from repository sources into Kiwi (idempotent)')]
# [working-directory(justfile_directory())]
# sync-plan:
#     #!/bin/bash
#     set -euo pipefail
#     ./infra/kiwi/sync.py --plan
#
# [group('kiwi')]
# [doc('Sync latest Gradle JUnit XML results into Kiwi as test runs')]
# [working-directory(justfile_directory())]
# sync-results:
#     #!/bin/bash
#     set -euo pipefail
#     ./infra/kiwi/sync.py --results
#
# [group('kiwi')]
# [doc('Report untested gaps: cases never run, empty components, untested classes')]
# [working-directory(justfile_directory())]
# gaps limit="15":
#     #!/bin/bash
#     set -euo pipefail
#     ./infra/kiwi/gaps.py --limit {{limit}}
#
# [group('kiwi')]
# [doc('Delete test runs outside the retention window (dry run — pass --apply to delete)')]
# [working-directory(justfile_directory())]
# prune keep="20" apply="":
#     #!/bin/bash
#     set -euo pipefail
#     if [ "{{apply}}" = "apply" ]; then
#         ./infra/kiwi/prune.py --keep {{keep}} --apply
#     else
#         ./infra/kiwi/prune.py --keep {{keep}}
#     fi
#
# [group('kiwi')]
# [doc('Fail when never-run test cases rise above the committed floor')]
# [working-directory(justfile_directory())]
# floor:
#     #!/bin/bash
#     set -euo pipefail
#     ./scripts/check-kiwi-gaps.py --if-present

# ==============================================================================
# 🔗 TRACEABILITY — сценарии пользовательского поведения (infra/kiwi/traceability)
#
# Ответ на вопрос «какие сценарии мы проверяем, на чём и с каким результатом» —
# производный артефакт, а не таблица, которую ведут руками. Git хранит спеки и
# связь (@DisplayName / scenario: теги), CI — факт прохождения, Kiwi — проекция.
# ==============================================================================

[group('traceability')]
[doc('Validate scenario specs and their links to automation (no stand, no network)')]
[working-directory(justfile_directory())]
trace-validate:
    #!/bin/bash
    set -euo pipefail
    PYTHONPATH=infra/kiwi python3 -m traceability validate

[group('traceability')]
[doc('Regenerate the committed coverage matrix from specs + links')]
[working-directory(justfile_directory())]
trace-coverage:
    #!/bin/bash
    set -euo pipefail
    PYTHONPATH=infra/kiwi python3 -m traceability coverage

[group('traceability')]
[doc('Fail if the committed coverage matrix differs from the regenerated one (CI)')]
[working-directory(justfile_directory())]
trace-coverage-check:
    #!/bin/bash
    set -euo pipefail
    PYTHONPATH=infra/kiwi python3 -m traceability coverage --check

[group('traceability')]
[doc('Generate a reachability probe for a scenario: `just trace-carrier TASK-CHECK-01 [--dry-run]`')]
[working-directory(justfile_directory())]
trace-carrier scenario:
    #!/bin/bash
    set -euo pipefail
    PYTHONPATH=infra/kiwi python3 -m traceability carrier {{scenario}}

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
    # pass it: it runs -Ptest.tags=fast,slow, and there the rule is the point.
    if [ -n "{{maestro}}" ]; then
        PYTHONPATH=infra/kiwi python3 -m traceability results --maestro --partial
    else
        PYTHONPATH=infra/kiwi python3 -m traceability results --partial
    fi

[group('traceability')]
[doc('Project scenario specs into Kiwi (idempotent on the scenario id)')]
[working-directory(justfile_directory())]
kiwi-seed:
    #!/bin/bash
    set -euo pipefail
    PYTHONPATH=infra/kiwi python3 -m traceability seed

[group('traceability')]
[doc('Report what seeding would write, without writing it')]
[working-directory(justfile_directory())]
kiwi-seed-dry-run:
    #!/bin/bash
    set -euo pipefail
    PYTHONPATH=infra/kiwi python3 -m traceability seed --dry-run

[group('traceability')]
[doc('Fail when Kiwi has drifted from the scenario specs')]
[working-directory(justfile_directory())]
kiwi-seed-check:
    #!/bin/bash
    set -euo pipefail
    PYTHONPATH=infra/kiwi python3 -m traceability seed --check

[group('traceability')]
[doc('Publish results.json into Kiwi as execution history (never gates the build)')]
[working-directory(justfile_directory())]
kiwi-publish results="build/traceability/results.json":
    #!/bin/bash
    set -euo pipefail
    PYTHONPATH=infra/kiwi python3 -m traceability publish --results {{results}}
