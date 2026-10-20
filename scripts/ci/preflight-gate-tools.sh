#!/usr/bin/env bash
# Check that the tools the gate suite shells out to are present, and say how to get them.
#
# Why this exists: a gate that cannot run looks exactly like a gate that has
# nothing to report. A missing `lychee` used to fail the "documentation links
# resolve" gate with a traceback; a missing `sgate` install made the gate
# scripts' own unit tests fail with ModuleNotFoundError — a failure that reads
# like a broken test rather than a missing install. Both cost a debugging
# session each.
#
# The check is cheap and it names the fix. It runs before the gate suite, in
# check.sh, and on demand.
#
# Usage:  bash scripts/ci/preflight-gate-tools.sh
#
# Exit code: 0 when every tool is present, 1 otherwise.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

missing=0

report() {
    # report <status> <tool> <what needs it>
    local status=$1 tool=$2 needs=$3
    if [ "$status" = "ok" ]; then
        printf '  %-14s ok      %s\n' "$tool" "$needs"
    else
        printf '  %-14s MISSING %s\n' "$tool" "$needs"
        missing=$((missing + 1))
    fi
}

have_python_module() {
    python3 -c "import $1" >/dev/null 2>&1
}

have_binary() {
    command -v "$1" >/dev/null 2>&1
}

echo "Gate tooling:"

if have_python_module sgate; then
    report ok sgate "the gate CLI (the local package in scripts/src/)"
else
    report no sgate "the gate CLI (the local package in scripts/src/)"
fi

if have_python_module junitparser; then
    report ok junitparser "the per-test CI annotations"
else
    report no junitparser "the per-test CI annotations"
fi

if have_binary lychee; then
    report ok lychee "the 'documentation links resolve' gate"
else
    report no lychee "the 'documentation links resolve' gate"
fi

if have_binary yamllint; then
    report ok yamllint "the 'YAML is structurally valid' gate"
else
    report no yamllint "the 'YAML is structurally valid' gate"
fi

if have_binary actionlint; then
    report ok actionlint "the 'GitHub workflows are valid' gate"
else
    report no actionlint "the 'GitHub workflows are valid' gate"
fi

if have_binary shellcheck; then
    report ok shellcheck "actionlint's shellcheck integration (it is disabled without it)"
else
    report no shellcheck "actionlint's shellcheck integration (it is disabled without it)"
fi

if [ "$missing" -gt 0 ]; then
    cat >&2 <<MSG

$missing tool(s) the gate suite needs are missing. Install them with:

    bash scripts/ci/setup-local-tools.sh
    export PATH="\$HOME/.venvs/singularity-todo/bin:\$PATH"

(uv is the installer; see https://docs.astral.sh/uv/getting-started/installation/ if
uv itself is not on PATH. The venv location can be overridden with
SINGULARITY_TOOLS_VENV.)

The same tools are installed in CI by .github/actions/setup-python-tools, so a run
that passes here and fails there is a version difference, not a missing install.
MSG
    exit 1
fi

echo "  all present."
