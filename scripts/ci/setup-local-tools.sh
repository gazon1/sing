#!/usr/bin/env bash
# Install everything the gate suite shells out to — with uv, into one venv.
#
# The gate suite needs:
#   - sgate          the gate CLI (the local package in scripts/src/)
#   - junitparser    read by scripts/junit-annotations.py
#   - lychee         the "documentation links resolve" gate
#   - yamllint       the "YAML is structurally valid" gate
#   - shellcheck     actionlint's shellcheck integration
#   - actionlint     the "GitHub workflows are valid" gate
#
# Why a venv, and why uv: CI installs exactly this, in exactly this shape, through
# .github/actions/setup-python-tools. The four ways this was installed before all
# failed on their own terms — `python -m pip install` broke when CI moved to uv,
# `uv pip install --system` refused an externally managed interpreter,
# `--break-system-packages` hit a permission error, and `--prefix "$HOME/.local"`
# installed a package the interpreter could not import. A venv on PATH answers all
# four, and it is the same arrangement CI uses, so a local run and a CI run of the
# same gate agree.
#
# The local package must be *installed*, not merely on PYTHONPATH: the gate
# scripts' own unit tests run the scripts with a minimal environment
# (`env={'PATH': ...}`), so a package that is only on PYTHONPATH is invisible to
# them and they fail with ModuleNotFoundError — a failure that looks like a broken
# test rather than a missing install.
#
# Usage:
#   bash scripts/ci/setup-local-tools.sh
#   export PATH="$HOME/.venvs/singularity-todo-$(basename "$PWD")/bin:$PATH"
#
# The venv location can be overridden with SINGULARITY_TOOLS_VENV.
#
# A venv belongs to the checkout it was installed from. The gate CLI is an
# editable install of scripts/src/, so it resolves to the tree it was installed
# from — a venv made in one worktree measures that worktree, and every gate run
# through it reports about a tree that is not in front of you. So the venv is
# named after the checkout by default, and the script refuses to take over a
# venv that serves another one.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# The venv is named after the checkout, so two checkouts never share one. The
# name is computed here and printed literally in the messages below: the reader
# pastes those commands into their own shell, where ROOT does not exist.
VENV_NAME="singularity-todo-$(basename "$ROOT")"
VENV="${SINGULARITY_TOOLS_VENV:-$HOME/.venvs/$VENV_NAME}"

# The version pins live in one file, shared with the CI install path, so a local
# run and a CI run of the same gate install the same versions. The pins are not
# repeated here: a version written out twice is a version that can drift, and
# the drift shows up as a local/CI disagreement rather than as a version bump.
PINS_FILE="$ROOT/config/toolchain-versions.env"
if [ ! -f "$PINS_FILE" ]; then
    echo "missing the toolchain pins file: $PINS_FILE" >&2
    exit 1
fi
# shellcheck disable=SC1090,SC1091  # $PINS_FILE is a runtime path; it names config/toolchain-versions.env
source "$PINS_FILE"

if ! command -v uv >/dev/null 2>&1; then
    cat >&2 <<'MSG'
uv is required and is not on PATH.

Install it:  https://docs.astral.sh/uv/getting-started/installation/

The gate suite is installed with uv on purpose: it is the same tool CI uses, and
the versions are the ones CI pins — both read them from
config/toolchain-versions.env, the single source for the pins.
MSG
    exit 1
fi

echo "==> creating the venv at $VENV"
if [ -d "$VENV" ]; then
    # An existing venv may belong to another checkout. The gate CLI is an
    # editable install of scripts/src/, so it resolves to the tree it was
    # installed from: recreating the venv here repoints that other checkout's
    # gates at this one. That is how one checkout takes another's tooling
    # over — silently, because the result is a green run about the wrong tree.
    #
    # So the venv is not taken over by default. The script says what to do
    # instead and stops: a venv asked for by name is the caller's decision (it
    # is taken over, with a warning), a venv found by the default is not.
    # The origin is read with find_spec, not with `import sgate`: importing
    # needs the venv's dependencies, and a venv whose dependencies are missing
    # would then look unclaimed and be taken over — which is the very thing
    # this check is here to prevent.
    existing_origin="$("$VENV/bin/python" -c 'import importlib.util, pathlib; spec = importlib.util.find_spec("sgate"); print(pathlib.Path(spec.origin).resolve().parent if spec and spec.origin else "")' 2>/dev/null || true)"
    if [ -n "$existing_origin" ] && [ "${existing_origin#"$ROOT/scripts/src"}" = "$existing_origin" ]; then
        # The name this checkout would own. It is the default path — unless the
        # caller asked for that very path by name, in which case a different
        # name is needed for this checkout's own venv.
        own_name="$VENV_NAME"
        if [ "$VENV" = "$HOME/.venvs/$VENV_NAME" ]; then
            own_name="$VENV_NAME-2"
        fi
        if [ -n "${SINGULARITY_TOOLS_VENV:-}" ]; then
            cat >&2 <<MSG

The venv at $VENV serves another checkout:
    $existing_origin

It was named explicitly, so it is taken over — and from now on that checkout's
gates measure this one. If that checkout is still in use, give this checkout a
venv of its own:

    SINGULARITY_TOOLS_VENV="$HOME/.venvs/$own_name" \\
        bash scripts/ci/setup-local-tools.sh
    export PATH="$HOME/.venvs/$own_name/bin:\$PATH"

MSG
        else
            cat >&2 <<MSG

The venv at $VENV serves another checkout:
    $existing_origin

Taking it over would repoint that checkout's gates at this one, and a gate run
about the wrong tree is a green run that says nothing. So the script stops.

Give this checkout a venv under another name:

    SINGULARITY_TOOLS_VENV="$HOME/.venvs/$own_name" \\
        bash scripts/ci/setup-local-tools.sh
    export PATH="$HOME/.venvs/$own_name/bin:\$PATH"

or remove that install — this checkout has nothing in it — and let the
installer make the venv whose name belongs to this checkout:

    rm -rf "$VENV"
    bash scripts/ci/setup-local-tools.sh

MSG
            exit 1
        fi
    fi
    uv venv --clear "$VENV"
else
    uv venv "$VENV"
fi

echo "==> installing the gate suite (the local package, editable) and junitparser"
VIRTUAL_ENV="$VENV" uv pip install --editable "$ROOT/scripts/src" junitparser

echo "==> installing the pinned linters and the link checker"
VIRTUAL_ENV="$VENV" uv pip install "yamllint==$YAMLLINT_VERSION"

# The pinned binaries are the same bytes in every checkout — same pins, same
# downloads — and they are the expensive part: lychee alone is 303 MB, and one
# copy per checkout is 8.9 GB of identical binaries on one machine (measured:
# thirty venvs, `du -ch ~/.venvs/*/bin/lychee`).
#
# So they live in one shared store, keyed by version, and the venv gets a
# symlink to the shared copy. The split is the one the Gradle home already
# makes (ADR 2026-10-04-gradle-daemon-isolation): what is read-mostly and
# identical everywhere is shared, what belongs to a checkout stays private.
# The checkout's own code — the editable install of scripts/src/ — is the
# private half, and that is exactly the half that must never be shared.
TOOLS_ROOT="${SINGULARITY_TOOLS_ROOT:-$HOME/.venvs/_tools}"

# shared_binary <tool> <version> — where the shared copy of a pinned tool is.
# The binary carries the tool's name: the `lychee-bin` wheel ships a `lychee`
# binary, the `shellcheck-py` wheel ships a `shellcheck` binary, and the
# actionlint release ships `actionlint`.
shared_binary() {
    printf '%s/%s-%s/bin/%s' "$TOOLS_ROOT" "$1" "$2" "$1"
}

# ensure_shared_wheel_binary <tool> <version> <requirement>
#
# The wheel is the cheapest way to get the binary, and the wheel's binary is
# what the gates run. It is installed into the store — once per version — and
# the venv points at it.
ensure_shared_wheel_binary() {
    local tool=$1 version=$2 requirement=$3
    local store shared
    store="$TOOLS_ROOT/$tool-$version"
    shared="$(shared_binary "$tool" "$version")"
    if [ ! -x "$shared" ]; then
        echo "    $tool $version -> the shared store"
        uv venv "$store" >/dev/null
        VIRTUAL_ENV="$store" uv pip install "$requirement" >/dev/null
    fi
    ln -sfn "$shared" "$VENV/bin/$tool"
    echo "    $tool -> $shared"
}

ensure_shared_wheel_binary lychee "$LYCHEE_BIN_VERSION" "lychee-bin==$LYCHEE_BIN_VERSION"
ensure_shared_wheel_binary shellcheck "$SHELLCHECK_PY_VERSION" "shellcheck-py==$SHELLCHECK_PY_VERSION"

# actionlint has no Python distribution: CI downloads the pinned release binary.
# A download failure here is reported and the script continues — the preflight
# is what fails loudly, and it names what is missing. Nothing is skipped
# silently.
#
# The release tag and the asset version come from the same pin, so the two
# cannot name different releases.
echo "==> installing the pinned actionlint"
ACTIONLINT_ASSET_VERSION="${ACTIONLINT_VERSION#v}"
ACTIONLINT_SHARED="$(shared_binary actionlint "$ACTIONLINT_VERSION")"
mkdir -p "$(dirname "$ACTIONLINT_SHARED")"
if [ ! -x "$ACTIONLINT_SHARED" ]; then
    if curl -fsSL "https://github.com/rhysd/actionlint/releases/download/$ACTIONLINT_VERSION/actionlint_${ACTIONLINT_ASSET_VERSION}_linux_amd64.tar.gz" \
        | tar -xz -C "$(dirname "$ACTIONLINT_SHARED")" actionlint; then
        echo "    actionlint $ACTIONLINT_VERSION -> the shared store"
    else
        echo "    actionlint could not be downloaded — the 'GitHub workflows are valid' gate" >&2
        echo "    will fail until it is on PATH. The preflight will say so." >&2
    fi
fi
if [ -x "$ACTIONLINT_SHARED" ]; then
    ln -sfn "$ACTIONLINT_SHARED" "$VENV/bin/actionlint"
    echo "    actionlint -> $ACTIONLINT_SHARED"
fi

cat <<MSG

The gate suite is ready. Put the venv on PATH for this shell:

    export PATH="$VENV/bin:\$PATH"

The gate CLI now serves this checkout:

    "$VENV/bin/python" -c 'import pathlib, sgate; print(pathlib.Path(sgate.__file__).resolve().parent)'

The pinned binaries (lychee, shellcheck, actionlint) are shared, by version:

    $TOOLS_ROOT/<tool>-<version>/bin/<tool>

and this venv's copies are symlinks into it. One copy per version on the
machine, not one per checkout.

Then check what is still missing:

    bash scripts/ci/preflight-gate-tools.sh

and run the gates:

    sgate run-all
MSG
