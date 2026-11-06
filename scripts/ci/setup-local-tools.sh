#!/usr/bin/env bash
# Install the gate suite's Python package and confirm the external tools.
#
# The external binaries (actionlint, shellcheck, yamllint, lychee, ktlint,
# rumdl, ...) are provided by mise (see mise.toml) and must be on PATH before
# this runs: `mise install`. This script no longer downloads them itself.
#
# Usage:
#   mise install
#   bash scripts/ci/setup-local-tools.sh
#   export PATH="$HOME/.venvs/singularity-todo-$(basename "$PWD")/bin:$PATH"
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VENV_NAME="singularity-todo-$(basename "$ROOT")"
VENV="${SINGULARITY_TOOLS_VENV:-$HOME/.venvs/$VENV_NAME}"

if ! command -v uv >/dev/null 2>&1; then
    echo "uv is required and is not on PATH." >&2
    exit 1
fi

for tool in actionlint shellcheck yamllint lychee ktlint rumdl; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "missing tool on PATH: $tool — run 'mise install' first." >&2
        exit 1
    fi
done

echo "==> creating the venv at $VENV"
if [ -d "$VENV" ]; then
    existing_origin="$("$VENV/bin/python" -c 'import importlib.util, pathlib; spec = importlib.util.find_spec("sgate"); print(pathlib.Path(spec.origin).resolve().parent if spec and spec.origin else "")' 2>/dev/null || true)"
    if [ -n "$existing_origin" ] && [ "${existing_origin#"$ROOT/scripts/src"}" = "$existing_origin" ]; then
        echo "venv at $VENV serves another checkout: $existing_origin" >&2
        echo "give this checkout its own venv: SINGULARITY_TOOLS_VENV=... bash scripts/ci/setup-local-tools.sh" >&2
        exit 1
    fi
    uv venv --clear "$VENV"
else
    uv venv "$VENV"
fi

echo "==> installing the gate suite (editable) and junitparser"
VIRTUAL_ENV="$VENV" uv pip install --editable "$ROOT/scripts/src" junitparser

for tool in actionlint shellcheck yamllint lychee ktlint rumdl; do
    ln -sfn "$(command -v "$tool")" "$VENV/bin/$tool"
done

cat <<MSG

The gate suite is ready. Put the venv on PATH for this shell:

    export PATH="$VENV/bin:\$PATH"

Then run the gates:

    sgate run-all
MSG
