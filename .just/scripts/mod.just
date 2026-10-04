# ==============================================================================
# 📜 SCRIPTS — bench, refresh-decisions, android-ui-tests, os-validate
# Wrappers around existing project scripts in ./scripts/
# ==============================================================================

OPENSPEC_BIN := "/home/max/.nvm/versions/node/v24.14.0/lib/node_modules/@fission-ai/openspec/bin/openspec.js"

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

[group('scripts')]
[doc('Validate all OpenSpec change artifacts (advisory — does not block)')]
[working-directory(justfile_directory())]
os-validate:
    #!/bin/bash
    set -euo pipefail
    # Resolve the CLI instead of hardcoding one developer's nvm path, which made this
    # recipe fail on every other machine. Order: an explicit override, a global
    # install on PATH, then npx (what CI uses).
    if [ -n "${OPENSPEC_BIN:-}" ] && [ -f "${OPENSPEC_BIN}" ]; then
        node "${OPENSPEC_BIN}" validate --all --json --strict
    elif command -v openspec >/dev/null 2>&1; then
        openspec validate --all --json --strict
    else
        npx --yes @fission-ai/openspec@1.14.0 validate --all --json --strict
    fi
