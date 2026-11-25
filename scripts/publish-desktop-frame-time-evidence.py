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

The published median is the median of the pooled samples — the population the
published sample count describes. A median of the medians of the runs is a
different number, and publishing it next to the count of the pooled samples
leaves the reader with two populations and one number; the medians of the
runs travel next to it instead, so the spread between the runs stays visible.

    python3 scripts/publish-desktop-frame-time-evidence.py <run-1.xml> [<run-2.xml> ...] [--out <file>]
"""

import argparse
import json
import math
import re
import sys
from pathlib import Path
from xml.etree import ElementTree

sys.path.insert(0, str(Path(__file__).resolve().parent))
import frame_time_evidence  # noqa: E402  — one owner of the rule of a complete measurement

ROOT = Path(__file__).resolve().parent.parent
EVIDENCE = ROOT / "docs" / "architecture" / "frame-time-evidence" / "desktop-jvm-frame-time-baseline.json"

# The screens the desktop baseline measures. The set is written down here and
# not taken from the runs: three runs that measured the same three screens
# agree with each other and are still not the baseline of the five screens
# the project measures.
REQUIRED_SCENARIOS = frame_time_evidence.REQUIRED_DESKTOP_SCENARIOS


def scenario_order(scenario: str) -> tuple[int, str]:
    """The order the scenarios are published in.

    The order of the test's own execution is the order of the failures of one
    run, not the order a reader thinks in: the agenda, the projects, the
    detail of a project, the detail of a task, the calendar. A scenario the
    list does not know goes last, in alphabetical order - it is published,
    not dropped.
    """
    order = REQUIRED_SCENARIOS
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
    "dataset",
    "subject",
    "runs",
    "warmupRuns",
    "percentileConvention",
    # The environment the numbers were measured in. Two runs of two
    # different JVMs, or of two different builds, are two different
    # experiments - their samples cannot be pooled into one median either.
    "environment",
)

# The names the environment of a run has to carry. A number without them
# describes a program nobody can point at: which JVM drew it, on which
# operating system, out of which build.
ENVIRONMENT_KEYS = {
    "runtime": ("javaVersion", "javaVendor", "javaVmName", "javaVmVersion", "assertionsEnabled"),
    "os": ("name", "version", "arch"),
    "build": ("variant", "version", "gitSha"),
}

# The report prints its samples in milliseconds with three decimals - a
# microsecond of resolution. The median of an even-sized sample is the mean
# of two of them, so the median of the printed samples and the printed median
# can differ by the rounding of three numbers.
ROUNDING_TOLERANCE_MS = 0.002

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


def check_the_environment_is_named(run: dict, index: int) -> None:
    """Refuse a run that does not name the environment it was measured in.

    The desktop numbers move with the machine they were taken on: a different
    JVM, a different operating system or a different build measures a
    different program. A report that names none of them cannot be compared
    with any other report - and the issue asks for the target, the runtime,
    the build and the method next to the numbers for exactly that reason.
    """
    environment = run.get("environment")
    if not isinstance(environment, dict):
        raise SystemExit(
            f"run {index} names no environment. The published numbers have to say which "
            "JVM, on which operating system and out of which build they were measured - "
            "a number without them describes a program nobody can point at."
        )
    missing = []
    for group, keys in ENVIRONMENT_KEYS.items():
        section = environment.get(group)
        if not isinstance(section, dict):
            missing.append(group)
            continue
        for key in keys:
            value = section.get(key)
            if value is None or (isinstance(value, str) and not value.strip()):
                missing.append(f"{group}.{key}")
    if missing:
        raise SystemExit(
            f"run {index} names no {', '.join(missing)}. The published numbers have to say "
            "which JVM, on which operating system and out of which build they were "
            "measured - a number without them describes a program nobody can point at."
        )


def check_the_measurement_is_repeatable(run: dict, index: int) -> None:
    """Refuse a run that measured too few times, or did not warm up at all.

    The same rule the Android publisher and the Android table apply: a
    baseline is a median over a repeatable measurement, so a scenario needs
    several measured runs, and a warm-up run before them, or the first
    measured run is the measurement of a cold start. The rule lives in one
    place, and every publisher of a frame-time number asks it the same
    question.
    """
    problems = frame_time_evidence.thin_measurement_problems(
        run.get("runs"), run.get("warmupRuns")
    )
    if problems:
        raise SystemExit(
            f"run {index} measured too little to be a baseline: " + "; ".join(problems) + "."
        )


def check_samples_match_the_configuration(run: dict, index: int) -> None:
    """Refuse a run whose samples do not match what it claims to have measured.

    The count of the samples, the raw samples themselves and the numbers
    computed from them are one claim about one run: five samples of a
    measurement are five raw values and the median of those five values. A
    run whose raw samples are fewer than it claims, or whose median is not
    the median of its own samples, does not describe a measurement - it
    describes numbers someone wrote down.
    """
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
        raw = measurement.get("samplesMs")
        if not isinstance(raw, list) or len(raw) != expected:
            raise SystemExit(
                f"run {index} holds {len(raw) if isinstance(raw, list) else 0} raw samples of "
                f"'{name}' but claims {expected}. The report does not match its own "
                "configuration."
            )
        check_the_numbers_match_the_samples(measurement, raw, name, index)


def check_the_numbers_match_the_samples(
    measurement: dict,
    raw: list,
    name: str,
    index: int,
) -> None:
    """Refuse a run whose median or percentile is not the one of its samples."""
    for key, computed in (
        ("medianMs", median(raw)),
        ("p95Ms", percentile(raw, 0.95)),
    ):
        reported = measurement.get(key)
        if reported is None:
            raise SystemExit(
                f"run {index} holds no {key} of '{name}'. The numbers of a measurement are "
                "the numbers of its own samples."
            )
        if abs(float(reported) - float(computed)) > ROUNDING_TOLERANCE_MS:
            raise SystemExit(
                f"run {index} holds a {key} of {reported} for '{name}', but the median and "
                f"the percentile of its own {len(raw)} raw samples are {computed}. The numbers "
                "of a measurement are the numbers of its own samples."
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
        check_the_environment_is_named(run, index)
        check_the_measurement_is_repeatable(run, index)
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
            # The median of the pooled samples - the population that the sample
            # count below describes, and the same rule the Android publisher
            # uses for the frames of its own runs.
            "medianMs": median(pooled),
            "p95Ms": round(percentile(pooled, 0.95) or 0.0, 3),
            "sampleCount": len(pooled),
            "samplesPerRun": [measurement.get("samples") for measurement in measurements],
            # The median of every run, and the spread between them: how much
            # the runs of the series differ from each other.
            "runMediansMs": medians,
            "runMedianSpreadMs": [min(medians), max(medians)],
        }
        samples[name] = pooled

    return {
        "scenario": scenario,
        "dataset": first.get("dataset"),
        "subject": first.get("subject"),
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

    # One run, one report of a scenario. Two reports of the same scenario in
    # one execution are two measurements of one screen, and the set of the
    # scenarios would hide the second one: the run would look like it
    # measured the screen once, and the pooled median would carry the samples
    # of both.
    for index, reports in enumerate(per_run, start=1):
        names = [report.get("scenario") for report in reports]
        duplicates = sorted({name for name in names if names.count(name) > 1})
        if duplicates:
            raise SystemExit(
                f"run {index} holds {len(duplicates)} reports of the same scenario "
                f"({', '.join(duplicates)}). One execution measures a scenario once, and "
                "the samples of two measurements of one screen cannot be published as "
                "one measurement of it."
            )

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

    # And they have to be the whole baseline: three runs that measured the
    # same three screens agree with each other and are still not the baseline
    # of the five screens the project measures.
    missing = [name for name in REQUIRED_SCENARIOS if name not in scenario_sets[0]]
    if missing:
        raise SystemExit(
            f"the runs did not measure the whole desktop baseline: "
            f"{', '.join(missing)} {'is' if len(missing) == 1 else 'are'} missing. "
            "The published file is the baseline of the five desktop screens, and a "
            "series that measured a part of it cannot be published as the whole."
        )

    grouped: dict[str, list[tuple[Path, dict]]] = {}
    for path, reports in zip(paths, per_run):
        for report in reports:
            grouped.setdefault(report.get("scenario"), []).append((path, report))

    scenarios = {
        scenario: scenario_evidence(scenario, grouped[scenario])
        for scenario in sorted(grouped, key=scenario_order)
    }

    # One environment for the whole file: the published numbers are the
    # numbers of one program on one machine. Two runs of two different JVMs
    # or of two different builds are two different series, and the file
    # names one environment.
    environments = {
        json.dumps(report.get("environment"), sort_keys=True)
        for reports in per_run
        for report in reports
    }
    if len(environments) != 1:
        raise SystemExit(
            "the runs were measured in different environments: "
            + "; ".join(
                f"run {index} measured in {json.dumps(report.get('environment'), sort_keys=True)}"
                for index, reports in enumerate(per_run, start=1)
                for report in reports[:1]
            )
            + ". The published file names one environment, and its numbers are the "
            "numbers of one program on one machine."
        )
    environment = json.loads(next(iter(environments)))

    evidence = {
        "target": per_run[0][0].get("target"),
        "environment": environment,
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
