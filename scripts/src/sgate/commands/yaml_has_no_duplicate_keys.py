"""sgate.commands.yaml_has_no_duplicate_keys — native Click for: YAML has no duplicate keys."""
from __future__ import annotations

import re
import sys
from typing import ClassVar

import click
from pydantic import BaseModel
from sgate.log import logger

import sgate
from sgate.output import gate_fail
from sgate.commands._runner import run_capture, Result
from sgate.commands.base import IO_ERROR, INTERNAL_ERROR, register
from sgate.models import GatePayload

# Kiwi-dependent gate (skips if Kiwi TCMS is unavailable)
KIWI_REQUIRED: ClassVar[bool] = False

GATE_NAME = "yaml_duplicate_keys"


class YamlDuplicate(BaseModel):
    """One duplicate YAML key occurrence."""

    file: str
    key: str


class YamlDuplicateKeysPayload(GatePayload):
    """Typed payload for the YAML-duplicate-keys gate."""

    duplicate_count: int = 0
    duplicates: list[YamlDuplicate] = []


# Pattern: "  - path/to/file.yaml: duplicate key 'foo'"
_DUPLICATE_RE = re.compile(r"^\s*-\s+(.+?):\s+duplicate key\s+'([^']+)'$")


def _parse_yaml_duplicate_keys(stdout: str | bytes, stderr: str | bytes) -> YamlDuplicateKeysPayload:
    """Parse check-yaml-duplicate-keys.py --quiet output."""
    if isinstance(stderr, bytes):
        stderr = stderr.decode("utf-8", errors="replace")
    if isinstance(stdout, bytes):
        stdout = stdout.decode("utf-8", errors="replace")
    duplicates: list[YamlDuplicate] = []

    # stderr contains "check-yaml-duplicate-keys: N duplicate key(s)\n  - file: detail"
    # and then a explanatory message
    for line in stderr.splitlines():
        m = _DUPLICATE_RE.match(line)
        if m:
            duplicates.append(YamlDuplicate(file=m.group(1), key=m.group(2)))

    return YamlDuplicateKeysPayload(
        gate=GATE_NAME,
        passed=len(duplicates) == 0,
        duplicate_count=len(duplicates),
        duplicates=duplicates,
        blocking_errors=(
            [] if not duplicates else [f"{len(duplicates)} file(s) have duplicate YAML keys"]
        ),
    )


@click.command("yaml_has_no_duplicate_keys")
@click.option("--check", is_flag=True, help="Dry-run: print result without exiting non-zero")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
def yaml_duplicate_keys(check: bool, emit_json: bool) -> None:
    """Run the "YAML has no duplicate keys" gate.

    Mode: blocking

    Exit codes:
      0 = clean (gate passed)
      1 = validation failure (gate detected an issue)
      2 = I/O error (script not found)
      3 = config error
      4 = kiwi unavailable (KIWI_REQUIRED gate; skip is expected)
      5 = internal error (unexpected exception)

    KIWI_REQUIRED: False
    """
    repo_root = sgate.repo.ROOT

    script = repo_root / 'scripts/check-yaml-duplicate-keys.py'
    if not script.exists():
        logger.error(f"{script} not found")
        gate_fail(GATE_NAME, None, None, None, None, "script not found")
        raise SystemExit(IO_ERROR)

    py_cmd = [sys.executable, str(script), '--quiet']
    try:
        result: Result = run_capture(py_cmd, GATE_NAME)
    except Exception:
        logger.exception("unexpected error")
        gate_fail(GATE_NAME, None, None, None, None, "unexpected error")
        raise SystemExit(INTERNAL_ERROR)

    if emit_json:
        payload = _parse_yaml_duplicate_keys(result.stdout, result.stderr)
        payload.emit_and_exit(result.returncode, emit_json=True)

    # Human-readable: run() already printed stdout, gate_fail already fired
    raise SystemExit(0 if result.returncode == 0 else 1)


register(yaml_duplicate_keys)
