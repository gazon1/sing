"""sgate.commands.gate_costs — what the gates cost, and what that cost says.

Why this exists
---------------
The local verification loop grew without anyone saying so. A wave added an Android
host test, another added a module to the detekt sweep, a third added a gate to the
static suite — and each of those is a few minutes on a machine that was already slow.
The cost of the loop is therefore the sum of decisions nobody priced, and the first
person to notice was the person waiting for it.

The infrastructure for measuring it was written and never connected:
``config/docs/gate-benchmarks.json`` existed, empty, and ``sgate check-benchmarks``
existed to read it — a command whose baseline was empty could not pass for any gate
name, so it could only ever fail. This command replaces it with something that works:
the measurement comes from the run that already happened, and the baseline is a
committed record of what the loop cost.

The three facts
---------------
*Measurement* — ``sgate run-all`` writes the duration of every gate it ran, and
``check.sh`` writes the duration of every Gradle step it ran. Both are the runs that
happened anyway; nothing is executed twice to be timed.

*Baseline* — ``config/docs/gate-benchmarks.json`` holds the recorded samples and the
environment they were measured in. It is committed, so the cost of the loop is a fact
in the repository rather than a memory in someone's head.

*Publication* — ``docs/testing/gate-costs.md`` carries the baseline as a generated
block, and the gate "the published gate costs are current" says the two agree.

The honest limit
----------------
A duration measured on one machine says nothing about another: a laptop with a cold
Gradle home and a CI runner are not the same machine. So the baseline records the
environment it was measured in, and the comparison refuses to draw a conclusion when
the environment has changed — it says "not comparable" rather than "3× slower". The
gate that runs in CI checks the two committed files against each other, which is a
question a CI runner can answer honestly.

Exit codes:
  0 = clean (report produced, or --check passed)
  1 = --check: the published block is not what the baseline says
  2 = I/O error (the measurement or the baseline could not be read)
  3 = configuration error (the published document could not be written)
"""
from __future__ import annotations

import json
import os
import platform
import shutil
import statistics
import subprocess
import time
from datetime import datetime
from pathlib import Path

import click

import sgate
from sgate.commands.base import CLEAN, CONFIG_ERROR, IO_ERROR, VALIDATION_FAILURE, register
from sgate.log import logger

#: The repository whose CI runs are measured. The command runs where the code does.
CI_REPOSITORY = "gazon1/sing"

#: The version of the baseline and of the measurement file this command reads.
SCHEMA_VERSION = 1

#: How much slower a measurement may be before it is called a regression.
#:
#: Three times, and not a percentage: a gate that takes 40 ms and one that takes 40 s
#: need different tolerances, and a multiplicative bound treats them alike. The number
#: is inherited from the command this one replaces, and it is deliberately loose — this
#: reports "look at this", not "the build is broken".
REGRESSION_FACTOR = 3

#: How many samples the baseline keeps per entry. Enough to smooth a noisy run, few
#: enough that the committed file stays readable.
MAX_SAMPLES = 10

#: The markers of the generated block inside the published document.
BEGIN_MARKER = "<!-- BEGIN GENERATED: sgate gate-costs -->"
END_MARKER = "<!-- END GENERATED: sgate gate-costs -->"


# ── The environment a measurement belongs to ─────────────────────────────────────


def environment() -> dict[str, object]:
    """What the measurement was taken on.

    A duration without its machine is not a fact about the loop; it is a fact about
    one machine's loop. The fields are the cheap, stable ones: the operating system,
    the number of processors, the Python that ran the gates, and the JDK the Gradle
    steps used. A changed field means the baseline is not comparable, and the report
    says so instead of pretending otherwise.
    """
    return {
        "os": platform.system(),
        "cpus": os.cpu_count() or 0,
        "python": platform.python_version(),
        "javaHome": os.environ.get("JAVA_HOME", ""),
    }


def host_load() -> dict[str, object]:
    """How busy the machine was when the measurement was taken.

    The cost of a gate is not a property of the gate alone: on a host that
    already runs eight Gradle daemons, the same gate costs several times
    more. A duration without the load it was measured under is a number
    nobody can compare with anything.

    This is deliberately *not* part of the environment: the environment says
    whether two measurements are comparable at all, and the load changes
    every minute — a comparison that included it would never hold.
    """
    try:
        one, five, fifteen = os.getloadavg()
    except (OSError, AttributeError):  # not every platform has it
        return {}
    return {
        "load1m": round(one, 1),
        "load5m": round(five, 1),
        "load15m": round(fifteen, 1),
        "cpus": os.cpu_count() or 0,
    }


def _describe_load(load: dict[str, object]) -> str:
    """The load, in a sentence a reader can compare with another one."""
    one = load.get("load1m")
    cpus = load.get("cpus")
    if not isinstance(one, (int, float)):
        return ""
    per_cpu = f" on {cpus} CPUs" if isinstance(cpus, int) and cpus else ""
    return f"the host was at load {one}{per_cpu}"


def _same_environment(left: dict[str, object], right: dict[str, object]) -> bool:
    """Whether two measurements were taken on the same machine."""
    return left == right


# ── Reading and writing the pieces ───────────────────────────────────────────────


def read_measurement() -> dict[str, object]:
    """The cycle that was measured: the gates that ran and the steps that ran.

    Two files, two producers: ``run-all`` writes the gates, ``check.sh`` writes the
    Gradle steps. Neither is asked to know about the other — one is the static suite,
    the other is the build — and this command is the only place that puts them side by
    side.
    """
    gates = _read_gate_run()
    steps = _read_steps()
    return {
        "schemaVersion": SCHEMA_VERSION,
        "recordedAt": int(time.time() * 1000),
        "environment": environment(),
        "host": host_load(),
        "gates": gates,
        "steps": steps,
    }


def _read_gate_run() -> dict[str, int]:
    """The per-gate durations ``sgate run-all`` recorded, in milliseconds."""
    path = sgate.repo.GATE_RUN_JSON
    if not path.is_file():
        return {}
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        logger.warning(f"could not read the gate run measurement at {path}: {exc}")
        return {}
    if not isinstance(document, dict):
        return {}
    gates = document.get("gates")
    if not isinstance(gates, dict):
        return {}
    measured: dict[str, int] = {}
    for name, value in gates.items():
        if isinstance(name, str) and isinstance(value, (int, float)):
            measured[name] = int(value)
    return measured


def _read_steps() -> dict[str, int]:
    """The per-step durations ``check.sh`` recorded, in milliseconds.

    The format is one ``name<TAB>milliseconds`` line per step. It is written by a
    shell script that measures a Gradle invocation, and a shell script should not have
    to know JSON to hand over a number.
    """
    path = sgate.repo.CYCLE_STEPS_TXT
    if not path.is_file():
        return {}
    measured: dict[str, int] = {}
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        logger.warning(f"could not read the cycle steps at {path}: {exc}")
        return {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        name, _, value = line.partition("\t")
        name = name.strip()
        try:
            milliseconds = int(value.strip())
        except ValueError:
            continue
        if name and milliseconds >= 0:
            measured[name] = milliseconds
    return measured


def read_baseline(strict: bool = True) -> dict[str, object]:
    """The committed baseline: what the loop cost, and where it was measured.

    A malformed baseline is a real problem — the gate and the recorder both read it —
    so by default it stops the command. The report path asks for it leniently: a
    report that cannot read the baseline still has something to say about the cycle
    it just measured.
    """
    path = sgate.repo.GATE_BENCHMARKS_JSON
    if not path.is_file():
        return {}
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        if strict:
            logger.error(f"could not read the baseline at {path}: {exc}")
            raise SystemExit(IO_ERROR) from exc
        logger.warning(f"could not read the baseline at {path}: {exc}")
        return {}
    return document if isinstance(document, dict) else {}


def write_baseline(baseline: dict[str, object]) -> None:
    """Write the baseline, sorted, so a diff of it is readable."""
    path = sgate.repo.GATE_BENCHMARKS_JSON
    ordered = {
        "schemaVersion": SCHEMA_VERSION,
        "recordedAt": baseline.get("recordedAt", ""),
        "environment": baseline.get("environment", {}),
        "gates": _sorted_entries(baseline.get("gates")),
        "steps": _sorted_entries(baseline.get("steps")),
    }
    if baseline.get("ci"):
        # The CI side is a record, not a baseline: it travels with the file, and a
        # re-recording of the local loop must not throw it away.
        ordered["ci"] = baseline["ci"]
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(ordered, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    except OSError as exc:
        logger.error(f"could not write the baseline at {path}: {exc}")
        raise SystemExit(IO_ERROR) from exc


def _sorted_entries(entries: object) -> dict[str, object]:
    """The baseline's entries, sorted by name."""
    if not isinstance(entries, dict):
        return {}
    return {name: entries[name] for name in sorted(entries)}


def _entry(samples: list[int]) -> dict[str, object]:
    """One baseline entry: the samples and their median."""
    return {
        "samples": samples[-MAX_SAMPLES:],
        "medianMs": int(statistics.median(samples)) if samples else 0,
    }


# ── The published document ──────────────────────────────────────────────────────


def render_document(baseline: dict[str, object]) -> str:
    """The published document: prose, and the generated block with the numbers.

    The block is regenerated from the baseline, and the gate says the committed
    document is what the baseline says. A hand-edited number is therefore a failing
    gate rather than a fact nobody can trace.
    """
    path = sgate.repo.GATE_COSTS_MD
    header = f"""# What the verification loop costs

The local loop is the sum of decisions nobody priced. This page publishes what it
costs, so that the next decision is priced too.

The numbers come from the runs that happened anyway: `sgate run-all` times every gate
it runs, and `check.sh` times every Gradle step it runs. Nothing is executed twice to
be measured, and the baseline in `config/docs/gate-benchmarks.json` records the
machine the numbers came from — a duration measured on one machine says nothing about
another, so the comparison refuses to conclude anything when the environment changed.

Two caveats, so the numbers are read as what they are: they are **one run on one
machine**, and a warm Gradle cache makes them lower than a cold one, a busy machine
higher than an idle one. Re-record after a cold, quiet run if that is the number you
want to compare against.

To refresh the numbers:

    ./check.sh                       # measure the loop
    sgate gate-costs --record        # write the baseline and re-render this page

The block below is generated. A hand-edited number is a failing gate, not a fact.

{BEGIN_MARKER}
"""
    footer = f"""{END_MARKER}
"""
    return header + _render_block(baseline) + footer


def _render_block(baseline: dict[str, object]) -> str:
    """The generated block: the medians, per gate and per step."""
    lines: list[str] = []
    env = baseline.get("environment")
    recorded = baseline.get("recordedAt")
    lines.append("")
    if isinstance(env, dict) and env:
        described = ", ".join(f"{key}={value}" for key, value in sorted(env.items()) if value != "")
        lines.append(f"Recorded on {recorded or 'an unrecorded date'} — {described}.")
        lines.append("")
    load = baseline.get("host")
    if isinstance(load, dict) and load:
        described_load = _describe_load(load)
        if described_load:
            lines.append(f"Measured while {described_load} — the cost of a gate is a")
            lines.append("property of the gate and the host together, and this is the host.")
            lines.append("")
    lines.append("| What | Median |")
    lines.append("|------|--------|")
    for kind, title in (("steps", "Step"), ("gates", "Gate")):
        entries = baseline.get(kind)
        if not isinstance(entries, dict) or not entries:
            continue
        for name in sorted(entries):
            entry = entries[name]
            median = entry.get("medianMs") if isinstance(entry, dict) else None
            lines.append(f"| {title}: {name} | {_format_ms(median)} |")
    lines.append("")
    lines.extend(_render_coverage_block())
    lines.extend(_render_ci_block(baseline))
    return "\n".join(lines)


def _read_coverage_costs() -> dict[str, str]:
    """The measured cost of the instrumented coverage run, per population.

    Its own file, and not a row of the table above: the instrumented run is
    the one the Kover agent multiplies, and its cost decides whether a timeout
    is adequate. The build reads the same file, so the number the build
    prints and the number published here cannot drift apart.
    """
    path = sgate.repo.COVERAGE_COST_PROPERTIES
    costs: dict[str, str] = {}
    if not path.is_file():
        return costs
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        costs[key.strip()] = value.strip()
    return costs


def _render_coverage_block() -> list[str]:
    """The instrumented coverage run: what it costs, per population."""
    costs = _read_coverage_costs()
    if not costs:
        return []
    lines = ["### The instrumented coverage run", ""]
    measured = costs.get("measuredAt")
    machine = costs.get("machine")
    if measured or machine:
        lines.append(
            f"Measured on {measured or 'an unrecorded date'}"
            + (f" — {machine}" if machine else "")
            + "."
        )
        lines.append("")
    lines.append("| Population | Cost |")
    lines.append("|---|---|")
    for population in ("fast", "full"):
        millis = costs.get(f"{population}.ms")
        if millis is None:
            continue
        lines.append(f"| {population} | {_format_ms(int(millis))} |")
    lines.append("")
    lines.append(
        "The uninstrumented `:shared:jvmTest` in the table above is a different "
        "run: the Kover agent multiplies the cost of every test, and this is the "
        "number a timeout has to be set from."
    )
    lines.append("")
    return lines


def _render_ci_block(baseline: dict[str, object]) -> list[str]:
    """The CI side of the loop: the last run's jobs and what each took.

    Not a median and not a baseline: a hosted runner is not this machine, and the
    comparison this file refuses to make across machines is the same one. So the
    numbers are published as a record of one run, with the run it came from.
    """
    ci = baseline.get("ci")
    if not isinstance(ci, dict):
        return []
    jobs = ci.get("jobs")
    if not isinstance(jobs, dict) or not jobs:
        return []
    lines = ["### The last CI run", ""]
    created = ci.get("createdAt")
    url = ci.get("url")
    if created or url:
        where = f" — {created}" if created else ""
        if url:
            where += f" — {url}"
        lines.append(f"The CI loop is the one that blocks merges, so it is priced too{where}.")
        lines.append("")
    lines.append("| Job | Duration |")
    lines.append("|------|----------|")
    for name in sorted(jobs):
        lines.append(f"| {name} | {_format_ms(jobs[name])} |")
    lines.append("")
    return lines


def _format_ms(milliseconds: object) -> str:
    """A duration in the unit a reader thinks in."""
    if not isinstance(milliseconds, (int, float)) or milliseconds <= 0:
        return "—"
    if milliseconds < 1000:
        return f"{int(milliseconds)} ms"
    seconds = milliseconds / 1000
    if seconds < 60:
        return f"{seconds:.1f} s"
    minutes, rest = divmod(int(seconds), 60)
    return f"{minutes} min {rest:02d} s"


def write_document(text: str) -> None:
    """Write the published document."""
    path = sgate.repo.GATE_COSTS_MD
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
    except OSError as exc:
        logger.error(f"could not write {path}: {exc}")
        raise SystemExit(CONFIG_ERROR) from exc


# ── The comparison ───────────────────────────────────────────────────────────────


def compare(measurement: dict[str, object], baseline: dict[str, object]) -> dict[str, object]:
    """What the measurement says against the baseline.

    Three verdicts, and the third is the one that matters: *not comparable*. A
    measurement from another machine is not a regression and not an improvement — it
    is a number about a different loop.
    """
    findings: list[dict[str, object]] = []
    baseline_environment = baseline.get("environment")
    recorded = isinstance(baseline_environment, dict) and bool(baseline_environment)
    measurement_environment = measurement.get("environment", {})
    comparable = recorded and _same_environment(measurement_environment, baseline_environment)
    differences: list[str] = []
    if recorded and not comparable:
        # The comparison refuses to draw a conclusion it cannot support, and it says
        # *why*: "another machine" is true but useless when the difference is one
        # interpreter version.
        for key in sorted(set(measurement_environment) | set(baseline_environment or {})):
            current = measurement_environment.get(key)
            was = (baseline_environment or {}).get(key)
            if current != was:
                differences.append(f"{key}: {was} → {current}")
    for kind in ("steps", "gates"):
        measured = measurement.get(kind)
        baseline_entries = baseline.get(kind)
        if not isinstance(measured, dict):
            continue
        for name in sorted(measured):
            current = measured[name]
            baseline_entry = baseline_entries.get(name) if isinstance(baseline_entries, dict) else None
            median = baseline_entry.get("medianMs") if isinstance(baseline_entry, dict) else None
            if not isinstance(median, (int, float)) or median <= 0:
                verdict = "new"
            elif not comparable:
                verdict = "not-comparable"
            elif current > median * REGRESSION_FACTOR:
                verdict = "slower"
            else:
                verdict = "ok"
            findings.append(
                {
                    "kind": kind,
                    "name": name,
                    "currentMs": current,
                    "medianMs": median,
                    "verdict": verdict,
                }
            )
    return {
        "recorded": recorded,
        "comparable": comparable,
        "differences": differences,
        "findings": findings,
        "slower": [f for f in findings if f["verdict"] == "slower"],
    }


# ── The CI side of the loop ─────────────────────────────────────────────────────


def _gh(*args: str) -> str | None:
    """The output of a `gh` command, or None when there is no answer.

    `gh` is how this repository is reached from a terminal — the same tool the
    release and issue scripts use — so asking it about a run costs nothing extra.
    """
    binary = shutil.which("gh")
    if binary is None:
        return None
    try:
        result = subprocess.run(
            [binary, *args],
            capture_output=True,
            text=True,
            timeout=60,
            stdin=subprocess.DEVNULL,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    if result.returncode != 0:
        return None
    return result.stdout


def read_ci_run(repository: str = CI_REPOSITORY) -> dict[str, object] | None:
    """The last CI run: its jobs and how long each took.

    The CI loop is the one that blocks merges, and it was unpriced: the local loop
    was measured, the loop that decides was not. The durations are free — the
    runner wrote them down — so the measurement is a reading, not a guess.

    These numbers are **not** comparable with the local ones: a hosted runner is
    not this machine, and the local baseline refuses to compare across machines for
    the same reason. So they are published as "the last run", with no median and no
    baseline: a record, not a threshold.
    """
    listed = _gh(
        "run", "list",
        "--repo", repository,
        "--workflow", "ci.yml",
        "--limit", "1",
        "--json", "databaseId,url,createdAt,conclusion",
    )
    if listed is None:
        return None
    try:
        runs = json.loads(listed)
    except json.JSONDecodeError:
        return None
    if not isinstance(runs, list) or not runs:
        return None
    run = runs[0]
    if not isinstance(run, dict):
        return None

    detailed = _gh(
        "run", "view", str(run.get("databaseId", "")),
        "--repo", repository,
        "--json", "jobs",
    )
    jobs: dict[str, int] = {}
    if detailed is not None:
        try:
            document = json.loads(detailed)
        except json.JSONDecodeError:
            document = {}
        for job in document.get("jobs", []) if isinstance(document, dict) else []:
            name = job.get("name")
            duration = _duration_ms(job.get("startedAt"), job.get("completedAt"))
            if isinstance(name, str) and duration is not None:
                jobs[name] = duration
    return {
        "runId": run.get("databaseId"),
        "url": run.get("url"),
        "createdAt": run.get("createdAt"),
        "conclusion": run.get("conclusion"),
        "jobs": jobs,
    }


def _duration_ms(started: object, completed: object) -> int | None:
    """How long a job took, in milliseconds, from two timestamps."""
    if not isinstance(started, str) or not isinstance(completed, str):
        return None
    try:
        first = datetime.fromisoformat(started.replace("Z", "+00:00"))
        second = datetime.fromisoformat(completed.replace("Z", "+00:00"))
    except ValueError:
        return None
    return int((second - first).total_seconds() * 1000)


def measure_coverage_costs(populations: tuple[str, ...] = ("fast", "full")) -> dict[str, int]:
    """Measure the instrumented coverage run, once per population.

    This is the run the Kover agent multiplies: ~37 minutes for the fast
    population on the development host. It is measured here rather than
    copied from a log because the number decides whether a timeout is
    adequate, and a timeout set from a stale number is a timeout set from a
    guess.

    A run that FAILS still costs the time it took — the coverage floor may be
    below the recorded one — so the duration is taken either way, and the
    failure is reported rather than swallowed.
    """
    measured: dict[str, int] = {}
    for population in populations:
        command = [
            "./gw",
            ":koverVerify",
            f"-Ptest.population={population}",
        ]
        logger.info(f"measuring the instrumented coverage run: {population}")
        started = time.monotonic()
        result = subprocess.run(command, cwd=sgate.repo.ROOT, check=False)
        elapsed = int((time.monotonic() - started) * 1000)
        if result.returncode != 0:
            logger.warning(
                f"the '{population}' coverage run exited {result.returncode} — "
                f"the duration is recorded anyway, but the run itself failed"
            )
        measured[population] = elapsed
    return measured


def write_coverage_costs(measured: dict[str, int]) -> None:
    """Write the measured instrumented costs into their own file.

    A separate file, and not a row of the baseline: this file is read by the
    build (`:koverVerify` prints the estimate before it starts), and a build
    reading the gate-costs baseline would make the loop's bookkeeping part of
    the build's inputs.
    """
    path = sgate.repo.COVERAGE_COST_PROPERTIES
    lines = [
        "# The measured cost of the INSTRUMENTED coverage run.",
        "#",
        "# Written by `sgate gate-costs --record --coverage`. The build reads this",
        "# file: `:koverVerify` prints the estimate before it starts, so that nobody",
        "# sets a 15-minute timeout on a 37-minute task.",
        "",
    ]
    for population in sorted(measured):
        lines.append(f"{population}.ms={measured[population]}")
    lines.append(f"measuredAt={datetime.now().astimezone().strftime('%Y-%m-%d')}")
    lines.append(f"machine={os.cpu_count()} vCPU, warm Gradle cache")
    lines.append("")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines), encoding="utf-8")


# ── The command ──────────────────────────────────────────────────────────────────


@click.command("gate-costs")
@click.option("--record", is_flag=True, help="Record the measured cycle into the committed baseline.")
@click.option("--record-ci", is_flag=True, help="Record the last CI run's job durations into the baseline.")
@click.option(
    "--coverage",
    is_flag=True,
    help="Also measure the instrumented coverage run, once per population (slow: about an hour).",
)
@click.option("--render", is_flag=True, help="Render the published document from the baseline.")
@click.option("--check", is_flag=True, help="Fail if the published document is not what the baseline says.")
@click.option("--json", "emit_json", is_flag=True, help="Emit machine-readable output to stdout.")
def gate_costs(
    record: bool,
    record_ci: bool,
    coverage: bool,
    render: bool,
    check: bool,
    emit_json: bool,
) -> None:
    """Report, record, and publish what the verification loop costs.

    With no flag, the last measured cycle is printed next to the baseline, and
    anything that got much slower is named.

    --record writes the measurement into the committed baseline and re-renders the
    published page. --record-ci writes the last CI run's job durations into the same
    baseline: the loop that blocks merges was unpriced while the local one was
    measured. --render re-renders the page from the baseline alone. --check is the
    gate: it fails when the published page is not what the baseline says, which is
    a question a CI runner can answer without a local measurement.

    Exit codes:
      0 = clean (report produced, or --check passed)
      1 = --check: the published document is not what the baseline says
      2 = I/O error (the measurement or the baseline could not be read)
      3 = configuration error (the published document could not be written)
    """
    measurement = read_measurement()
    baseline = read_baseline()

    if record_ci:
        run = read_ci_run()
        if run is None:
            if emit_json:
                click.echo(json.dumps({"passed": False, "status": "no-ci-run"}))
            else:
                logger.error("no CI run could be read — is `gh` installed and authenticated?")
            raise SystemExit(IO_ERROR)
        recorded = dict(baseline)
        recorded["ci"] = run
        write_baseline(recorded)
        write_document(render_document(recorded))
        if emit_json:
            click.echo(json.dumps({
                "passed": True,
                "status": "recorded",
                "runId": run.get("runId"),
                "jobs": len(run.get("jobs", {})),
            }))
        else:
            logger.info("recorded the last CI run and re-rendered the published page")
        raise SystemExit(CLEAN)

    if check:
        expected = render_document(baseline)
        path = sgate.repo.GATE_COSTS_MD
        current = path.read_text(encoding="utf-8") if path.is_file() else ""
        if current != expected:
            if emit_json:
                click.echo(json.dumps({
                    "passed": False,
                    "status": "outdated",
                    "path": str(path),
                }))
            else:
                logger.error(
                    f"{path} is not what the baseline says — run `sgate gate-costs --record`"
                )
            raise SystemExit(VALIDATION_FAILURE)
        if emit_json:
            click.echo(json.dumps({"passed": True, "status": "current", "path": str(path)}))
        else:
            logger.info(f"{path} is current")
        raise SystemExit(CLEAN)

    if coverage:
        measured = measure_coverage_costs()
        if not measured:
            logger.error("the instrumented coverage run could not be measured")
            raise SystemExit(IO_ERROR)
        write_coverage_costs(measured)
        logger.info(
            "measured the instrumented coverage run: "
            + ", ".join(f"{name} {_format_ms(value)}" for name, value in sorted(measured.items()))
        )
        write_document(render_document(baseline))

    if record:
        recorded = _record(baseline, measurement)
        write_baseline(recorded)
        write_document(render_document(recorded))
        if emit_json:
            click.echo(json.dumps({
                "passed": True,
                "status": "recorded",
                "gates": len(recorded.get("gates", {})),
                "steps": len(recorded.get("steps", {})),
            }))
        else:
            logger.info("recorded the measured cycle and re-rendered the published page")
        raise SystemExit(CLEAN)

    if render:
        write_document(render_document(baseline))
        if emit_json:
            click.echo(json.dumps({"passed": True, "status": "rendered"}))
        else:
            logger.info("rendered the published page from the baseline")
        raise SystemExit(CLEAN)

    verdict = compare(measurement, baseline)
    if emit_json:
        click.echo(json.dumps({
            "passed": True,
            "measurement": measurement,
            "comparable": verdict["comparable"],
            "findings": verdict["findings"],
        }, indent=2, sort_keys=True))
        raise SystemExit(CLEAN)

    _print_report(measurement, baseline, verdict)
    raise SystemExit(CLEAN)


def _record(baseline: dict[str, object], measurement: dict[str, object]) -> dict[str, object]:
    """The baseline with the measured cycle appended to its samples."""
    recorded: dict[str, object] = {
        "schemaVersion": SCHEMA_VERSION,
        "recordedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "environment": measurement.get("environment", {}),
    }
    if baseline.get("ci"):
        recorded["ci"] = baseline["ci"]
    for kind in ("gates", "steps"):
        entries = baseline.get(kind) if isinstance(baseline.get(kind), dict) else {}
        measured = measurement.get(kind) if isinstance(measurement.get(kind), dict) else {}
        merged: dict[str, object] = {}
        for name in sorted(set(entries) | set(measured)):
            previous = entries.get(name) if isinstance(entries, dict) else None
            samples = list(previous.get("samples", [])) if isinstance(previous, dict) else []
            current = measured.get(name)
            if isinstance(current, (int, float)):
                samples.append(int(current))
            merged[name] = _entry([s for s in samples if isinstance(s, int)])
        recorded[kind] = merged
    return recorded


def _print_report(
    measurement: dict[str, object],
    baseline: dict[str, object],
    verdict: dict[str, object],
) -> None:
    """The human-readable report."""
    gates = measurement.get("gates") or {}
    steps = measurement.get("steps") or {}
    if not gates and not steps:
        logger.info("no cycle has been measured yet — run ./check.sh to measure one")
        return

    lines = ["The verification loop, last measured:"]
    for kind, title in (("steps", "step"), ("gates", "gate")):
        entries = measurement.get(kind) or {}
        if not isinstance(entries, dict) or not entries:
            continue
        lines.append(f"  {title}s:")
        for finding in verdict["findings"]:
            if finding["kind"] != kind:
                continue
            name = str(finding["name"])
            median = finding["medianMs"]
            marker = {
                "slower": "SLOWER",
                "new": "new",
                "not-comparable": "not comparable",
            }.get(str(finding["verdict"]), "")
            suffix = f"  (baseline {_format_ms(median)})" if median else ""
            flag = f"  ← {marker}" if marker else ""
            lines.append(f"    {name:<52}{_format_ms(finding['currentMs']):>12}{suffix}{flag}")

    if not verdict["recorded"]:
        lines.append("")
        lines.append("No baseline recorded yet — every measurement above is new.")
    elif not verdict["comparable"]:
        lines.append("")
        lines.append(
            "The baseline was measured in a different environment, so the comparison "
            "says nothing:"
        )
        for difference in verdict.get("differences", []):
            lines.append(f"  {difference}")
    slower = verdict["slower"]
    if slower:
        lines.append("")
        lines.append(f"{len(slower)} measurement(s) are more than {REGRESSION_FACTOR}x the baseline.")
    print("\n".join(lines))


# Register with the CLI group
register(gate_costs)
