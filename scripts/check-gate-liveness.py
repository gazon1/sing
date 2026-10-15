#!/usr/bin/env python3
"""check-gate-liveness.py — a gate is green because it passed, or because it died?

The failure this exists for (#529)
---------------------------------
A Python gate that raises instead of reporting looks exactly like a gate that
reports "clean" to anything that only looks at the exit code — and in CI, a crash
is a *non-zero* exit, so it is usually not green. The subtler and much more
expensive case is the opposite: a gate whose command never runs the check at all.

That is not hypothetical. In this repository, on 2026-10-10, two ADR gates were
broken and neither was noticed:

    check-backlog-status.py        NameError: name 'path' is not defined
    check-backlog-issue-refs.py    pointed at a deleted file, exited "backlog not found"

Both were *registered* in the gate registry. Both ran. Both printed something that
read like a verdict. Neither ever checked a document again.

What this gate asserts
----------------------
For every `gate` line in `scripts/ci/static-gates.sh`:

  1. the command is runnable from the repository root — a missing file, a bad path
     or an unresolvable command must be reported, not skipped;
  2. it does not die with an uncaught exception (traceback on stderr);
  3. a *crash* is distinguished from a *finding*: a gate that exits non-zero because
     it found a violation is working, and must not be reported as broken.

Rule 3 is the point. Without it this script would simply be "run every gate and
complain", which duplicates CI and tells you nothing new. The liveness question is
narrower and answerable: did this gate get to have an opinion?

Usage:
    python3 scripts/check-gate-liveness.py            # check every registered gate
    python3 scripts/check-gate-liveness.py --list     # just enumerate them
"""

from __future__ import annotations

import argparse
import pathlib
import re
import shlex
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
REGISTRY = ROOT / 'scripts' / 'ci' / 'static-gates.sh'

GATE_RE = re.compile(r'^gate\s+(blocking|advisory)\s+"([^"]+)"\s+(.+?)\s*$', re.MULTILINE)

# `static-gates.sh` registers some gates by calling a shell function defined in the
# same file (`traceability validate`, `adr_dry_run`). Those are not executables, so
# probing them as a command would report every one of them as unrunnable — which is
# how a liveness gate turns into noise that everyone learns to ignore.
FUNCTION_RE = re.compile(r'^([A-Za-z_][A-Za-z0-9_]*)\(\)\s*\{', re.MULTILINE)

# A traceback is the signature of "died instead of reporting". Gates are allowed to
# print ERROR: lines — that is how they report findings.
TRACEBACK_MARKERS = (
    'Traceback (most recent call last)',
    'NameError:',
    'AttributeError:',
    'TypeError:',
    'ImportError:',
    'ModuleNotFoundError:',
    'FileNotFoundError:',
    'UnboundLocalError:',
    'SyntaxError:',
    'IndentationError:',
)

# Skipping the heavyweight ones: this script runs 40 gates and must finish in
# seconds, not minutes. Each of these either builds the project or is itself a
# meta-gate that already covers the liveness question.
#
# WARNING: this prefix filter used to be the whole mechanism, and it silently
# stopped matching when the registry moved to `sgate <subcommand>`. Every gate
# then probed as `sgate ...`, which matches none of these prefixes, so the probe
# actually ran Gradle-invoking gates (`declared dependencies are used` →
# `check-dependency-usage.py` → `./gradlew :shared:printResolvedArtifacts`).
# One cold Kotlin daemon per probe, several concurrently — a probe of the gate
# registry exhausted RAM on the dev machine (2026-10-15). `resolves_to_gradle()`
# below is the real defence; these prefixes stay for shell-function and direct
# invocations that never reach a registry entry.
SLOW_PREFIXES = (
    './gradlew', 'gradlew', './just', 'just ', 'bash scripts/ci/',
    'python3 -m unittest', 'python3 -m pytest',
)
SLOW_NAMES = {
    "gate scripts' own unit tests",
    # Runs Gradle; see resolves_to_gradle(). Named here too so the skip is
    # visible where a reader looks for it.
    "declared dependencies are used",
}

#: Shapes that mean "this script actually spawns the JVM toolchain", matched in
#: the gate's *resolved* script so a registry command that wraps one is caught.
#:
#: Deliberately the argv-list form (`["./gradlew", …]`), not the bare string.
#: A bare `./gradlew` also appears in docstrings, in `print()` lines that suggest
#: a command to the user, and in README fixtures — `check-test-task-ins.py:13`,
#: `check-baseline-ratchet.py:237`, `check-publication-hygiene.py:387`. Matching
#: those would skip three cheap gates as "heavyweight" and silently weaken this
#: gate, which is the same failure as the one this filter just had.
GRADLE_MARKERS = ('["./gradlew"', "['./gradlew'", '"./gradlew",')

# Marker for gates that must not be probed. Declared in the registry (static-gates.sh)
# rather than guessed from the script's own filename, so the rule is visible where
# readers look and applies to any future meta-gate without code changes (#534).
SELF_PROBE_SKIP = '# no-self-probe'

# Seconds per probe. Generous, because a timeout is now reported as `slow` rather
# than `dead` and a generous budget stops healthy gates from being reported at all.
PROBE_TIMEOUT = 300


def registered_gates() -> list[tuple[str, str, str]]:
    """(level, name, command) for every gate in the registry."""
    if not REGISTRY.is_file():
        sys.exit(f'check-gate-liveness.py: registry not found at {REGISTRY}')
    text = REGISTRY.read_text(encoding='utf-8')
    out = []
    for level, name, cmd in GATE_RE.findall(text):
        out.append((level, name, cmd.strip()))
    return out


def registry_functions() -> set[str]:
    """Shell functions `static-gates.sh` defines and later invokes as gates."""
    if not REGISTRY.is_file():
        return set()
    return set(FUNCTION_RE.findall(REGISTRY.read_text(encoding='utf-8')))


_SCRIPT_CMD_TO_GATE: dict[str, str] | None = None


def _command_name(display_name: str) -> str:
    """The CLI command for a gate, derived from its display name.

    The registry stores `room schema integrity`; the registry calls
    `sgate room_schema_integrity`. Keys must be the command, not the display
    name, or every lookup misses.
    """
    return re.sub(r'[^a-z0-9]+', '_', display_name.lower()).strip('_')


def gate_scripts() -> dict[str, str]:
    """Map `sgate <subcommand>` -> the script path it dispatches to.

    Most registry commands are `sgate <name>`, which says nothing about cost.
    The registry knows the real script behind each one, so the heavyweight
    filter reads it from there instead of from the command text.
    """
    global _SCRIPT_CMD_TO_GATE
    if _SCRIPT_CMD_TO_GATE is None:
        mapping: dict[str, str] = {}
        try:
            sys.path.insert(0, str(ROOT / 'scripts' / 'src'))
            from sgate.commands import _registry  # noqa: PLC0415
            for spec in _registry.GATES:
                if spec.name and spec.script:
                    mapping[_command_name(spec.name)] = str(spec.script)
        except Exception:  # noqa: BLE001 — a missing registry means "probe it",
            # which is the pre-2026-10-15 behaviour, not a crash.
            mapping = {}
        _SCRIPT_CMD_TO_GATE = mapping
    return _SCRIPT_CMD_TO_GATE


def resolves_to_gradle(cmd: str) -> bool:
    """True when a registry command ends in a script that cold-boots Gradle."""
    parts = cmd.split()
    if len(parts) < 2 or parts[0] != 'sgate':
        return False
    script = gate_scripts().get(parts[1])
    if not script:
        return False
    path = ROOT / script
    try:
        text = path.read_text(encoding='utf-8')
    except OSError:
        return False
    return any(marker in text for marker in GRADLE_MARKERS)


def is_slow(name: str, cmd: str) -> bool:
    if name in SLOW_NAMES:
        return True
    if any(cmd.startswith(p) for p in SLOW_PREFIXES):
        return True
    return resolves_to_gradle(cmd)


def is_registry_function(cmd: str, functions: set[str]) -> bool:
    """True when the gate's first word is a shell function from the registry.

    Those gates are *not* probeable out of context — they need the harness that
    sourced them. Reporting them as unrunnable would be a false positive on every
    run, so they are counted and named instead of probed.
    """
    return bool(cmd.split()[:1] and cmd.split()[0] in functions)


def probe(name: str, cmd: str, budget: int = PROBE_TIMEOUT) -> tuple[str, str]:
    """Run one gate. Returns (verdict, detail).

    verdict is one of: ok, finding, slow, dead, unrunnable.

    **`slow` is not `dead`, and keeping them apart is the whole point.** The first
    version called a timeout a death, so one gate that shells out to Gradle when its
    cache file is absent — `declared dependencies are used`, which reads
    `/tmp/_resolved.tsv` and otherwise runs `:shared:printResolvedArtifacts` — reddened
    the whole CI job on a GitHub runner. Locally the file existed and the gate took
    1.9 s; in CI it did not, and a cold daemon took longer than the probe budget. The
    gate was healthy. The verdict was wrong.

    A meta-gate that cannot tell "crashed" from "slow" will eventually report a dead
    gate for a live one, and the first thing anyone does then is ignore it.
    """
    argv = shlex.split(cmd)
    if not argv:
        return 'unrunnable', 'empty command'

    # Every script a gate names must exist before we run it. A gate pointing at a
    # deleted file is the exact shape of the 2026-10-10 breakage.
    for token in argv:
        candidate = token.lstrip('-')
        if candidate.endswith('.py') and not (ROOT / candidate).is_file():
            return 'unrunnable', f'{candidate} does not exist'

    try:
        proc = subprocess.run(
            argv, cwd=ROOT, capture_output=True, text=True, timeout=budget,
            # Never inherit the runner's stdin. A gate that reads stdin blocks
            # forever in Actions, where stdin is an open pipe with no writer.
            stdin=subprocess.DEVNULL,
        )
    except FileNotFoundError:
        return 'unrunnable', f'{argv[0]} not found'
    except subprocess.TimeoutExpired:
        return 'slow', f'no verdict after {budget}s'
    except PermissionError:
        return 'unrunnable', f'{argv[0]} is not executable'

    stderr = proc.stderr or ''
    for marker in TRACEBACK_MARKERS:
        if marker in stderr:
            first = next((l for l in stderr.splitlines() if marker in l), marker)
            return 'dead', first.strip()

    if proc.returncode == 0:
        return 'ok', ''
    # Non-zero without a traceback is a gate that worked and found something.
    return 'finding', ''


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--list', action='store_true',
                        help='print the gates this script would probe, then exit')
    parser.add_argument('--include-slow', action='store_true',
                        help='also probe gates that invoke gradle or the full test suite')
    args = parser.parse_args()

    gates = registered_gates()
    if not gates:
        sys.exit('check-gate-liveness.py: registry parsed to zero gates — the regex is wrong, '
                 'or the registry was truncated')

    targets = [g for g in gates
               if SELF_PROBE_SKIP not in g[2]
               and (args.include_slow or not is_slow(g[1], g[2]))]
    skipped = len(gates) - len(targets)

    if args.list:
        for level, name, cmd in targets:
            print(f'{level:9} {name}')
        print(f'\n{len(targets)} probeable, {skipped} skipped as slow')
        return 0

    functions = registry_functions()
    dead: list[tuple[str, str]] = []
    unrunnable: list[tuple[str, str]] = []
    slow: list[tuple[str, str]] = []
    findings: list[str] = []
    via_function: list[str] = []

    for level, name, cmd in targets:
        if is_registry_function(cmd, functions):
            via_function.append(name)
            continue
        verdict, detail = probe(name, cmd)
        if verdict == 'dead':
            dead.append((name, detail))
        elif verdict == 'unrunnable':
            unrunnable.append((name, detail))
        elif verdict == 'slow':
            slow.append((name, detail))
        elif verdict == 'finding':
            findings.append(name)

    for name in via_function:
        print(f'note: {name} — runs a shell function from the registry; '
              f'not probeable out of context, see static-gates.sh')
    for name in findings:
        print(f'note: {name} — reported a finding (non-zero, no crash); that is the gate working')
    for name, detail in unrunnable:
        print(f'ERROR: gate "{name}" cannot run: {detail}')
    for name, detail in slow:
        # Reported, not fatal. A gate that is merely slow is a different problem from
        # one that cannot answer at all, and merging the two verdicts is what turned a
        # healthy Gradle-calling gate into a red CI job.
        print(f'note: {name} — {detail}; slow is not dead, so this is not a failure')
    for name, detail in dead:
        print(f'ERROR: gate "{name}" died instead of reporting: {detail}')

    print('')
    probed = len(targets) - len(via_function)
    print(f'check-gate-liveness.py: {probed} gate(s) probed, '
          f'{len(dead)} died, {len(unrunnable)} unrunnable, {len(slow)} slow, '
          f'{len(findings)} reported findings, '
          f'{len(via_function)} shell-function gates, '
          + (f'{skipped} skipped as slow' if skipped else '0 skipped as slow'))

    if dead or unrunnable:
        print('A gate that dies is indistinguishable from a gate that passes, for every '
              'consumer that only reads the exit code. This one is the difference.')
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())