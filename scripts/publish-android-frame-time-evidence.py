#!/usr/bin/env python3
"""Publish the Android frame-time evidence from the report of the script.

The published file is a copy of the report the measurement script wrote,
with a note of where it came from and of what it says. The report is
copied as it is: the raw samples of every run stay in it.

The script lives in the repository and not in the working directory of
whoever made the measurement: a published file whose producer cannot be
run again is a file that nobody can check.

    python3 scripts/publish-android-frame-time-evidence.py <frame-time-report.json> [--out <file>]
"""

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import frame_time_evidence  # noqa: E402  — one owner of the rule of a complete report

ROOT = Path(__file__).resolve().parent.parent
EVIDENCE = ROOT / "docs" / "architecture" / "frame-time-evidence" / "android-phone-frame-time-report.json"


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path, help="the report the measurement script wrote")
    parser.add_argument("--out", type=Path, default=EVIDENCE, help="where the evidence is written")
    args = parser.parse_args(argv[1:])

    report_path = args.report
    report = json.loads(report_path.read_text(encoding="utf-8"))
    # A report that was written while the measurement was still going is
    # worth keeping, but it is not a baseline: publishing it would publish
    # the median of two runs as if it were the median of five.
    problems = frame_time_evidence.check_report_is_complete(report)
    if problems:
        print(f"the report is not a complete measurement: {report_path}", file=sys.stderr)
        for problem in problems:
            print(f"  {problem}", file=sys.stderr)
        return 1
    report["evidence"] = {
        "source": "scripts/measure-compose-frame-time.py on the phone",
        "publishedBy": "scripts/publish-android-frame-time-evidence.py",
        "device": "identified by a sha256 fingerprint of its serial; the serial itself is not published",
        "singleRun": True,
        "note": (
            "one run of the whole set of scenarios on one build: every scenario carries the "
            "version of the build in each of its runs, and a run without frame data would have "
            "failed the run instead of being stored as a zero"
        ),
    }
    args.out.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"written {args.out}")
    for name, scenario in report["scenarios"].items():
        aggregate = scenario.get("summary", {})
        versions = sorted({sample.get("appVersionName", "?") for sample in scenario.get("samples", [])})
        print(
            f"  {name}: frames/run={aggregate.get('framesPerRunMedian')} "
            f"janky/run={aggregate.get('jankyFramesPerRunMedian')} "
            f"p50={aggregate.get('measuredFramesMedianMs')} "
            f"p95={aggregate.get('measuredFramesP95Ms')} builds={versions}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
