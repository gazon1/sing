#!/usr/bin/env python3
"""Publish the desktop frame-time evidence from the runs of the test.

The runs are the XML files of the test task; the numbers come from the
`FRAME_TIME_BASELINE` lines the test prints — one line per scenario. The
published file keeps every run's own raw samples next to the run's own
medians, so a reader can check the medians against the samples and see how
much the runs differ from each other.

The published file has a shape of its own: it is the aggregation of several
runs, and the test writes one run. The aggregation therefore lives here, in
the repository, and not in the working directory of whoever made the
measurement — a published file whose producer cannot be run again is a file
that nobody can check.

    python3 scripts/publish-desktop-frame-time-evidence.py <run-1.xml> [<run-2.xml> ...] [--out <file>]
"""

import argparse
import json
import math
import re
import sys
from pathlib import Path
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parent.parent
EVIDENCE = ROOT / "docs" / "architecture" / "frame-time-evidence" / "desktop-jvm-frame-time-baseline.json"


def scenario_order(scenario: str) -> tuple[int, str]:
    """The order the scenarios are published in.

    The order of the test's own execution is the order of the failures of one
    run, not the order a reader thinks in: the agenda, the projects, the
    detail of a project, the detail of a task, the calendar. A scenario the
    list does not know goes last, in alphabetical order - it is published,
    not dropped.
    """
    order = (
        "agenda-upcoming-scroll",
        "projects-list-scroll",
        "project-detail-open",
        "task-detail-open",
        "calendar-periods",
    )
    return (order.index(scenario) if scenario in order else len(order), scenario)


def reports_of(path: Path) -> list[dict]:
    """Every scenario report of one run — the test prints one line per scenario."""
    reports = []
    for line in re.findall(r"FRAME_TIME_BASELINE[^\n<]*", path.read_text(encoding="utf-8")):
        reports.append(json.loads(line.split("FRAME_TIME_BASELINE", 1)[1].strip()))
    if not reports:
        raise SystemExit(f"no FRAME_TIME_BASELINE line in {path}")
    return reports


def ran_at(path: Path) -> str:
    """When the test ran - the proof that the runs are separate.

    Gradle skips a test task that is up to date, so three copies of one run
    look like three runs. The timestamp the test suite wrote into its own
    report tells them apart; the time of the file does not, because the file
    is copied by hand after each run.
    """
    tree = ElementTree.fromstring(path.read_text(encoding="utf-8"))
    return tree.get("timestamp", "")


# The fields that say which experiment a run measured. Two runs that
# disagree about them are two different experiments, and their samples
# cannot be pooled into one median.
CONFIGURATION_KEYS = (
    "target",
    "scenario",
    "seed",
    "runs",
    "warmupRuns",
    "percentileConvention",
)

# The fields of a measurement that say what it measured. Two runs that
# disagree about them measured different gestures.
MEASUREMENT_KEYS = (
    "name",
    "kind",
    "gesturesPerRun",
    "distancePx",
)


def configuration_of(run: dict) -> dict:
    """The configuration of one run - what it measured, not what it measured."""
    configuration = {key: run.get(key) for key in CONFIGURATION_KEYS}
    configuration["measurements"] = [
        {key: measurement.get(key) for key in MEASUREMENT_KEYS}
        for measurement in run.get("measurements", [])
    ]
    return configuration


def check_same_configuration(runs: list[dict]) -> None:
    """Refuse runs that measured different experiments.

    The samples of every run are pooled into one median, so the runs have to
    agree about the dataset, the warm-up, the number of measured runs, the
    gesture and the rule of the percentiles. A run over a hundred tasks and a
    run over a thousand are two different experiments: pooling them would
    publish a median that describes neither.
    """
    first = configuration_of(runs[0])
    for index, run in enumerate(runs[1:], start=2):
        other = configuration_of(run)
        if other == first:
            continue
        differences = "; ".join(
            f"{key}: {first[key]!r} != {other[key]!r}"
            for key in list(CONFIGURATION_KEYS) + ["measurements"]
            if first[key] != other[key]
        )
        raise SystemExit(
            f"run {index} measured a different experiment than run 1 ({differences}). "
            "The samples of the runs are pooled into one median, so runs of different "
            "configurations cannot be published together - measure every configuration "
            "on its own."
        )


def check_samples_match_the_configuration(run: dict, index: int) -> None:
    """Refuse a run whose samples do not match what it claims to have measured."""
    runs = run.get("runs", 0)
    for measurement in run.get("measurements", []):
        name = measurement.get("name")
        gestures = measurement.get("gesturesPerRun")
        expected = runs * gestures if gestures else runs
        if measurement.get("samples") != expected:
            raise SystemExit(
                f"run {index} holds {measurement.get('samples')} samples of '{name}' but claims "
                f"{runs} runs of {gestures or 1} gestures ({expected}). The report does not match "
                "its own configuration."
            )


def percentile(values: list[float], fraction: float) -> float | None:
    """The p-th percentile by the nearest-rank rule - the rule of the document."""
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, min(len(ordered), math.ceil(fraction * len(ordered))))
    return ordered[rank - 1]


def median(values: list[float]) -> float | None:
    """The median by the rule of the document: the middle value, or the mean of the two."""
    if not values:
        return None
    ordered = sorted(values)
    middle = len(ordered) // 2
    if len(ordered) % 2 == 1:
        return ordered[middle]
    return (ordered[middle - 1] + ordered[middle]) / 2.0


def scenario_evidence(
    scenario: str,
    entries: list[tuple[Path, dict]],
) -> dict:
    """The evidence of one scenario, aggregated over the runs that measured it."""
    runs = [report for _, report in entries]
    check_same_configuration(runs)
    for index, run in enumerate(runs, start=1):
        check_samples_match_the_configuration(run, index)
    first = runs[0]

    names = [measurement["name"] for measurement in first.get("measurements", [])]
    results = {}
    samples = {}
    for name in names:
        measurements = [run["measurements"][index] for run in runs for index, m in enumerate(run["measurements"]) if m["name"] == name]
        pooled = [sample for measurement in measurements for sample in measurement.get("samplesMs", [])]
        medians = [measurement["medianMs"] for measurement in measurements]
        results[name] = {
            "medianMs": median(medians),
            "medianSpreadMs": [min(medians), max(medians)],
            "p95Ms": round(percentile(pooled, 0.95) or 0.0, 3),
            "sampleCount": len(pooled),
            "samplesPerRun": [measurement.get("samples") for measurement in measurements],
        }
        samples[name] = pooled

    return {
        "scenario": scenario,
        "dataset": {
            "seed": first.get("seed"),
            "source": "seeded in the test through the repository",
        },
        "method": {
            "warmupRuns": first.get("warmupRuns"),
            "measuredRunsPerRun": first.get("runs"),
            "percentileConvention": first.get("percentileConvention"),
            "hostLoadAveragePerRun": [run.get("hostLoadAverage") for run in runs],
            "measurements": [
                {key: measurement.get(key) for key in MEASUREMENT_KEYS}
                for measurement in first.get("measurements", [])
            ],
        },
        "results": results,
        "runs": [
            {
                "index": index,
                "ranAt": ran_at(path),
                "hostLoadAverage": run.get("hostLoadAverage"),
                "measurements": {
                    measurement["name"]: {
                        "medianMs": measurement.get("medianMs"),
                        "p95Ms": measurement.get("p95Ms"),
                        "samples": measurement.get("samples"),
                        "samplesMs": measurement.get("samplesMs"),
                    }
                    for measurement in run.get("measurements", [])
                },
            }
            for index, (path, run) in enumerate(entries, start=1)
        ],
        "samples": samples,
    }


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("runs", nargs="+", type=Path, help="the JUnit XML of every run")
    parser.add_argument("--out", type=Path, default=EVIDENCE, help="where the evidence is written")
    args = parser.parse_args(argv[1:])

    paths = args.runs
    if not paths:
        raise SystemExit("no runs given")

    # Every run has to be its own execution. Gradle skips a test task that is
    # up to date, so three copies of one run look like three runs - and the
    # median of one execution, published as the median of three, is a number
    # the measurement never produced.
    times = [ran_at(path) for path in paths]
    if len(set(times)) != len(times):
        raise SystemExit(
            "the runs are not separate executions: they share the time in `ranAt` "
            f"({', '.join(sorted(set(times)))}). Gradle skipped the repeated test tasks, so "
            "these are copies of one run - run the test again with --rerun."
        )

    # The scenarios of the runs have to be the same: a run that measured three
    # scenarios and a run that measured four are two different series.
    per_run = [reports_of(path) for path in paths]
    scenario_sets = [set(report.get("scenario") for report in reports) for reports in per_run]
    if len({frozenset(scenarios) for scenarios in scenario_sets}) != 1:
        raise SystemExit(
            "the runs measured different scenarios: "
            + "; ".join(
                f"run {index} measured {sorted(scenarios)}"
                for index, scenarios in enumerate(scenario_sets, start=1)
            )
            + ". The runs of a series have to measure the same scenarios."
        )

    grouped: dict[str, list[tuple[Path, dict]]] = {}
    for path, reports in zip(paths, per_run):
        for report in reports:
            grouped.setdefault(report.get("scenario"), []).append((path, report))

    scenarios = {
        scenario: scenario_evidence(scenario, grouped[scenario])
        for scenario in sorted(grouped, key=scenario_order)
    }

    evidence = {
        "target": per_run[0][0].get("target"),
        "scenarios": scenarios,
        "repeatRuns": len(paths),
        "runsAreDistinct": True,
        "source": (
            "the FRAME_TIME_BASELINE reports of each run, read from the JUnit XML of the run "
            "(desktopApp/build/test-results/test/TEST-*DesktopFrameTimeBaselineTest.xml); the test "
            "also writes the same reports to build/perf/frame-time-desktop/"
        ),
        "publishedBy": "scripts/publish-desktop-frame-time-evidence.py",
    }
    args.out.write_text(json.dumps(evidence, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"written {args.out}")
    for scenario, published in scenarios.items():
        print(
            f"scenario={scenario} "
            + " ".join(
                f"{name}={published['results'][name]['medianMs']:.1f}ms" for name in published["results"]
            )
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
