#!/usr/bin/env python3
"""check-gate-json-contract.py — every gate dispatched through `sgate` speaks one contract.

``sgate run-all`` calls each gate with ``--json``, and the document that lands on
stdout is what a consumer reads. The contract has exactly one shape: **one JSON
object, and nothing else**. A line of prose in front of it — a GitHub Actions
annotation, a warning, a debug print — makes the document unparseable, and the
consumer that trusted it gets a stack trace instead of a verdict.

Nothing checked that, and the defect happened twice before this script existed:

  - the annotation collision. ``gate_fail`` writes ``::error::`` to stdout,
    because that is where Actions reads workflow commands from. A gate whose
    stdout carried a contract had two claimants for one channel, and in CI the
    annotation won;
  - the four adapters that rebuilt a verdict from their producer's prose
    instead of reading the verdict the producer already emitted.

Both were found by reading the code, and both were found late. A gate that
reads what every other gate reads would have found them.

The probe reuses the liveness probe's heavyweight filter, so the two gates skip
the same gates: a gate that boots Gradle is expensive for both, and two
independent filters would drift apart exactly where the cost is.

Usage:
    python3 scripts/check-gate-json-contract.py
    python3 scripts/check-gate-json-contract.py --json
"""
from __future__ import annotations

import importlib.util
import json
import os
import pathlib
import re
import shlex
import subprocess
import sys

import click
from sgate.commands.base import run

CLEAN = 0
VALIDATION_FAILURE = 1
IO_ERROR = 2

ROOT = pathlib.Path(__file__).resolve().parent.parent
REGISTRY = ROOT / 'scripts' / 'ci' / 'static-gates.sh'

#: Seconds per probe. The same order as the liveness probe's budget: a gate
#: that needs longer than this is a gate to report, not to wait for.
PROBE_TIMEOUT = 300

GATE_RE = re.compile(r'^gate\s+(blocking|advisory)\s+"([^"]+)"\s+(.+?)\s*$', re.MULTILINE)


def _liveness_module():
    """Load ``check-gate-liveness.py`` by path, for its heavyweight filter.

    Loaded by path rather than imported as a package module, because it is a
    standalone script that lives outside ``scripts/src`` and computes its own
    ROOT from ``__file__`` — the same trick ``check-gate-wiring.py`` uses to
    stay trustworthy on a machine with two checkouts of this repository.

    Returns None when the script cannot be loaded; the probe then probes
    everything, which is the pre-2026-10-15 behaviour, not a crash.
    """
    path = ROOT / 'scripts' / 'check-gate-liveness.py'
    if not path.is_file():
        return None
    spec = importlib.util.spec_from_file_location('check_gate_liveness', path)
    if spec is None or spec.loader is None:
        return None
    module = importlib.util.module_from_spec(spec)
    sys.modules['check_gate_liveness'] = module
    try:
        spec.loader.exec_module(module)
    except Exception:  # noqa: BLE001 — an unresolvable filter means "probe it"
        return None
    return module


def _probe_argv(name: str) -> list[str]:
    """The command that runs one gate in **this** checkout.

    Not the ``sgate`` on PATH: on a machine with two checkouts of this
    repository the binary on PATH is an editable install pointing at the
    other one, and the gate would then certify somebody else's code. The
    module is run from this checkout's ``scripts/src`` explicitly, so what is
    probed is what is on disk here.
    """
    return [sys.executable, '-m', 'sgate', name, '--json']


def sgate_gates() -> list[tuple[str, str]]:
    """(display name, command) for every gate the shell registry dispatches via ``sgate``.

    The shell registry is what CI actually runs, so it is the thing to check.
    A gate written as ``python3 scripts/check-something.py`` has no JSON
    contract — it prints human text and is judged by its exit code.
    """
    if not REGISTRY.is_file():
        return []
    out = []
    for _level, name, cmd in GATE_RE.findall(REGISTRY.read_text(encoding='utf-8')):
        parts = shlex.split(cmd)
        if len(parts) >= 2 and parts[0] == 'sgate':
            out.append((name, cmd))
    return out


def contract_violation(stdout: str) -> str | None:
    """None when stdout is exactly one JSON object; the reason when it is not.

    A gate that printed nothing is a violation too: a consumer that reads the
    document finds nothing to read, and a document that says nothing is not a
    verdict.
    """
    text = (stdout or '').strip()
    if not text:
        return 'printed nothing on stdout'
    try:
        data = json.loads(text)
    except json.JSONDecodeError as exc:
        return f'stdout is not one JSON document ({exc})'
    if not isinstance(data, dict):
        return f'the JSON document is a {type(data).__name__}, not an object'
    if 'passed' not in data:
        return "the JSON document has no 'passed' field"
    return None


def _probe_env() -> dict:
    """The environment the probe runs a gate in.

    ``PYTHONPATH`` points at this checkout's ``scripts/src``, so the gate that
    is probed is the code on disk here and not whatever an editable install
    elsewhere happens to resolve to.
    """
    env = dict(os.environ)
    env['PYTHONPATH'] = str(ROOT / 'scripts' / 'src')
    return env


def probe(name: str, cmd: str) -> str | None:
    """Run one gate with ``--json``; return the violation, or None when it is clean."""
    parts = shlex.split(cmd)
    argv = _probe_argv(parts[1])
    env = _probe_env()
    try:
        proc = subprocess.run(
            argv, cwd=ROOT, capture_output=True, text=True, timeout=PROBE_TIMEOUT,
            # Never inherit the runner's stdin. A gate that reads stdin blocks
            # forever in Actions, where stdin is an open pipe with no writer.
            stdin=subprocess.DEVNULL, env=env,
        )
    except FileNotFoundError:
        return f'{name}: {sys.executable} not found'
    except subprocess.TimeoutExpired:
        return f'{name}: no verdict after {PROBE_TIMEOUT}s'

    reason = contract_violation(proc.stdout)
    if reason is None:
        return None
    first = (proc.stdout or '').strip().splitlines()[:1]
    head = f' (first line: {first[0]!r})' if first else ''
    return f'{name}: {reason}{head}'


def evaluate() -> tuple[list[str], list[str], int]:
    """Probe every sgate-dispatched gate. Returns (violations, skipped, probed)."""
    liveness = _liveness_module()
    violations: list[str] = []
    skipped: list[str] = []
    probed = 0
    for name, cmd in sgate_gates():
        if liveness is not None and liveness.is_slow(name, cmd):
            skipped.append(name)
            continue
        violation = probe(name, cmd)
        if violation is not None:
            violations.append(violation)
            continue
        probed += 1
    return violations, skipped, probed


@click.command()
@click.option("--json", "emit_json", is_flag=True, help="Emit the verdict as JSON.")
@click.pass_context
def cli(ctx: click.Context, emit_json: bool) -> None:
    """Every gate dispatched through `sgate` prints exactly one JSON document on stdout."""
    if not REGISTRY.is_file():
        click.echo(f'check-gate-json-contract.py: registry not found at {REGISTRY}', err=True)
        raise ctx.exit(IO_ERROR)

    violations, skipped, probed = evaluate()

    if violations:
        if emit_json:
            click.echo(json.dumps({
                'passed': False,
                'blocking_errors': violations,
                'probed': probed,
                'skipped': skipped,
            }, ensure_ascii=False, indent=2))
        else:
            click.echo(
                f'check-gate-json-contract.py: {len(violations)} gate(s) do not speak '
                f'the JSON contract',
                err=True,
            )
            for violation in violations:
                click.echo(f'  {violation}', err=True)
        raise ctx.exit(VALIDATION_FAILURE)

    if emit_json:
        click.echo(json.dumps({
            'passed': True,
            'blocking_errors': [],
            'probed': probed,
            'skipped': skipped,
        }, ensure_ascii=False, indent=2))
    else:
        message = f'{probed} gate(s) speak the JSON contract'
        if skipped:
            message += f'; {len(skipped)} skipped as heavyweight'
        click.echo(f'check-gate-json-contract.py: {message}')
    raise ctx.exit(CLEAN)


if __name__ == '__main__':
    raise SystemExit(run(cli))
