#!/usr/bin/env python3
"""check-recorded-ci-jobs.py — the recorded CI run still names jobs the workflow has.

What went wrong
---------------
The published page prices the CI loop from ONE run, and the run's jobs are the
record: `config/docs/gate-benchmarks.json` holds them, `docs/testing/gate-costs.md`
prints them. The criterion that decides when that record goes stale is written
down in the ADR of 2026-10-21: **the record is stale when the workflow's jobs
change** — a job renamed, added or removed turns it into a price of a loop that
no longer exists.

Nothing checked it. The false names were exactly this: the job was called
`JVM tests, detekt, coverage` while it ran neither coverage nor detekt, then it
was renamed to `JVM tests`, and the published page kept naming the old job for
a whole change — because the record is a run's record, and a run's record does
not notice that the workflow moved on. The page said "the last finished run of
`main`", which expired on every push for a different reason.

The rule
--------
Every job the recorded run names must still be a job the workflow declares.

**Containment, not equality.** The record holds the jobs that RAN — ten of them
— and the workflow declares the jobs that CAN run — fourteen, because four of
them are conditional (a pull request's jobs, the nightly alert). The two sets
are not the same size, and they were never meant to be: equality would be red
forever, and a gate that is red forever is a gate nobody reads. So the rule is
one-sided: a recorded job that the workflow no longer declares is a finding.

A matrix job is expanded before the comparison. `Android (${{ matrix.variant }})`
declares one job and the runner shows two — `Android (free)` and
`Android (pro)` — and the record holds the two the runner showed. A comparison
that does not expand the matrix would call both of them unknown.

What the rule does NOT see
---------------------------
- **A job that was renamed and renamed back.** The record names what it names.
- **An edit that keeps every name and changes what a job does.** The criterion
  is about the JOBS, and the check is the same proxy: a job whose steps changed
  is still a job with that name. A stricter rule would hash the job bodies and
  expire the record on every comment in a step — which is not what the criterion
  says.
- **A job added to the trunk path.** The set of jobs a trunk push runs depends
  on the paths the push touched, so "the workflow's trunk jobs" is not a fixed
  set. The record holds the jobs of ONE push, and this gate says they still
  exist.

Usage:
    python3 scripts/check-recorded-ci-jobs.py            # report
    python3 scripts/check-recorded-ci-jobs.py --quiet
    python3 scripts/check-recorded-ci-jobs.py --root DIR  # another tree

Exit codes:
    0 — every recorded job is still a job the workflow declares
    1 — a finding
    2 — the tree could not be read (a configuration error)
"""

from __future__ import annotations

import itertools
import json
import pathlib
import re
import sys

import click
import yaml

# `scripts/src` on sys.path, and it has to be there *before* the first import
# that uses it: this gate must resolve `sgate` against *this* checkout, not
# against whatever `sgate` happens to be installed — an editable install of a
# sibling worktree is a different tree under the same package name, and an
# import that happens first is already bound to it. When this file is loaded by
# a test harness via importlib, sys.path[0] is `scripts/tests` and the package
# is invisible; this file's own location is authoritative.
_scripts_src = str(pathlib.Path(__file__).resolve().parent / "src")
_added = _scripts_src not in sys.path
if _added:
    sys.path.insert(0, _scripts_src)

from sgate.commands.base import run  # noqa: E402

if _added:
    sys.path.remove(_scripts_src)

ROOT = pathlib.Path(__file__).resolve().parent.parent

#: The record of the measurements: the run the page prices.
RECORD_FILE = 'config/docs/gate-benchmarks.json'

#: The workflow the record describes.
WORKFLOW_FILE = '.github/workflows/ci.yml'

#: A matrix reference in a job's display name: `${{ matrix.variant }}`.
_MATRIX_REF = re.compile(r'\$\{\{\s*matrix\.([A-Za-z0-9_-]+)\s*\}\}')

CLEAN = 0
VALIDATION_FAILURE = 1
IO_ERROR = 2


def recorded_jobs(text: str) -> list[str]:
    """The job names the recorded run names.

    The record is `config/docs/gate-benchmarks.json`; the CI block holds the
    jobs of one run and what each took. A baseline that carries no CI block has
    recorded no CI run, and there is nothing to check.
    """
    try:
        data = json.loads(text)
    except json.JSONDecodeError as error:
        raise ValueError(f'the record is not readable JSON: {error}') from error
    ci = data.get('ci')
    if not isinstance(ci, dict):
        return []
    jobs = ci.get('jobs')
    if not isinstance(jobs, dict):
        return []
    return sorted(str(name) for name in jobs)


def _legs(matrix: object) -> list[dict[str, object]]:
    """The legs of a job's matrix.

    GitHub's matrix is axes plus `include` entries; the `include` entries of
    this repository's `android` job carry the whole leg (`variant`, `flags`,
    `tasks`). Both shapes are read: the axes give the combinations, and every
    `include` entry is a leg of its own.
    """
    if not isinstance(matrix, dict):
        return [{}]
    axes = {
        key: value
        for key, value in matrix.items()
        if key != 'include' and isinstance(value, list)
    }
    legs: list[dict[str, object]] = []
    if axes:
        keys = sorted(axes)
        for combination in itertools.product(*(axes[key] for key in keys)):
            legs.append(dict(zip(keys, combination)))
    include = matrix.get('include')
    if isinstance(include, list):
        for entry in include:
            if isinstance(entry, dict):
                legs.append(dict(entry))
    return legs or [{}]


def _expand(template: str, matrix: object) -> list[str]:
    """The display names one job produces, one per matrix leg.

    A leg whose placeholders do not all resolve produces no name: the runner
    would not show `Android (${{ matrix.variant }})` either, and a name the
    runner never prints is not a name the record can hold.
    """
    names: list[str] = []
    for leg in _legs(matrix):
        missing = [
            key for key in _MATRIX_REF.findall(template) if key not in leg
        ]
        if missing:
            continue
        name = _MATRIX_REF.sub(lambda match: str(leg[match.group(1)]), template)
        names.append(name)
    return names


def declared_jobs(text: str) -> set[str]:
    """Every job name the workflow declares.

    A job's display name is its `name:`, or its own key when it declares none —
    the runner shows the key in that case (`changes`, `adr-gate`).
    """
    try:
        document = yaml.safe_load(text)
    except yaml.YAMLError as error:
        raise ValueError(f'the workflow is not readable YAML: {error}') from error
    if not isinstance(document, dict):
        raise ValueError('the workflow is not a mapping')
    jobs = document.get('jobs')
    if not isinstance(jobs, dict):
        raise ValueError('the workflow declares no jobs')
    names: set[str] = set()
    for key, job in jobs.items():
        if not isinstance(job, dict):
            continue
        template = str(job.get('name') or key)
        strategy = job.get('strategy')
        matrix = strategy.get('matrix') if isinstance(strategy, dict) else None
        names.update(_expand(template, matrix))
    return names


def findings(recorded: list[str], declared: set[str]) -> list[str]:
    """The recorded jobs the workflow no longer declares."""
    return [name for name in recorded if name not in declared]


@click.command()
@click.option('--quiet', is_flag=True)
@click.option(
    '--root',
    default=None,
    help='the tree to check (default: the repository this script lives in)',
)
@click.pass_context
def cli(ctx: click.Context, quiet: bool, root: str | None) -> None:
    tree = pathlib.Path(root).resolve() if root else ROOT
    record_path = tree / RECORD_FILE
    workflow_path = tree / WORKFLOW_FILE
    for path in (record_path, workflow_path):
        if not path.is_file():
            click.echo(f'ERROR: {path} is not there — the gate cannot read its input')
            raise ctx.exit(IO_ERROR)

    try:
        recorded = recorded_jobs(record_path.read_text(encoding='utf-8'))
        declared = declared_jobs(workflow_path.read_text(encoding='utf-8'))
    except (OSError, ValueError) as error:
        click.echo(f'ERROR: {error}')
        raise ctx.exit(IO_ERROR) from error

    if not recorded:
        if not quiet:
            click.echo(
                f'check-recorded-ci-jobs: {RECORD_FILE} carries no CI record — '
                f'nothing to check'
            )
        raise ctx.exit(CLEAN)

    missing = findings(recorded, declared)

    for name in missing:
        click.echo(
            f'ERROR: the record names a job the workflow does not declare: {name!r} — '
            f'the record prices a loop that no longer exists'
        )

    if missing:
        click.echo('')
        click.echo(
            "check-recorded-ci-jobs: the record is stale when the workflow's jobs "
            'change, and nothing else says so. A job renamed, added or removed turns '
            'the published price into a price of a loop that no longer exists.'
        )
        click.echo(
            '  fix with — PYTHONPATH=scripts/src python3 -m sgate gate-costs --record-ci'
        )
        raise ctx.exit(VALIDATION_FAILURE)

    if not quiet:
        click.echo(
            f'check-recorded-ci-jobs: OK — {len(recorded)} recorded jobs are still '
            f'jobs the workflow declares'
        )
    raise ctx.exit(CLEAN)


if __name__ == '__main__':
    raise SystemExit(run(cli))
