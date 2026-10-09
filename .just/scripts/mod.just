# ==============================================================================
# 📜 SCRIPTS — bench, refresh-decisions, android-ui-tests, os-validate
# Wrappers around existing project scripts in ./scripts/
# ==============================================================================
#
# There is deliberately no OpenSpec path constant here. An earlier version had
# `OPENSPEC_BIN := "/home/<user>/.nvm/..."`, which was wrong twice over: it named
# one developer's machine, and the `os-validate` recipe reads a *shell* variable
# (`${OPENSPEC_BIN:-}`) rather than a just variable (`{{OPENSPEC_BIN}}`), so the
# constant never reached the recipe at all. The recipe resolves the CLI itself —
# env override → PATH → `npx` — which is what the comment in the recipe says.

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

[group('scripts')]
[doc('Fetch GitHub issues into config/docs/issues-snapshot.json (needs network)')]
[working-directory(justfile_directory())]
issues-refresh:
    #!/bin/bash
    set -euo pipefail
    python3 scripts/refresh-issues-snapshot.py
