#!/usr/bin/env python3
"""Write the result tables of the document from the artifacts.

Both tables are rewritten from the files the measurement produced, so the
document cannot quote a number that the evidence does not hold.

    python3 scripts/update-frame-time-doc-tables.py \
      --android-report build/perf/<run>/frame-time-report.json \
      --desktop-evidence docs/architecture/frame-time-evidence/desktop-jvm-frame-time-baseline.json

The Android table is rendered by `scripts/render-android-frame-time-table.py`
and the Desktop table is read out of the published evidence, so the document
holds the numbers of the artifacts and not of a run of this script.
"""

import argparse
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOC = ROOT / "docs" / "architecture" / "compose-frame-time-baseline.md"


def replace_table(text: str, header_prefix: str, table: str) -> str:
    """Replace the markdown table that starts with the given header."""
    lines = text.splitlines()
    start = None
    for index, line in enumerate(lines):
        if line.startswith(header_prefix):
            start = index
            break
    if start is None:
        raise SystemExit(f"no table starting with '{header_prefix}' in the document")
    end = start
    while end < len(lines) and lines[end].startswith("|"):
        end += 1
    new = text.split("\n")
    new[start:end] = table.rstrip("\n").split("\n")
    return "\n".join(new)


def replace_paragraph(text: str, prefix: str, paragraph: str) -> str:
    """Replace the paragraph that starts with the given prefix."""
    lines = text.splitlines()
    start = None
    for index, line in enumerate(lines):
        if line.startswith(prefix):
            start = index
            break
    if start is None:
        raise SystemExit(f"no paragraph starting with '{prefix}' in the document")
    end = start
    while end < len(lines) and lines[end].strip():
        end += 1
    new = text.split("\n")
    new[start:end] = paragraph.split("\n")
    return "\n".join(new)


def ms(value: float) -> str:
    """A number in the units of the document: a decimal comma."""
    return f"{value:.1f}".replace(".", ",")


def desktop_spread_paragraph(evidence: dict) -> str:
    """The paragraph about the spread of the desktop runs.

    The numbers come out of the published evidence, so the prose cannot
    quote a number the evidence does not hold.
    """
    runs = evidence["runs"]
    scroll_medians = [run["scrollMedianMs"] for run in runs]
    open_medians = [run["openTimelineMedianMs"] for run in runs]
    spread = (max(scroll_medians) - min(scroll_medians)) / min(scroll_medians) * 100
    loads = [load for load in (evidence.get("method") or {}).get("hostLoadAveragePerRun") or [] if load is not None and load >= 0]
    load_text = ""
    if len(loads) == len(runs):
        load_text = (
            " Нагрузка хоста в момент замеров (load average "
            f"{', '.join(ms(load) for load in loads)}) — часть измерения, и она "
            "опубликована вместе с числами."
        )
    return (
        "Прогоны подряд дали медиану шага "
        f"{' / '.join(ms(value) for value in scroll_medians)} мс и медиану открытия "
        f"{' / '.join(ms(value) for value in open_medians)} мс: процедура повторяема, но не "
        f"до миллисекунды — разброс между прогонами достигает {spread:.0f} процентов, и это "
        f"нормально для величины, которую меряет настольный компьютер.{load_text} "
        "Desktop-числа читаются как порядок величины, а сравнивать их можно только с "
        "прогонами, снятыми при одинаковой нагрузке хоста."
    )


def android_table(report: Path) -> str:
    result = subprocess.run(
        [sys.executable, str(ROOT / "scripts" / "render-android-frame-time-table.py"), str(report)],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise SystemExit(result.stderr.strip() or "the Android table could not be rendered")
    return result.stdout


def desktop_table(evidence: Path) -> str:
    published = json.loads(evidence.read_text(encoding="utf-8"))
    results = published["results"]
    runs = published["runs"]
    rows = [
        "| величина | медиана | 95-й перцентиль | выборок |",
        "|---|---|---|---|",
        "| открытие ленты Upcoming (переключение вкладки) | {open} мс | — | {opens} |".format(
            open=f"{results['openTimelineMedianMs']:.0f}",
            opens=sum(len(run["openTimelineSamplesMs"]) for run in runs),
        ),
        "| шаг прокрутки (жест + перерисовка) | {scroll} мс | {p95} мс | {scrolls} |".format(
            scroll=f"{results['scrollMedianMs']:.0f}",
            p95=f"{results['scrollP95Ms']:.0f}",
            scrolls=sum(len(run["scrollSamplesMs"]) for run in runs),
        ),
    ]
    return "\n".join(rows)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-report", required=True, type=Path)
    parser.add_argument("--desktop-evidence", required=True, type=Path)
    parser.add_argument(
        "--doc",
        type=Path,
        default=DOC,
        help="the document to rewrite (the default is the document of the repository)",
    )
    args = parser.parse_args(argv[1:])

    text = args.doc.read_text(encoding="utf-8")
    text = replace_table(text, "| сценарий | кадров на прогон |", android_table(args.android_report))
    text = replace_table(text, "| величина |", desktop_table(args.desktop_evidence))
    published = json.loads(args.desktop_evidence.read_text(encoding="utf-8"))
    text = replace_paragraph(text, "Прогоны подряд дали медиану шага", desktop_spread_paragraph(published))
    args.doc.write_text(text, encoding="utf-8")
    print(f"tables of {args.doc} rewritten")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
