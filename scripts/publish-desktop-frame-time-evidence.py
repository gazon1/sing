#!/usr/bin/env python3
"""Publish the desktop frame-time evidence from the runs of the test.

The runs are the XML files of the test task; the numbers come from the
`FRAME_TIME_BASELINE` line the test prints. The published file keeps every
run's own raw samples next to the run's own medians, so a reader can check
the medians against the samples and see how much the runs differ from each
other.

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


def report_of(path: Path) -> dict:
    for line in re.findall(r"FRAME_TIME_BASELINE[^\n<]*", path.read_text(encoding="utf-8")):
        return json.loads(line.split("FRAME_TIME_BASELINE", 1)[1].strip())
    raise SystemExit(f"no FRAME_TIME_BASELINE line in {path}")


def ran_at(path: Path) -> str:
    """When the test ran - the proof that the runs are separate.

    Gradle skips a test task that is up to date, so three copies of one run
    look like three runs. The timestamp the test suite wrote into its own
    report tells them apart; the time of the file does not, because the file
    is copied by hand after each run.
    """
    tree = ElementTree.fromstring(path.read_text(encoding="utf-8"))
    return tree.get("timestamp", "")


def percentile(values: list[float], fraction: float) -> float | None:
    """The p-th percentile by the nearest-rank rule - the rule of the document."""
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, min(len(ordered), math.ceil(fraction * len(ordered))))
    return ordered[rank - 1]


def median(values: list[float]) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    middle = len(ordered) // 2
    if len(ordered) % 2 == 1:
        return ordered[middle]
    return (ordered[middle - 1] + ordered[middle]) / 2.0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("runs", nargs="+", type=Path, help="the JUnit XML of every run")
    parser.add_argument("--out", type=Path, default=EVIDENCE, help="where the evidence is written")
    args = parser.parse_args(argv[1:])

    paths = args.runs
    runs = [report_of(path) for path in paths]
    if not runs:
        raise SystemExit("no runs given")
    first = runs[0]

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

    scrolls = [sample for run in runs for sample in run["scrollSamplesMs"]]
    opens = [sample for run in runs for sample in run["openTimelineSamplesMs"]]
    scroll_medians = [run["scrollMedianMs"] for run in runs]
    open_medians = [run["openTimelineMedianMs"] for run in runs]

    evidence = {
        "target": first["target"],
        "scenario": first["scenario"],
        "dataset": {
            "tasks": first["tasks"],
            "source": "seeded in the test through the repository",
        },
        "method": {
            "warmupRuns": first["warmupRuns"],
            "measuredRunsPerRun": first["runs"],
            "dragsPerRun": first["dragsPerRun"],
            "dragDistancePx": first["dragDistancePx"],
            "measured": "the gesture plus waitForIdle() — the recomposition and the layout of the tree a frame draws",
            "percentileConvention": first["percentileConvention"],
            "hostLoadAveragePerRun": [run.get("hostLoadAverage") for run in runs],
        },
        "results": {
            "openTimelineMedianMs": median(open_medians),
            "openTimelineMedianSpreadMs": [min(open_medians), max(open_medians)],
            "scrollMedianMs": median(scroll_medians),
            "scrollMedianSpreadMs": [min(scroll_medians), max(scroll_medians)],
            "scrollP95Ms": round(percentile(scrolls, 0.95) or 0.0, 3),
            "scrollP95PerRunMs": [run["scrollP95Ms"] for run in runs],
            "composedRows": first["composedRows"],
        },
        "runs": [
            {
                "index": index,
                "openTimelineMedianMs": run["openTimelineMedianMs"],
                "openTimelineSamplesMs": run["openTimelineSamplesMs"],
                "scrollMedianMs": run["scrollMedianMs"],
                "scrollP95Ms": run["scrollP95Ms"],
                "scrollSamplesMs": run["scrollSamplesMs"],
                "ranAt": ran_at(paths[index - 1]),
            }
            for index, run in enumerate(runs, start=1)
        ],
        "samples": {
            "openTimelineMs": opens,
            "scrollMs": scrolls,
            "openTimelineSampleCount": len(opens),
            "scrollSampleCount": len(scrolls),
        },
        "repeatRuns": len(runs),
        "runsAreDistinct": True,
        "reproducibleToTheMillisecond": len(set(scroll_medians)) == 1 and len(set(open_medians)) == 1,
        "note": (
            "the load of the host that measured is in `method.hostLoadAveragePerRun`, because the "
            "numbers move with it; "
            "every raw sample of every run is in `samples` and per run in `runs`; "
            f"{len(runs)} full runs of the same procedure gave a scroll median between "
            f"{min(scroll_medians)} ms and {max(scroll_medians)} ms — the same procedure measured "
            "on this host at another time gave noticeably different numbers, so the state of the "
            "host is part of the measurement and the numbers read as an order of magnitude, not as "
            "a threshold. "
            "every run has its own time in `ranAt`: they are separate executions"
        ),
        "source": (
            "the FRAME_TIME_BASELINE report of each run, read from the JUnit XML of the run "
            "(desktopApp/build/test-results/test/TEST-*AgendaFrameTimeBaselineTest.xml); the test "
            "also writes the same report to build/perf/frame-time-desktop/frame-time-baseline.json"
        ),
        "publishedBy": "scripts/publish-desktop-frame-time-evidence.py",
    }
    args.out.write_text(json.dumps(evidence, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"written {args.out}")
    print(f"runs={len(runs)} scrollMedians={scroll_medians} openMedians={open_medians}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
