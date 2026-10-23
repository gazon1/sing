#!/usr/bin/env python3
"""check-coverage-timeout.py — the coverage job's timeout covers the measured cost.

What went wrong
---------------
The CI workflow sets the JVM tier's timeout as a literal number:

    .github/workflows/ci.yml
      tests:
        timeout-minutes: 75

and the cost of the instrumented run that timeout has to cover is measured
and published:

    config/docs/coverage-cost.properties
      fast.ms=2245000
      full.ms=2003000

Nothing connects the two. The 75 was written by hand next to a comment that
says it is measured, and a re-measurement that doubled the instrumented cost
would leave the 75 exactly where it is: the claim "the timeout is set from the
measurement" would be a comment rather than a control. ADR
`docs/decisions/2026-10-23-a-population-is-a-name.md` (D5) prices the run and
says the price exists "so that nobody sets a 15-minute timeout on a 37-minute
task" — and the only reader of the price was the build, which prints it.

The rule
--------
The job that runs `:koverVerify` declares a `timeout-minutes` that covers the
measured instrumented cost multiplied by the runner margin.

The measured cost to plan against is the LARGER of the populations — the cost
file says so in words ("The number to plan against is the larger of the two"),
and the two populations run in the same job: the pull request measures `fast`,
the nightly measures `fast,slow`.

The margin, and where it comes from
-----------------------------------
The measurement was taken on the development host: 16 vCPU, warm Gradle cache
(`config/docs/coverage-cost.properties`, the `machine=` line). The CI runner
has 4 vCPU — the same file says so ("A CI runner has 4 vCPU: the same run
there is slower"), and the commit that set the timeout says it in words
("this runner has 4 vCPUs instead of 16", 3888d625). A run that was entirely
CPU-bound would therefore cost 16/4 = 4 times the measurement on the runner:
4 x 2245000 ms = 8980000 ms, about 150 minutes.

The tree publishes no measurement of how much of the run is CPU-parallel.
Gradle configuration, JVM startup and the Kover report aggregation do not
scale with the core count, so the 4x figure is an upper bound and not a
measurement; a gate that demanded 150 minutes would demand a timeout the
repository has never run.

The margin used here is therefore a CHOSEN MARGIN of 2x, written down as a
constant so that a reader does not have to guess it:

  - it is the smallest whole-number multiple of the measured cost that covers
    the slower runner for the parallel part of the run; and
  - it is the multiple the repository's own timeout already embodies: 75 min =
    4500000 ms against 2245000 ms measured is 2.0045x — the 75-minute timeout
    IS the measurement plus a factor of two (plus ten seconds).

So the requirement is `timeout-minutes >= ceil(measured_ms * 2 / 60000)`,
which is 75 for the current measurement: the gate passes on the current tree
exactly where the hand-written timeout is. A re-measurement that doubled the
instrumented cost (4490000 ms) would need 150 minutes, and the 75-minute
timeout would fail THIS gate instead of the run.

What this does not do
---------------------
It does not run Gradle, and it does not check the rest of the job. The
`timeout-minutes` of the JVM tier has to cover the uninstrumented test run
and detekt as well; the cost file publishes only the instrumented coverage
run, so the margin is what covers the rest.

How the CI timeout is found
---------------------------
The workflow has several `timeout-minutes:` values (the static job, the meta
job, the JVM tier, the Android tier and the small gates). The one that
matters is the `timeout-minutes` of the JOB whose steps run `:koverVerify`
(`./gradlew :koverVerify -Ptest.population="$TEST_POPULATION"`), NOT the
first one in the file.

The job is found by parsing the workflow as YAML (PyYAML is a declared
dependency of the tooling venv, `scripts/src/pyproject.toml`): the job whose
steps' `run` text contains `:koverVerify` as a Gradle task. The sibling task
`:detekt-rules:koverVerify` is a different task and does not match. When no
YAML parser is available the file is read as text instead: the job blocks are
the blocks at indent 2 under `jobs:`, and the block that contains the
`:koverVerify` step is the one whose timeout is read. Either way the finding
names the file and the line.

Usage:
    python3 scripts/check-coverage-timeout.py            # report
    python3 scripts/check-coverage-timeout.py --quiet
    python3 scripts/check-coverage-timeout.py --root DIR  # another tree

Exit codes:
    0 — the timeout covers the measured cost
    1 — a finding
    3 — the tree could not be read (a configuration error)
    5 — an internal error
"""

from __future__ import annotations

import json
import math
import pathlib
import re
import sys

import click
from sgate.commands.base import (  # noqa: PLC0415 — scripts are not a package
    CLEAN,
    CONFIG_ERROR,
    INTERNAL_ERROR,
    VALIDATION_FAILURE,
    run,
)

ROOT = pathlib.Path(__file__).resolve().parent.parent

#: The measured cost of the instrumented coverage run, per population.
COST_FILE = "config/docs/coverage-cost.properties"

#: The CI workflow that carries the coverage job.
WORKFLOW = ".github/workflows/ci.yml"

#: The CHOSEN margin between the measured cost and the CI timeout.
#:
#: See the module docstring: the measurement is from a 16 vCPU host and the CI
#: runner has 4 vCPU, so the runner's run is slower; the tree publishes no
#: measurement of the CPU-parallel fraction, so 2x is a chosen margin, and it
#: is the margin the repository's own 75-minute timeout already embodies
#: (4500000 ms / 2245000 ms = 2.0045).
RUNNER_MARGIN = 2

#: The Gradle task whose job carries the timeout.
KOVER_TASK = ":koverVerify"

#: `:koverVerify` as a Gradle task — not `:detekt-rules:koverVerify`.
_KOVER_TASK_RE = re.compile(r"(?<![\w:.\-]):koverVerify(?![\w\-])")

#: A job header under `jobs:` — two spaces of indent, then the job id.
_JOB_HEADER_RE = re.compile(r"^([A-Za-z0-9_.\-]+):\s*(.*)$")

#: `timeout-minutes: 75` inside a job block.
_TIMEOUT_RE = re.compile(r"^\s*timeout-minutes:\s*(\d+)\s*$")

#: `jobs:` at the top level of the workflow.
_JOBS_KEY_RE = re.compile(r"^jobs:\s*$")


class CoverageJob:
    """The job that runs `:koverVerify`, and the timeout it declares."""

    def __init__(
        self,
        job_id: str | None,
        timeout_minutes: int | None,
        timeout_line: int | None,
        jobs_line: int,
    ) -> None:
        self.job_id = job_id
        self.timeout_minutes = timeout_minutes
        self.timeout_line = timeout_line
        self.jobs_line = jobs_line


def _human_millis(millis: int) -> str:
    """The measured cost as a sentence a reader can compare with a timeout."""
    minutes, seconds = divmod(millis // 1000, 60)
    return f"{minutes} min {seconds} s"


def _properties(text: str) -> dict[str, tuple[str, int]]:
    """The properties of a `.properties` file, with the line each came from.

    A `#` starts a comment anywhere on the line — the same rule
    `java.util.Properties` applies, and the same rule the build reads the
    file with (`build.gradle.kts`), so the gate and the build see one file.
    """
    found: dict[str, tuple[str, int]] = {}
    for number, line in enumerate(text.splitlines(), start=1):
        if not line.strip():
            continue
        marker = line.find("#")
        if marker >= 0:
            line = line[:marker]
        if "=" not in line:
            continue
        key, _, value = line.partition("=")
        found[key.strip()] = (value.strip(), number)
    return found


def _measured_costs(properties: dict[str, tuple[str, int]]) -> tuple[dict[str, int], list[str]]:
    """The measured cost per population, and the findings about unreadable ones."""
    costs: dict[str, int] = {}
    errors: list[str] = []
    for key, (value, number) in properties.items():
        if not key.endswith(".ms"):
            continue
        try:
            costs[key] = int(value)
        except ValueError:
            errors.append(
                f"{COST_FILE}:{number}: the measured cost '{key}={value}' is not a whole "
                f"number of milliseconds. The timeout is compared against this number, and "
                f"a number the gate cannot read is a number the gate cannot check."
            )
    return costs, errors


def _job_blocks(text: str) -> tuple[dict[str, tuple[int, int]], int]:
    """The job blocks of the workflow: job id -> (first line, last line).

    The jobs are the blocks at indent 2 under `jobs:`. The line of the
    `jobs:` key is returned as well, so that a finding about the workflow as
    a whole can name a line.
    """
    lines = text.splitlines()
    blocks: dict[str, tuple[int, int]] = {}
    jobs_line = 1
    current: str | None = None
    start = 0
    inside = False

    for number, line in enumerate(lines, start=1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        indent = len(line) - len(line.lstrip())
        if not inside:
            if indent == 0 and _JOBS_KEY_RE.match(stripped):
                inside = True
                jobs_line = number
            continue
        if indent == 2:
            match = _JOB_HEADER_RE.match(stripped)
            if match:
                if current is not None:
                    blocks[current] = (start, number - 1)
                current = match.group(1)
                start = number
    if current is not None:
        blocks[current] = (start, len(lines))
    return blocks, jobs_line


def _timeout_in_block(lines: list[str], start: int, end: int) -> tuple[int | None, int | None]:
    """The `timeout-minutes` of a job block: the value and the line it is on."""
    for number in range(start, min(end, len(lines)) + 1):
        match = _TIMEOUT_RE.match(lines[number - 1])
        if match:
            return int(match.group(1)), number
    return None, None


def _yaml_coverage_job(text: str, lines: list[str], blocks: dict[str, tuple[int, int]], jobs_line: int) -> CoverageJob | None:
    """The coverage job, read from the YAML structure of the workflow.

    `None` when the workflow cannot be parsed as YAML — the caller then reads
    the same answer from the text.
    """
    try:
        import yaml  # noqa: PLC0415 — imported late, so a missing parser is one fallback
    except ImportError:
        return None
    try:
        document = yaml.safe_load(text)
    except yaml.YAMLError:
        return None
    if not isinstance(document, dict):
        return None
    jobs = document.get("jobs")
    if not isinstance(jobs, dict):
        return None
    for job_id, job in jobs.items():
        if not isinstance(job, dict):
            continue
        for step in job.get("steps") or []:
            if not isinstance(step, dict):
                continue
            if not _KOVER_TASK_RE.search(str(step.get("run") or "")):
                continue
            timeout = job.get("timeout-minutes")
            timeout_value = timeout if isinstance(timeout, int) else None
            _, timeout_line = _timeout_in_block(lines, *blocks.get(job_id, (1, 1)))
            return CoverageJob(str(job_id), timeout_value, timeout_line, jobs_line)
    return CoverageJob(None, None, None, jobs_line)


def _text_coverage_job(lines: list[str], blocks: dict[str, tuple[int, int]], jobs_line: int) -> CoverageJob:
    """The coverage job, read from the text of the workflow.

    The fallback for a tree without a YAML parser. A job block is the
    coverage job when one of its lines runs the Gradle task — a comment
    that mentions the task is not a step that runs it.
    """
    for job_id, (start, end) in blocks.items():
        for number in range(start, min(end, len(lines)) + 1):
            line = lines[number - 1]
            if line.strip().startswith("#"):
                continue
            if not _KOVER_TASK_RE.search(line):
                continue
            timeout, timeout_line = _timeout_in_block(lines, start, end)
            return CoverageJob(job_id, timeout, timeout_line, jobs_line)
    return CoverageJob(None, None, None, jobs_line)


def _coverage_job(text: str) -> CoverageJob:
    """The job that runs `:koverVerify`, and the timeout it declares."""
    lines = text.splitlines()
    blocks, jobs_line = _job_blocks(text)
    job = _yaml_coverage_job(text, lines, blocks, jobs_line)
    if job is not None:
        return job
    return _text_coverage_job(lines, blocks, jobs_line)


def check(root: pathlib.Path | None = None) -> list[str]:
    """The findings; empty when the tree is correct."""
    tree = pathlib.Path(root).resolve() if root else ROOT
    findings: list[str] = []

    cost_path = tree / COST_FILE
    if not cost_path.is_file():
        return [
            f"{COST_FILE}:1: the measured cost of the instrumented coverage run is missing. "
            f"The coverage job's timeout has to cover a measured number, and without this "
            f"file there is no number to cover."
        ]

    workflow_path = tree / WORKFLOW
    if not workflow_path.is_file():
        return [
            f"{WORKFLOW}:1: the CI workflow is missing, so the coverage job's timeout "
            f"cannot be checked against the measured cost at all."
        ]

    properties = _properties(cost_path.read_text(encoding="utf-8"))
    costs, cost_errors = _measured_costs(properties)
    findings.extend(cost_errors)
    if not costs:
        findings.append(
            f"{COST_FILE}:1: the file holds no '<population>.ms' cost, so the measured cost "
            f"of the instrumented coverage run cannot be read. The timeout needs a number to "
            f"be compared with."
        )
        return findings

    # The larger of the populations: the cost file says the number to plan
    # against is the larger one, and both populations run in the same job.
    population = max(costs, key=lambda key: costs[key])
    measured = costs[population]
    machine = properties.get("machine", ("an unnamed machine", 1))[0]

    text = workflow_path.read_text(encoding="utf-8")
    job = _coverage_job(text)

    if job.job_id is None:
        findings.append(
            f"{WORKFLOW}:{job.jobs_line}: no job in the workflow runs {KOVER_TASK}, so there "
            f"is no coverage job whose timeout could be checked against the measured cost of "
            f"{measured} ms. The step that runs the coverage floors has gone, and the timeout "
            f"that guarded it is no longer guarded by anything."
        )
        return findings

    if job.timeout_minutes is None:
        findings.append(
            f"{WORKFLOW}:{job.timeout_line or job.jobs_line}: the job '{job.job_id}' runs "
            f"{KOVER_TASK} but declares no 'timeout-minutes'. A job with no timeout runs "
            f"until the runner's own limit, and the instrumented run it carries is measured "
            f"at {measured} ms ({_human_millis(measured)}) on {machine}."
        )
        return findings

    required = math.ceil(measured * RUNNER_MARGIN / 60_000)
    if job.timeout_minutes < required:
        findings.append(
            f"{WORKFLOW}:{job.timeout_line}: the coverage job '{job.job_id}' has a "
            f"{job.timeout_minutes}-minute timeout, but the instrumented coverage run is "
            f"measured at {measured} ms ({_human_millis(measured)}) on {machine}, and the "
            f"runner is slower than the measurement host (4 vCPU against 16), so the timeout "
            f"must cover {measured} ms x {RUNNER_MARGIN} = {required} minutes. A timeout "
            f"that fires on a healthy run reports a failure that says nothing about the code."
        )

    return findings


@click.command()
@click.option("--quiet", is_flag=True, help="Print nothing when the gate is clean.")
@click.option(
    "--root",
    default=None,
    help="The tree to check (default: the repository this script lives in).",
)
@click.option("--json", "emit_json", is_flag=True, help="Emit the verdict as JSON.")
@click.pass_context
def cli(ctx: click.Context, quiet: bool, root: str | None, emit_json: bool) -> None:
    """Check that the coverage job's timeout covers the measured coverage cost."""
    tree = pathlib.Path(root).resolve() if root else ROOT
    if not tree.is_dir():
        if emit_json:
            click.echo(
                json.dumps(
                    {
                        "passed": False,
                        "blocking_errors": [f"--root {root} is not a directory."],
                        "warnings": [],
                    }
                )
            )
        else:
            click.echo(f"ERROR: --root {root} is not a directory.")
        raise ctx.exit(CONFIG_ERROR)

    findings = check(tree)

    if emit_json:
        click.echo(
            json.dumps(
                {
                    "passed": not findings,
                    "blocking_errors": findings,
                    "warnings": [],
                },
                ensure_ascii=False,
            )
        )
        raise ctx.exit(CLEAN if not findings else VALIDATION_FAILURE)

    for finding in findings:
        click.echo(f"ERROR: {finding}")
    if findings:
        click.echo("")
        click.echo(
            "check-coverage-timeout: the coverage job's timeout has to cover the measured "
            "cost of the instrumented coverage run — measure it again (sgate gate-costs "
            "--record --coverage) and set the timeout from the measurement."
        )
        raise ctx.exit(VALIDATION_FAILURE)

    if not quiet:
        costs, _ = _measured_costs(
            _properties((tree / COST_FILE).read_text(encoding="utf-8"))
        )
        population = max(costs, key=lambda key: costs[key])
        measured = costs[population]
        job = _coverage_job((tree / WORKFLOW).read_text(encoding="utf-8"))
        required = math.ceil(measured * RUNNER_MARGIN / 60_000)
        click.echo(
            f"check-coverage-timeout: OK — the coverage job '{job.job_id}' has a "
            f"{job.timeout_minutes}-minute timeout, and the measured instrumented run "
            f"({population} = {measured} ms = {_human_millis(measured)}) x {RUNNER_MARGIN} "
            f"needs {required} minutes."
        )
    raise ctx.exit(CLEAN)


def main(argv: list[str] | None = None) -> int:
    """The gate's entry point; the exit code the registry reads."""
    try:
        return run(cli.main, argv)
    except SystemExit:
        raise
    except Exception as exc:  # noqa: BLE001 — an internal error is an exit code, not a traceback
        click.echo(f"check-coverage-timeout: internal error: {exc}", err=True)
        return INTERNAL_ERROR


if __name__ == "__main__":
    raise SystemExit(main())
