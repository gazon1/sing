# ==============================================================================
# 🧪 KIWI — локальный стенд тест-кейсов (infra/kiwi)
# Kiwi хранит кейсы и результаты прогонов; запускает тесты Gradle.
#
# Фоновый режим: `just kiwi-start` поднимает контейнеры и сразу возвращает
# управление (docker compose up -d). БД при этом НЕ инициализирована —
# догоняет `just kiwi-wait`, который блокируется до готовности.
# ==============================================================================

[group('kiwi')]
[doc('Start Kiwi TCMS in background and return immediately (DB not yet initialised)')]
[working-directory(justfile_directory())]
start:
    #!/bin/bash
    set -euo pipefail
    ./infra/kiwi/up.sh --detach

[group('kiwi')]
[doc('Block until Kiwi is ready and initialise the DB (idempotent)')]
[working-directory(justfile_directory())]
wait:
    #!/bin/bash
    set -euo pipefail
    ./infra/kiwi/up.sh --wait-only

[group('kiwi')]
[doc('Start Kiwi and wait until ready (start + init, the usual path)')]
[working-directory(justfile_directory())]
up:
    #!/bin/bash
    set -euo pipefail
    ./infra/kiwi/up.sh

[group('kiwi')]
[doc('Stop Kiwi TCMS containers (keeps data)')]
[working-directory(justfile_directory())]
stop:
    #!/bin/bash
    set -euo pipefail
    ./infra/kiwi/down.sh

[group('kiwi')]
[doc('Stop and delete Kiwi DB + uploads (asks for confirmation)')]
[working-directory(justfile_directory())]
purge:
    #!/bin/bash
    set -euo pipefail
    ./infra/kiwi/down.sh --purge

[group('kiwi')]
[doc('Restart Kiwi TCMS in the foreground and wait until ready')]
[working-directory(justfile_directory())]
restart:
    #!/bin/bash
    set -euo pipefail
    ./infra/kiwi/down.sh
    ./infra/kiwi/up.sh

[group('kiwi')]
[doc('Show Kiwi container status and whether HTTP answers')]
[working-directory(justfile_directory())]
status:
    #!/bin/bash
    set -euo pipefail
    ./infra/kiwi/status.sh

[group('kiwi')]
[doc('Tail Kiwi logs (lines defaults to 50)')]
[working-directory(justfile_directory())]
logs lines="50":
    #!/bin/bash
    set -euo pipefail
    docker compose -f infra/kiwi/docker-compose.yml logs --tail={{lines}} -f kiwi

[group('kiwi')]
[doc('Sync test cases from repository sources into Kiwi (idempotent)')]
[working-directory(justfile_directory())]
sync-plan:
    #!/bin/bash
    set -euo pipefail
    ./infra/kiwi/sync.py --plan

[group('kiwi')]
[doc('Sync latest Gradle JUnit XML results into Kiwi as test runs')]
[working-directory(justfile_directory())]
sync-results:
    #!/bin/bash
    set -euo pipefail
    ./infra/kiwi/sync.py --results

[group('kiwi')]
[doc('Report untested gaps: cases never run, empty components, untested classes')]
[working-directory(justfile_directory())]
gaps limit="15":
    #!/bin/bash
    set -euo pipefail
    ./infra/kiwi/gaps.py --limit {{limit}}
