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


def measurement_label(name: str, kind: str) -> str:
    """What the measurement of a scenario is called in the document."""
    if name == "open":
        return "вход на экран"
    if kind == "click":
        return "переход между периодами"
    return "шаг прокрутки"


def percent(value: float) -> str:
    """A percentage in the units of the document: the Russian ending."""
    count = int(round(value))
    return f"{count} {plural(count, 'процент', 'процента', 'процентов')}"


def desktop_spread_paragraph(evidence: dict) -> str:
    """The paragraph about the spread of the desktop runs.

    The numbers come out of the published evidence, so the prose cannot
    quote a number the evidence does not hold.
    """
    scenarios = evidence["scenarios"]
    lines = []
    spreads = []
    loads: dict[int, float] = {}
    for scenario, published in scenarios.items():
        kinds = {
            measurement["name"]: measurement.get("kind")
            for measurement in published["method"]["measurements"]
        }
        for measurement, result in published["results"].items():
            medians = [
                run["measurements"][measurement]["medianMs"]
                for run in published["runs"]
            ]
            spreads.append((max(medians) - min(medians)) / min(medians) * 100)
            lines.append(
                f"`{scenario}` ({measurement_label(measurement, kinds.get(measurement, ''))}) — "
                + " / ".join(ms(value) for value in medians)
                + " мс"
            )
        for run in published["runs"]:
            load = run.get("hostLoadAverage")
            if load is not None and load >= 0:
                loads[run["index"]] = load
    load_text = ""
    if loads:
        values = [loads[index] for index in sorted(loads)]
        load_text = (
            " Нагрузка хоста в момент замеров (load average "
            f"{ms(min(values))}–{ms(max(values))}) — часть измерения, и она "
            "опубликована вместе с числами."
        )
    return (
        "Прогоны подряд дали по сценариям: "
        + "; ".join(lines)
        + f". Разброс между прогонами одного сценария достигает "
        f"{percent(max(spreads))}, "
        "и это нормально для величины, которую меряет настольный компьютер."
        + load_text
        + " Desktop-числа читаются как порядок величины, а сравнивать их можно только с "
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


def plural(count: int, one: str, few: str, many: str) -> str:
    """The Russian ending of a count: 1 проект, 2 проекта, 5 проектов."""
    if count % 10 == 1 and count % 100 != 11:
        return one
    if count % 10 in (2, 3, 4) and count % 100 not in (12, 13, 14):
        return few
    return many


#: The nouns the dataset of the runs is counted in.
SEED_NOUNS = {
    "tasks": ("задача", "задачи", "задач"),
    "projects": ("проект", "проекта", "проектов"),
    "tags": ("тег", "тега", "тегов"),
    "notes": ("заметка", "заметки", "заметок"),
    "taskTags": ("связь с тегом", "связи с тегами", "связей с тегами"),
    "taskDependencies": ("зависимость", "зависимости", "зависимостей"),
    "checklistItems": ("пункт чеклиста", "пункта чеклиста", "пунктов чеклиста"),
}

#: The nouns the relations of the opened task are counted in.
RELATION_NOUNS = {
    "children": ("подзадача", "подзадачи", "подзадач"),
    "tags": ("тег", "тега", "тегов"),
    "dependencies": ("зависимость", "зависимости", "зависимостей"),
    "notes": ("заметка", "заметки", "заметок"),
    "checklist": ("пункт чеклиста", "пункта чеклиста", "пунктов чеклиста"),
}


def thousands(value: int) -> str:
    """A count in the units of the document: a space between the thousands."""
    return f"{value:,}".replace(",", " ")


def desktop_dataset_paragraph(evidence: dict) -> str:
    """The paragraph about the dataset the desktop runs measured.

    The dataset is the one the runs report, so the prose cannot claim a
    dataset the measurement did not have.
    """
    published = next(iter(evidence["scenarios"].values()))
    dataset = published.get("dataset") or {}
    counts = dataset.get("counts") or {}
    described = ", ".join(
        f"{thousands(value)} {plural(value, *SEED_NOUNS.get(name, (name, name, name)))}"
        for name, value in counts.items()
    )
    database = dataset.get("database")
    room = (
        " Восстановление идёт в **production Room** того же прогона — "
        "в отчёте это записано отдельно (`dataset.database`): замер сделан на "
        "настоящей базе, а не на подмене."
        if database
        else ""
    )
    return (
        "Датасет у сценариев один: "
        f"{dataset.get('generator')}, профиль `{dataset.get('profile')}`, "
        f"seed {dataset.get('seed')} — {described}. Архив восстановлен "
        "импортёром самого приложения (`BackupRepository.import`) — так это "
        "записано в отчёте прогона (`dataset.restoredThrough`), то есть тем же "
        "путём, которым пользователь заливает архив." + room + " Лента держит только видимые "
        "строки — в дереве экрана одновременно живёт около десятка строк."
    )


def desktop_subject_paragraph(evidence: dict) -> str:
    """The paragraph about what the scenario of the task detail opened.

    The subject is the one the runs report, so the prose cannot claim a task
    the measurement did not open.
    """
    published = evidence["scenarios"].get("task-detail-open")
    if not published:
        return ""
    subject = published.get("subject") or {}
    relations = subject.get("relations") or {}
    described = ", ".join(
        f"{value} {plural(value, *RELATION_NOUNS.get(name, (name, name, name)))}"
        for name, value in relations.items()
    )
    return (
        f"Сценарий деталей задачи открывает «{subject.get('task')}» — видимую "
        f"строку ленты, которая держит больше всего связей ({described}). "
        "Правило выбора то же, что на телефоне: из видимых строк берётся самая "
        "связанная, потому что первая строка отсортированного списка обычно "
        "самая пустая, а экран деталей, на котором есть только заголовок, не "
        "меряет ничего."
    )


def desktop_table(evidence: Path) -> str:
    published = json.loads(evidence.read_text(encoding="utf-8"))
    rows = [
        "| сценарий | что меряется | медиана | 95-й перцентиль | выборок |",
        "|---|---|---|---|---|",
    ]
    for scenario, published_scenario in published["scenarios"].items():
        kinds = {
            measurement["name"]: measurement.get("kind")
            for measurement in published_scenario["method"]["measurements"]
        }
        for measurement, result in published_scenario["results"].items():
            rows.append(
                "| `{scenario}` | {label} | {median} мс | {p95} мс | {samples} |".format(
                    scenario=scenario,
                    label=measurement_label(measurement, kinds.get(measurement, "")),
                    median=f"{result['medianMs']:.0f}",
                    p95=f"{result['p95Ms']:.0f}",
                    samples=result["sampleCount"],
                )
            )
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
    text = replace_table(text, "| сценарий | что меряется |", desktop_table(args.desktop_evidence))
    published = json.loads(args.desktop_evidence.read_text(encoding="utf-8"))
    text = replace_paragraph(text, "Прогоны подряд дали по сценариям", desktop_spread_paragraph(published))
    text = replace_paragraph(text, "Датасет у сценариев один", desktop_dataset_paragraph(published))
    text = replace_paragraph(text, "Сценарий деталей задачи открывает", desktop_subject_paragraph(published))
    args.doc.write_text(text, encoding="utf-8")
    print(f"tables of {args.doc} rewritten")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
