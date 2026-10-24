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
that does not expand the matrix would call both of them unknown. An `exclude`
takes a leg away: the runner never shows it, so the gate does not declare it
either.

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
- **A job whose display name the gate cannot compute.** A name that holds an
  expression other than a matrix reference (`Nightly ${{ github.event.schedule }}`)
  is resolved by the runner and recorded resolved, and the gate cannot compute
  it. It does not compare the two — a healthy tree would read as stale. The
  name becomes a PATTERN instead: the literal parts are escaped and the
  expression becomes a wildcard, so only a recorded name that matches it is
  left unproven — one uncomputable job does not silence the rest of the gate.
  The gate prints the names it could not prove and the pattern that matched,
  whatever `--quiet` says.
- **A job whose display name is nothing but an expression** (`name: ${{
  github.sha }}`). Such a name would match every recorded name, and a pattern
  that matches everything identifies nothing: the gate cannot prove that any
  recorded job is gone. That is not a clean tree, it is a tree the gate cannot
  read — so the gate exits with a configuration error and names the job,
  instead of calling everything unproven and reporting a pass.

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

from typing import NamedTuple

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

#: Any expression at all: what is left in a name the gate could not resolve.
_ANY_EXPR = re.compile(r'\$\{\{.*?\}\}')

#: The same expression, capturing — `split` keeps the separator this way, so a
#: name can be cut into the literal parts and the parts the runner resolves.
_ANY_EXPR_CAPTURE = re.compile(r'(\$\{\{.*?\}\})')

CLEAN = 0
VALIDATION_FAILURE = 1
IO_ERROR = 2


class Pattern(NamedTuple):
    """A display name the gate cannot compute, and what it would look like.

    `regex` matches the recorded names that may be this job's resolved name.
    `literal` is the part of the name the gate can read — when it is empty,
    the name is nothing but an expression and the pattern would match every
    recorded name, which identifies nothing.
    """

    regex: 're.Pattern[str]'
    literal: str
    text: str

    def matches(self, name: str) -> bool:
        """Whether this pattern covers the given recorded name."""
        return self.regex.fullmatch(name) is not None

    @property
    def narrowable(self) -> bool:
        """Whether the pattern identifies a job, or matches everything."""
        return bool(self.literal)


class Declared(NamedTuple):
    """What the workflow declares: the names, and the ones it cannot name."""

    names: set[str]
    patterns: list[Pattern]


def recorded_jobs(text: str) -> list[str]:
    """The job names the recorded run names.

    The record is `config/docs/gate-benchmarks.json`; the CI block holds the
    jobs of one run and what each took. A baseline that carries no CI block has
    recorded no CI run, and there is nothing to check.

    A block that is there and malformed is a different thing: the record says
    it holds CI jobs and does not hold jobs the gate can read. That is a
    configuration error, not an empty record — a gate that reads a broken
    record as "nothing to check" is green for the wrong reason.
    """
    try:
        data = json.loads(text)
    except json.JSONDecodeError as error:
        raise ValueError(f'the record is not readable JSON: {error}') from error
    if not isinstance(data, dict):
        raise ValueError('the record is not a mapping')
    if 'ci' not in data:
        return []
    ci = data.get('ci')
    if not isinstance(ci, dict):
        raise ValueError('the record carries a CI block that is not a mapping')
    if 'jobs' not in ci:
        return []
    jobs = ci.get('jobs')
    if not isinstance(jobs, dict):
        raise ValueError('the record carries a CI block whose jobs are not a mapping')
    return sorted(str(name) for name in jobs)


def _legs(matrix: object) -> list[dict[str, object]]:
    """The legs of a job's matrix.

    GitHub's matrix is axes plus `include` entries; the `include` entries of
    this repository's `android` job carry the whole leg (`variant`, `flags`,
    `tasks`). Both shapes are read: the axes give the combinations, and every
    `include` entry is a leg of its own.

    `exclude` takes axis-generated legs away: the runner never shows an
    excluded combination, so a gate that declares it would accept a record of
    a job the workflow does not run any more — the exact false negative the
    gate exists to prevent.

    `exclude` does NOT take an `include` entry away. GitHub processes every
    `exclude` against the axis combinations first and every `include` after
    it, so an include entry is a leg whatever the excludes say. A gate that
    excluded an include entry would report a job the runner still shows as
    gone — a false positive on a healthy tree, in the matrix style this
    repository uses.
    """
    if not isinstance(matrix, dict):
        return [{}]
    axes = {
        key: value
        for key, value in matrix.items()
        if key not in ('include', 'exclude') and isinstance(value, list)
    }
    legs: list[dict[str, object]] = []
    if axes:
        keys = sorted(axes)
        exclude = matrix.get('exclude')
        excludes = [entry for entry in exclude if isinstance(entry, dict)] if isinstance(exclude, list) else []
        for combination in itertools.product(*(axes[key] for key in keys)):
            leg = dict(zip(keys, combination))
            if any(_excluded(leg, entry) for entry in excludes):
                continue
            legs.append(leg)
    include = matrix.get('include')
    include_entries = [
        entry for entry in include if isinstance(entry, dict)
    ] if isinstance(include, list) else []
    legs.extend(dict(entry) for entry in include_entries)
    if not axes and not include_entries:
        # A matrix that says nothing still runs the job once.
        return [{}]
    # Every axis combination excluded means the runner runs no leg of this job
    # at all — and a job that runs nothing declares no display name.
    return legs


def _excluded(leg: dict[str, object], entry: object) -> bool:
    """Whether an `exclude` entry takes this leg away.

    An exclude entry names the axis values it removes: it matches a leg when
    every key it names is in the leg and carries the same value.
    """
    if not isinstance(entry, dict) or not entry:
        return False
    return all(key in leg and leg[key] == value for key, value in entry.items())


def _expand(template: str, matrix: object) -> tuple[list[str], list[Pattern]]:
    """The display names one job produces, and the patterns of the ones it cannot.

    A leg whose placeholders do not all resolve produces no name: the record
    holds what the runner showed, and a name the runner cannot show is not a
    name the record can hold.

    A name that still holds an expression the gate cannot resolve is a name
    the gate cannot compute — the runner resolves it, the record holds the
    resolved text, and comparing the two would report a healthy tree as
    stale. Such a name becomes a PATTERN instead: the expression becomes a
    wildcard, and only a recorded name that matches it is ambiguous. Every
    other recorded name can still be proven gone — one uncomputable job must
    not silence the whole gate.
    """
    names: list[str] = []
    patterns: list[Pattern] = []
    for leg in _legs(matrix):
        missing = [
            key for key in _MATRIX_REF.findall(template) if key not in leg
        ]
        if missing:
            continue
        name = _MATRIX_REF.sub(lambda match: str(leg[match.group(1)]), template)
        if _ANY_EXPR.search(name):
            patterns.append(_pattern(name))
            continue
        names.append(name)
    return names, patterns


def _pattern(name: str) -> 'Pattern':
    """The pattern of a display name the gate cannot compute.

    The literal parts of the name are ESCAPED and the expression becomes
    `.*`: a job called `Coverage [full] ${{ github.sha }}` must match the
    recorded name `Coverage [full] abc123`, and a glob would read the
    brackets as a character class and call a healthy tree stale.

    A name that is nothing but an expression has no literal part at all. Its
    pattern would match every recorded name, and a pattern that matches
    everything identifies nothing — so the pattern says so, and the gate
    refuses to pretend it can prove anything while that job is in the
    workflow.
    """
    segments = _ANY_EXPR_CAPTURE.split(name)
    literal = ''.join(
        segment for segment in segments if not _ANY_EXPR.fullmatch(segment)
    )
    parts = [
        '.*' if _ANY_EXPR.fullmatch(segment) else re.escape(segment)
        for segment in segments
        if segment
    ]
    return Pattern(regex=re.compile(''.join(parts)), literal=literal, text=name)


def declared_jobs(text: str) -> 'Declared':
    """Every job name the workflow declares, and the patterns it cannot name.

    A job's display name is its `name:`, or its own key when it declares none —
    the runner shows the key in that case (`changes`, `adr-gate`).

    The patterns belong to the jobs whose display name the gate cannot compute
    (a name that holds an expression other than a matrix reference). A
    recorded name that matches one of them may be that job's resolved name, so
    the gate cannot prove it gone — but a recorded name that matches none of
    them can still be proven gone.
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
    patterns: list[Pattern] = []
    for key, job in jobs.items():
        if not isinstance(job, dict):
            continue
        template = str(job.get('name') or key)
        strategy = job.get('strategy')
        matrix = strategy.get('matrix') if isinstance(strategy, dict) else None
        expanded, uncomputable = _expand(template, matrix)
        names.update(expanded)
        patterns.extend(uncomputable)
    return Declared(names=names, patterns=patterns)


def ambiguous_names(recorded: list[str], declared: 'Declared') -> list[tuple[str, str]]:
    """The recorded names that match a job whose display name is unknown.

    These are the names the gate cannot prove gone: the workflow declares a
    job whose display name it cannot compute, and the record may hold that
    job's resolved name. Each pair is the recorded name and the pattern it
    matched — the pattern is what tells the reader WHICH job is in the way.
    """
    ambiguous: list[tuple[str, str]] = []
    for name in recorded:
        if name in declared.names:
            continue
        for pattern in declared.patterns:
            if pattern.matches(name):
                ambiguous.append((name, pattern.text))
                break
    return ambiguous


def findings(recorded: list[str], declared: 'Declared') -> list[str]:
    """The recorded jobs the workflow no longer declares.

    A recorded name that matches a job whose display name the gate cannot
    compute is not a finding — the gate cannot prove it gone. Every other
    unmatched recorded name is: one uncomputable job must not silence the
    whole gate.
    """
    ambiguous = {name for name, _ in ambiguous_names(recorded, declared)}
    return [name for name in recorded if name not in declared.names and name not in ambiguous]


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

    unnarrowable = [pattern for pattern in declared.patterns if not pattern.narrowable]
    if unnarrowable:
        # A name that is nothing but an expression matches every recorded name,
        # so the gate cannot prove that ANY of them is gone. That is not a clean
        # tree — it is a tree the gate cannot read — and it is a configuration
        # error, not a finding.
        click.echo(
            'ERROR: the workflow declares a job whose display name is an expression '
            'with no literal text '
            f'({", ".join(pattern.text for pattern in unnarrowable)}) — the gate '
            'cannot prove that any recorded job is gone while it is there. Give the '
            'job a name with literal text.'
        )
        raise ctx.exit(IO_ERROR)

    missing = findings(recorded, declared)
    ambiguous = ambiguous_names(recorded, declared)

    if ambiguous:
        # Printed whatever `--quiet` says: this is a limit of what the gate can
        # prove, and a limit nobody reads is a limit nobody knows about. The
        # pattern is the one that matched, so the reader knows WHICH job is in
        # the way.
        matched = sorted({pattern for _, pattern in ambiguous})
        names = sorted({name for name, _ in ambiguous})
        click.echo(
            f'check-recorded-ci-jobs: {len(names)} recorded job(s) match a job '
            f'whose display name the gate cannot compute (matched: '
            f'{", ".join(matched)}) — the gate cannot prove they are gone: '
            f'{", ".join(names)}'
        )

    for name in missing:
        click.echo(
            f'ERROR: the record names a job the workflow does not declare: {name!r} — '
            f'the record prices a loop that no longer exists'
        )

    if missing:
        click.echo('')
        click.echo(
            "check-recorded-ci-jobs: the record is stale when the workflow's jobs "
            'change, and nothing else says so. A job renamed or removed turns '
            'the published price into a price of a loop that no longer exists.'
        )
        click.echo(
            '  fix with — PYTHONPATH=scripts/src python3 -m sgate gate-costs --record-ci'
        )
        raise ctx.exit(VALIDATION_FAILURE)

    if not quiet:
        click.echo(
            f'check-recorded-ci-jobs: OK — {len(recorded) - len(ambiguous)} recorded '
            f'jobs are still jobs the workflow declares'
        )
    raise ctx.exit(CLEAN)


if __name__ == '__main__':
    raise SystemExit(run(cli))
