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
    case "$status" in
        ok)
            printf '  %-14s ok      %s\n' "$tool" "$needs"
            ;;
        foreign)
            printf '  %-14s FOREIGN %s\n' "$tool" "$needs"
            missing=$((missing + 1))
            ;;
        *)
            printf '  %-14s MISSING %s\n' "$tool" "$needs"
            missing=$((missing + 1))
            ;;
    esac
}

have_python_module() {
    python3 -c "import $1" >/dev/null 2>&1
}

have_binary() {
    command -v "$1" >/dev/null 2>&1
}

# Where the importable sgate actually comes from.
#
# An editable install resolves to the source tree it was installed from, so this
# names the *checkout* the gate CLI belongs to. A presence check cannot see the
# difference: a venv installed from another worktree answers "sgate is there",
# and every gate then measures that other checkout — the gate reports green
# about a tree that is not the one in front of you.
sgate_origin() {
    python3 -c 'import pathlib, sgate; print(pathlib.Path(sgate.__file__).resolve().parent)' 2>/dev/null || true
}

echo "Gate tooling:"

if have_python_module sgate; then
    origin="$(sgate_origin)"
    case "$origin" in
        "$ROOT/scripts/src/sgate"*)
            report ok sgate "the gate CLI (this checkout's scripts/src/)"
            ;;
        "")
            report ok sgate "the gate CLI (the local package in scripts/src/)"
            ;;
        *)
            report foreign sgate "the gate CLI — it serves $origin, not this checkout"
            ;;
    esac
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

A venv belongs to the checkout it was installed from: the gate CLI is an
editable install of scripts/src/, so a venv made in another worktree measures
that other checkout. Give each checkout its own venv:

    SINGULARITY_TOOLS_VENV="\$HOME/.venvs/singularity-todo-\$(basename "\$PWD")" \\
        bash scripts/ci/setup-local-tools.sh
    export PATH="\$HOME/.venvs/singularity-todo-\$(basename "\$PWD")/bin:\$PATH"

(uv is the installer; see https://docs.astral.sh/uv/getting-started/installation/ if
uv itself is not on PATH. The venv location can be overridden with
SINGULARITY_TOOLS_VENV.)

If the gate CLI is the one that was reported missing, run the installer *in this
checkout*: the package is installed editable, so the venv points at whichever
checkout installed it last, and the gate suite then runs that checkout's
registry instead of this one's — a different set of gates, reported green.

The same tools are installed in CI by .github/actions/setup-python-tools, so a run
that passes here and fails there is a version difference, not a missing install.
MSG
    exit 1
fi

echo "  all present."
