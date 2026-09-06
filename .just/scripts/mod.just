# ==============================================================================
# 📜 SCRIPTS — bench, refresh-decisions, android-ui-tests
# Wrappers around existing project scripts in ./scripts/
# ==============================================================================

[group('scripts')]
[doc('Measure RSS/VmPeak of running desktop app (auto-detects PID)')]
[working-directory(justfile_directory())]
bench pid="":
    #!/bin/bash
    set -euo pipefail
    if [ -z "{{pid}}" ]; then
        ./scripts/ram-bench.sh
    else
        ./scripts/ram-bench.sh {{pid}}
    fi

[group('scripts')]
[doc('Rebuild docs/decisions/DIGEST.md from dated entries')]
[working-directory(justfile_directory())]
refresh-decisions:
    #!/bin/bash
    set -euo pipefail
    ./scripts/refresh-decisions-digest.sh

[group('scripts')]
[doc('Run Android UI Automator tests (auto-detects adb device)')]
[working-directory(justfile_directory())]
android-ui-tests serial="":
    #!/bin/bash
    set -euo pipefail
    if [ -z "{{serial}}" ]; then
        ./scripts/run-android-ui-tests.sh
    else
        ./scripts/run-android-ui-tests.sh {{serial}}
    fi
