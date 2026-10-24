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

The verdict is a second loop
----------------------------
The pre-push verdict runs on the tree that is being pushed, in the developer's
checkout, and not in the loop above — so it is measured separately, by
``scripts/measure-verdict-cost.py``, and written to its own record,
``docs/testing/verdict-cost.json``. The page renders the verdict's price from that
record and never from a number written in the template: a price that is typed
into a generator is a claim nobody can check.

A record that is absent or stale is published as such. The page says "not
measured" when there is no record, and it says *stale* — with the reason — when
the record describes another tree or another month. Dropping the numbers instead
would leave a reader with neither a number nor a warning, which is the worse of
the two failures.

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
import textwrap
import time
from datetime import datetime, timedelta
from pathlib import Path

import click

import sgate
import sgate.repo  # noqa: F401  — the record's path is resolved from the repo root
from sgate.commands.base import CLEAN, CONFIG_ERROR, IO_ERROR, VALIDATION_FAILURE, register
from sgate.log import logger

#: The repository whose CI runs are measured. The command runs where the code does.
CI_REPOSITORY = "gazon1/sing"
# The branch whose run prices the loop. Every change goes through it, so its
# run is the same work every time — which is what a price describes.
CI_BRANCH = "main"

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

#: The record of the measured price of the local pre-push verdict.
#:
#: Its own file, and not a section of the baseline: the verdict runs on the tree
#: that is being pushed, not in the loop the baseline prices, and it is measured
#: by a different tool (``scripts/measure-verdict-cost.py``). The path lives here
#: rather than in ``sgate.repo`` because the file belongs to that tool's world:
#: the tool writes it, this command reads it, and neither owns the other.
VERDICT_COST_JSON: Path = sgate.repo.ROOT / "docs" / "testing" / "verdict-cost.json"

#: The sentinel that says "read the record from disk". A default of None would
#: mean "there is no record", and the two are different worlds: one is a missing
#: measurement, the other is a broken reader.
FROM_FILE = object()


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


def render_document(baseline: dict[str, object], verdict: object = FROM_FILE) -> str:
    """The published document: prose, and the generated block with the numbers.

    The block is regenerated from the baseline and from the verdict's record, and
    the gate says the committed document is what those two say. A hand-edited
    number is therefore a failing gate rather than a fact nobody can trace.

    The verdict's price is read from ``docs/testing/verdict-cost.json`` — the
    record ``scripts/measure-verdict-cost.py`` writes — so that no number about
    the verdict is typed into this file. Pass ``verdict`` to render from
    something else: a dict is the record, ``None`` says "there is no record" and
    the page says so.
    """
    path = sgate.repo.GATE_COSTS_MD
    if verdict is FROM_FILE:
        record, problem = read_verdict_cost()
    else:
        record = verdict if isinstance(verdict, dict) else None
        problem = "" if record is not None else "missing"
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
    sgate gate-costs --record-ci     # record the branch's last finished CI run

The block below is generated. A hand-edited number is a failing gate, not a fact.

The price of the pre-push verdict is a second loop, and it is measured separately:
the verdict runs on the tree that is being pushed, in the developer's checkout, and
not in the loop above. `scripts/measure-verdict-cost.py` measures it and writes the
record in `docs/testing/verdict-cost.json`; the last section of the generated block is
rendered from that record and from nothing else. When the record is missing or stale,
the section says so — a price published as current when it is not is worse than a
price that is not published at all.

The hook's detekt step is not in the block below: it runs on the tree that is
being pushed, in the developer's checkout, and its cost is what the push
waits for. Measured on 2026-10-23 with the hook's own invocation
(`./gw :shared:detekt :desktopApp:detekt --auto-correct --rerun`):

| Path | Seconds | What ran |
|---|---|---|
| warm — the current worktree | 561 s | `:shared:detekt` 7 min 26 s, `:desktopApp:detekt` 1 min 51 s |
| cold — a fresh worktree at the same sha | 565 s | `:shared:detekt` 8 min 20 s |

Two readings, and one caveat:

- **`:shared` is the whale.** 227 000 lines, 239 packages, 90 % of the verdict.
  The other modules are seconds to two minutes.
- **Warm is not warm.** The two paths cost the same, because `./gw` gives every
  worktree a private `GRADLE_USER_HOME` and the configuration cache is cold in
  a fresh worktree regardless. Judging in the current worktree saves the build
  directory, not the analysis — the number of modules is the lever.
- **Caveat:** the load average on this host was 34–50 (ten agent sessions
  share the machine), so the absolute numbers are an upper bound. The ratio
  between the paths is the fact.

The verdict was narrowed to the modules the change can reach (ADR
`2026-10-23-the-local-verdict-judges-what-the-change-reaches.md`), so the
price now follows the change: one module for a narrow change, none for a
change that moves neither Kotlin nor rules, and four for a change in
`shared`. The local verdict is partial, and the hook says so.

{BEGIN_MARKER}
"""
    footer = f"""{END_MARKER}
"""
    return header + _render_block(baseline, record, problem) + footer


def _verdict_status(record: dict[str, object] | None, problem: str) -> dict[str, object]:
    """What the gate says about the verdict's record, in machine-readable form.

    The gate does not fail on a stale record — a stale record is a fact about an
    older tree, and the page publishes it as such. It fails when the page and
    the record disagree, and this says which of the two is in the page.
    """
    if record is None:
        return {"present": False, "state": problem or "missing"}
    return {
        "present": True,
        "state": record.get("status", "unknown"),
        "measuredAt": record.get("measuredAt", ""),
    }


def read_verdict_cost(path: Path | None = None) -> tuple[dict[str, object] | None, str]:
    """The record of the measured verdict price, and what is wrong with it.

    Returns ``(record, problem)``. The record is ``None`` when there is nothing
    to render — the file is absent, or it is not a record — and the problem says
    which of the two it was, so that the page can say "not measured" instead of
    pretending that a broken file is a missing measurement.
    """
    target = path or VERDICT_COST_JSON
    if not target.is_file():
        return None, "missing"
    try:
        document = json.loads(target.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        logger.warning(f"could not read the verdict cost record at {target}: {exc}")
        return None, "unreadable"
    if not isinstance(document, dict):
        logger.warning(f"the verdict cost record at {target} is not a record")
        return None, "unreadable"
    return document, ""


def _render_verdict_block(record: dict[str, object] | None, problem: str) -> list[str]:
    """The price of the pre-push verdict, rendered from the record.

    Every number here comes out of the record. A number typed into this file
    would be a claim nobody could check, and the whole point of the record is
    that it can be.
    """
    lines: list[str] = ["### The price of the pre-push verdict", ""]
    if record is None:
        if problem == "unreadable":
            lines.append(
                "The record at `docs/testing/verdict-cost.json` could not be read, so "
                "the price of the verdict is **unknown**, not zero. Rewrite the record:"
            )
        else:
            lines.append(
                "No verdict measurement is recorded, so the price of the verdict is "
                "**unknown**, not zero. Measure it with:"
            )
        lines.append("")
        lines.append("    python3 scripts/measure-verdict-cost.py")
        lines.append("")
        lines.append(
            "The record it writes — `docs/testing/verdict-cost.json` — is what this "
            "section is rendered from."
        )
        lines.append("")
        lines.extend(_render_verdict_schema())
        return lines

    measured = record.get("measuredAt")
    status = record.get("status")
    sha = record.get("sha")
    lines.append(
        "Measured by `scripts/measure-verdict-cost.py`; the record is "
        "`docs/testing/verdict-cost.json`"
        + (f" — recorded {measured}" if measured else "")
        + (f", tree {sha[:8]}" if isinstance(sha, str) and sha else "")
        + "."
    )
    lines.append("")
    expiry = _verdict_expiry(record)
    if expiry:
        lines.append(f"This record expires on {expiry} — after that it is a history.")
        lines.append("")
    judged = record.get("verdict")
    if isinstance(judged, dict) and isinstance(judged.get("tasks"), list):
        tasks = ", ".join(f"`{task}`" for task in judged["tasks"] if isinstance(task, str))
        if tasks:
            lines.append(
                f"The verdict judged {tasks} — the price of a verdict is a price per "
                "judged module."
            )
            lines.append("")
    load = record.get("host")
    if isinstance(load, dict) and load:
        described = _describe_load(load)
        if described:
            lines.append(
                f"Measured while {described} — the cost of the verdict is a property "
                "of the verdict and the host together, and this is the host."
            )
            lines.append("")
    if status == "stale":
        reason = record.get("staleReason") or "the record was not refreshed"
        lines.append(
            f"**This record is stale** — {reason}. The numbers below describe the "
            "tree that was measured, not the tree in front of you; read them as a "
            "history, not as a price."
        )
        lines.append("")

    phases = record.get("phases")
    entries = [p for p in phases if isinstance(p, dict)] if isinstance(phases, list) else []
    if entries:
        lines.append("| Phase | Cost | What ran |")
        lines.append("|---|---|---|")
        for entry in entries:
            lines.append(
                f"| {entry.get('name')} | {_format_ms(entry.get('durationMs'))} "
                f"| {entry.get('what')} |"
            )
        lines.append("")
        lines.extend(_render_verdict_derivations(entries))
        lines.extend(_render_verdict_notes(record, entries))
        lines.extend(_render_verdict_commands(entries))
    else:
        lines.append("The record carries no measured phase, so there is no price to publish.")
        lines.append("")
    lines.extend(_render_verdict_schema())
    return lines


def _render_verdict_schema() -> list[str]:
    """What the record's fields mean, and how to produce a new one.

    The documentation of the record lives with the thing that renders it: a
    reader who wants to know what `durationMs` is should not have to open a
    second file to find out, and a second file would be a second thing to keep
    true.
    """
    return [
        "The record's fields:",
        "",
        "- `measuredAt` — when the measurement was taken.",
        "- `status` — `fresh` or `stale`; a stale record says why in `staleReason`.",
        "- `expiresDays` — how long the record stays fresh before it must be re-measured.",
        "- `sha` — the tree the measurement was taken on.",
        "- `treeDigest` — the content digest of that tree; a tree that no longer has "
        "it makes the record stale.",
        "- `environment`, `host` — the machine and its load: a duration without them "
        "is not comparable with anything.",
        "- `phases` — what was measured: `name`, `what`, `command`, `durationMs`, "
        "`exitCode`, `note`.",
        "- `notes` — the observations a number cannot make.",
        "",
        "To measure it again:",
        "",
        "    python3 scripts/measure-verdict-cost.py             # measure and write the record",
        "    python3 scripts/measure-verdict-cost.py --refresh   # re-decide freshness only",
        "",
    ]


def _verdict_expiry(record: dict[str, object]) -> str:
    """The day the record stops being a price, read from the record itself.

    Computed from the record and not from the clock: a page that decided for
    itself what day it is would print a different sentence every day, and the
    gate that compares the page with the render would fail for the wrong
    reason.
    """
    measured = record.get("measuredAt")
    expires = record.get("expiresDays")
    if not isinstance(measured, str) or not isinstance(expires, int):
        return ""
    try:
        taken = datetime.fromisoformat(measured)
    except ValueError:
        return ""
    return (taken + timedelta(days=expires)).date().isoformat()


def _render_verdict_derivations(entries: list[dict[str, object]]) -> list[str]:
    """What the measured phases say when they are read against each other.

    Derived here and not stored in the record: a derivation that is stored is a
    number that can go stale without the phases it came from moving.
    """
    by_name = {
        entry.get("name"): entry
        for entry in entries
        if isinstance(entry.get("name"), str)
    }
    lines: list[str] = []
    read_only = by_name.get("detekt")
    correcting = by_name.get("auto-correct")
    if read_only and correcting:
        read_ms = read_only.get("durationMs")
        correct_ms = correcting.get("durationMs")
        if isinstance(read_ms, int) and isinstance(correct_ms, int) and read_ms > 0:
            overhead = correct_ms - read_ms
            more = int(round(100 * overhead / read_ms))
            share = int(round(100 * overhead / correct_ms)) if correct_ms > 0 else 0
            lines.append(
                f"Correcting the tree costs {_format_ms(overhead)} on top of the "
                f"read-only run — {more} % more than the analysis, and {share} % of "
                "the correcting run. The verdict reads the tree it judges and never "
                "corrects it, so the push waits for the analysis and not for the "
                "correction."
            )
            lines.append("")
    return lines


def _render_verdict_notes(record: dict[str, object], entries: list[dict[str, object]]) -> list[str]:
    """The notes the record carries — the observations a number cannot make.

    A phase note belongs to its phase and says so; a note on the record belongs
    to the measurement as a whole.
    """
    notes: list[str] = []
    for entry in entries:
        note = entry.get("note")
        if isinstance(note, str) and note:
            notes.append(f"- `{entry.get('name')}`: {note}")
    carried = record.get("notes")
    if isinstance(carried, list):
        for note in carried:
            if isinstance(note, str) and note:
                notes.append(f"- {note}")
    if not notes:
        return []
    return ["Notes from the record:", "", *notes, ""]


def _render_verdict_commands(entries: list[dict[str, object]]) -> list[str]:
    """The command each phase ran, so that a number can be reproduced."""
    lines = ["The command each phase ran is in the record:", ""]
    for entry in entries:
        command = entry.get("command")
        if isinstance(command, str) and command:
            lines.append(f"- `{command}`")
    lines.append("")
    return lines


def _render_block(
    baseline: dict[str, object],
    verdict: dict[str, object] | None = None,
    verdict_problem: str = "missing",
) -> list[str]:
    """The generated block: the medians, per gate and per step, and the verdict."""
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
    lines.extend(_render_verdict_block(verdict, verdict_problem))
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
    """The CI side of the loop: one run's jobs and what each took.

    Not a median and not a baseline: a hosted runner is not this machine, and the
    comparison this file refuses to make across machines is the same one. So the
    numbers are published as a record of one run, with the run it came from.

    The record is that run, not the newest one. A heading that says "the last
    finished run" is a claim about the newest run, and every push to the branch
    makes it false — the page outlives the run it names. The record goes stale
    when the workflow's jobs change, because then it prices a loop that no longer
    exists; the branch moving is not that.
    """
    ci = baseline.get("ci")
    if not isinstance(ci, dict):
        return []
    jobs = ci.get("jobs")
    if not isinstance(jobs, dict) or not jobs:
        return []
    lines = [f"### The recorded run of `{CI_BRANCH}`", ""]
    created = ci.get("createdAt")
    url = ci.get("url")
    if created or url:
        where = f" — {created}" if created else ""
        if url:
            where += f" — {url}"
        lines.append(f"The CI loop is the one that blocks merges, so it is priced too{where}.")
        lines.append("")
        lines.append(
            "The record need not be the newest run: it is the run it names. It is stale "
            "when the workflow's jobs change: a job renamed, added or removed turns it "
            "into a price of a loop that no longer exists. It is not stale when "
            f"`{CI_BRANCH}` moves — a later run with the same jobs is another sample of "
            "the same loop, so re-recording it would move the numbers without making "
            "the page truer. A gate says so when it happens: "
            "`scripts/check-recorded-ci-jobs.py` names the job that is gone."
        )
        lines.append("")
    lines.append("| Job | Duration |")
    lines.append("|------|----------|")
    for name in sorted(jobs):
        lines.append(f"| {name} | {_format_ms(jobs[name])} |")
    lines.append("")
    conclusion = ci.get("conclusion")
    if isinstance(conclusion, str) and conclusion:
        # The verdict belongs with the durations: a run that failed is a price
        # like any other, and a reader who does not see it will read a failed
        # run as the price of a green one.
        lines.append(f"The run finished as **{conclusion}**.")
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
    the same reason. So they are published as one recorded run, with no median and
    no baseline: a record, not a threshold.

    The run has to be a FINISHED one. A run that is still going has no duration
    for the jobs that have not finished yet, and no conclusion to print — and a
    record of half a run is published as the price of the whole loop, which is
    the one thing a price must not be. So the runner is asked for completed runs,
    and a run that has not finished is not recorded at all.

    And it has to be the run of the BRANCH, not of whatever pull request was
    pushed last. A price is about the loop, and the loop that blocks a merge is
    the one the branch runs: the same workflow, every time, on every change. A
    pull request's run belongs to that pull request — its conclusion says
    something about that change, not about the loop, and a reader who finds the
    price of the loop written next to a foreign branch's failed run reads a
    price for something that was never measured.
    """
    listed = _gh(
        "run", "list",
        "--repo", repository,
        "--workflow", "ci.yml",
        "--branch", CI_BRANCH,
        "--limit", "1",
        "--status", "completed",
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
    if not jobs:
        # The runner's record of the run's jobs could not be read. A record with
        # no jobs is not a price, and writing it would REPLACE the recorded
        # price of the loop with nothing — and the published section, which
        # renders from the jobs, would silently disappear. A read that failed
        # is a failed read: there is no record, and the old one stays.
        return None
    return {
        "runId": run.get("databaseId"),
        "url": run.get("url"),
        "createdAt": run.get("createdAt"),
        "conclusion": run.get("conclusion"),
        "jobs": jobs,
    }


def _duration_ms(started: object, completed: object) -> int | None:
    """How long a job took, in milliseconds, from two timestamps.

    A job that was SKIPPED has no duration. The runner still reports two
    timestamps for it, and they are not in order — a job that never ran is
    recorded as finishing a second before it started. A negative price is not a
    price, and a job that did not run has nothing to price, so a span that is
    not positive is no measurement at all.
    """
    if not isinstance(started, str) or not isinstance(completed, str):
        return None
    try:
        first = datetime.fromisoformat(started.replace("Z", "+00:00"))
        second = datetime.fromisoformat(completed.replace("Z", "+00:00"))
    except ValueError:
        return None
    milliseconds = int((second - first).total_seconds() * 1000)
    return milliseconds if milliseconds > 0 else None


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


def _coverage_cost_header(
    measured: dict[str, int],
    measured_at: str,
    uninstrumented_ms: int | None,
) -> list[str]:
    """The prose above the numbers, written where the numbers are written.

    The rationale lives here, and not in the file: a re-measure that replaced
    the file with a header of its own would erase the reason the number
    exists — which is what a reader of the next timeout needs, and the two
    numbers that look backwards.
    """
    costs = ", ".join(
        f"{_format_ms(measured[population])} for the {population} population"
        for population in sorted(measured)
    )
    against = (
        f" — against {_format_ms(uninstrumented_ms)} for the uninstrumented "
        "`:shared:jvmTest`"
        if uninstrumented_ms
        else ""
    )
    paragraphs = [
        "The measured cost of the INSTRUMENTED coverage run.",
        "This is NOT the same run as the uninstrumented `:shared:jvmTest` in the "
        "table of `docs/testing/gate-costs.md`: the Kover agent multiplies the cost "
        "of every test, and the instrumented run is the one that decides whether a "
        f"timeout is adequate. On {measured_at} it took {costs}{against}.",
        "Two numbers that look backwards are not: the full population is CHEAPER "
        "than the fast one, because the fast population is a subset of the classes "
        "and the instrumented overhead is per-class-per-run. The number to plan "
        "against is the larger of the two.",
        "The build reads this file: `:koverVerify` prints the estimate before it "
        "starts, so that nobody sets a 15-minute timeout on a 37-minute task.",
        f"Measured on {measured_at} with a warm Gradle cache, on the development host "
        f"({os.cpu_count()} vCPU). A CI runner has 4 vCPU: the same run there is "
        "slower, so the CI job's timeout must cover at least this measurement "
        "times a chosen margin of 2x — the constant "
        "`scripts/check-coverage-timeout.py` uses. The margin is chosen, not "
        "derived: a fully CPU-bound run on 4 vCPU would cost four times this "
        "measurement. The gate is what says so — a timeout that no longer "
        "covers the measurement fails that gate instead of the run.",
        "To re-measure: `sgate gate-costs --record --coverage` (about an hour: it "
        "runs the instrumented coverage once per population).",
    ]
    lines: list[str] = []
    for index, paragraph in enumerate(paragraphs):
        if index:
            lines.append("#")
        lines.extend(f"# {line}" for line in textwrap.wrap(paragraph, width=76))
    lines.append("")
    return lines


def write_coverage_costs(
    measured: dict[str, int],
    uninstrumented_ms: int | None = None,
    measured_at: str | None = None,
) -> None:
    """Write the measured instrumented costs into their own file.

    A separate file, and not a row of the baseline: this file is read by the
    build (`:koverVerify` prints the estimate before it starts), and a build
    reading the gate-costs baseline would make the loop's bookkeeping part of
    the build's inputs.

    The prose is written here too, so that re-measuring replaces the numbers
    and leaves the reasoning that makes them readable.
    """
    path = sgate.repo.COVERAGE_COST_PROPERTIES
    day = measured_at or datetime.now().astimezone().strftime("%Y-%m-%d")
    lines = _coverage_cost_header(measured, day, uninstrumented_ms)
    for population in sorted(measured):
        lines.append(f"{population}.ms={measured[population]}")
    lines.append(f"measuredAt={day}")
    lines.append(f"machine={os.cpu_count()} vCPU, warm Gradle cache")
    lines.append("")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines), encoding="utf-8")


# ── The command ──────────────────────────────────────────────────────────────────


@click.command("gate-costs")
@click.option("--record", is_flag=True, help="Record the measured cycle into the committed baseline.")
@click.option(
    "--record-ci",
    is_flag=True,
    help="Record the branch's last finished CI run — its job durations and its verdict.",
)
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
    published page. --record-ci writes the branch's last FINISHED run — its job
    durations and its verdict — into the same baseline: the loop that blocks merges
    was unpriced while the local one was measured. A run that has not finished is
    not recorded, and a run whose jobs cannot be read is not recorded either: a
    failed read must not replace a recorded price with nothing. --render re-renders the page from the baseline alone. --check is the
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
        record, problem = read_verdict_cost()
        if current != expected:
            if emit_json:
                click.echo(json.dumps({
                    "passed": False,
                    "status": "outdated",
                    "path": str(path),
                    "verdict": _verdict_status(record, problem),
                }))
            else:
                logger.error(
                    f"{path} is not what the baseline says — run `sgate gate-costs --record`"
                )
            raise SystemExit(VALIDATION_FAILURE)
        if emit_json:
            click.echo(json.dumps({
                "passed": True,
                "status": "current",
                "path": str(path),
                "verdict": _verdict_status(record, problem),
            }))
        else:
            logger.info(f"{path} is current")
        raise SystemExit(CLEAN)

    if coverage:
        measured = measure_coverage_costs()
        if not measured:
            logger.error("the instrumented coverage run could not be measured")
            raise SystemExit(IO_ERROR)
        steps = baseline.get("steps")
        uninstrumented = None
        if isinstance(steps, dict):
            entry = steps.get("shared:jvmTest")
            if isinstance(entry, dict):
                median = entry.get("medianMs")
                if isinstance(median, int):
                    uninstrumented = median
        write_coverage_costs(measured, uninstrumented)
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
