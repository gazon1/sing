#!/usr/bin/env python3
"""toolchain-version.py — the pinned version of a tool, read out of mise.toml.

`mise.toml` is the one place the gate tooling's versions are written down: mise
installs them, on a developer machine and on a runner, so the local run and the
CI run of the same gate use the same bytes.

A shell script that needs a version — the ktlint installer in the autofix job —
cannot read a TOML file. Copying the version out of mise.toml into that script
is the second copy that drifts: the version moves in mise.toml, the copy stays,
and the formatter runs a version the gate does not expect. So the shell asks
this script, and this script answers out of mise.toml.

Nothing but the standard library: the caller is often a CI job whose Python has
none of the project's dependencies.

Usage:
    python3 scripts/toolchain-version.py <tool>

Exit code: 0 and the version on stdout; 1 when the tool is not pinned in
mise.toml, when its pin is not a fixed version ("latest" is not a pin), or when
mise.toml cannot be read.
"""

from __future__ import annotations

import pathlib
import sys
import tomllib

ROOT = pathlib.Path(__file__).resolve().parent.parent
MISE_TOML = ROOT / "mise.toml"

CLEAN = 0
VALIDATION_FAILURE = 1


def pinned_version(tool: str) -> str:
    """The version mise pins for `tool`.

    Raises ValueError when the tool is not pinned, when the pin is not a fixed
    version, or when mise.toml cannot be read — the three ways the answer is
    not a version.
    """
    try:
        with MISE_TOML.open("rb") as handle:
            tools = tomllib.load(handle).get("tools", {})
    except OSError as exc:
        raise ValueError(f"{MISE_TOML} cannot be read: {exc}") from exc
    except tomllib.TOMLDecodeError as exc:
        raise ValueError(f"{MISE_TOML} is not valid TOML: {exc}") from exc

    if tool not in tools:
        raise ValueError(f"{tool} is not pinned in {MISE_TOML.name}")
    version = str(tools[tool])
    if version == "latest":
        raise ValueError(f"the pin of {tool} is 'latest' — that is not a version")
    return version


def main(argv: list[str]) -> int:
    if len(argv) != 1:
        print(f"usage: {pathlib.Path(__file__).name} <tool>", file=sys.stderr)
        return VALIDATION_FAILURE
    try:
        version = pinned_version(argv[0])
    except ValueError as exc:
        print(f"toolchain-version: {exc}", file=sys.stderr)
        return VALIDATION_FAILURE
    print(version)
    return CLEAN


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
