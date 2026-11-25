#!/usr/bin/env python3
"""Render the Android results table of the document from the report.

The numbers of the table are read out of the report the measurement script
wrote, so the table cannot drift away from the evidence it quotes.

    python3 scripts/render-android-frame-time-table.py <frame-time-report.json>
"""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import frame_time_evidence  # noqa: E402  — one owner of the rule of a complete report

ROWS = [
    ("agenda-scroll", "секция Overdue вкладки Today"),
    ("project-tasks-scroll", "список проектов"),
    ("project-detail-scroll", "список задач проекта"),
    ("task-detail-scroll", "детали задачи"),
    ("calendar-periods", "календарь, переход между периодами"),
]


def cell(value) -> str:
    if value is None:
        return "—"
    if isinstance(value, float):
        return f"{value:.2f}".rstrip("0").rstrip(".").replace(".", ",")
    return str(value)


def main(argv: list[str]) -> int:
    report = json.loads(Path(argv[1]).read_text(encoding="utf-8"))
    # The table of the document quotes the numbers of the report, so a
    # report that was written while the measurement was still going cannot
    # be turned into a table: the row of a half-measured scenario would say
    # the median of two runs as if it were the median of five.
    problems = frame_time_evidence.check_report_is_complete(report)
    if problems:
        print("the report is not a complete measurement:", file=sys.stderr)
        for problem in problems:
            print(f"  {problem}", file=sys.stderr)
        return 1
    scenarios = report["scenarios"]
    print("| сценарий | кадров на прогон | janky на прогон | janky, % | p50 сводки | p95 сводки | кадр: медиана | кадр: p95 | сборка |")
    print("|---|---|---|---|---|---|---|---|---|")
    for name, what in ROWS:
        scenario = scenarios.get(name)
        if scenario is None:
            continue
        summary = scenario["summary"]
        versions = sorted({sample.get("appVersionName", "?") for sample in scenario["samples"]})
        build = versions[0] if len(versions) == 1 else ", ".join(versions)
        print(
            "| `{name}` | {frames} | {janky} | {percent} % | {p50} мс | {p95} мс | "
            "{median} мс | {frame_p95} мс | `{build}` |".format(
                name=name,
                what=what,
                frames=cell(summary.get("framesPerRunMedian")),
                janky=cell(summary.get("jankyFramesPerRunMedian")),
                percent=cell(summary.get("jankyPercentOverall")),
                p50=cell(summary.get("gfxinfoP50MedianMs")),
                p95=cell(summary.get("gfxinfoP95MedianMs")),
                median=cell(summary.get("measuredFramesMedianMs")),
                frame_p95=cell(summary.get("measuredFramesP95Ms")),
                build=build,
            )
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
