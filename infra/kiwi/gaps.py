#!/usr/bin/env python3
"""gaps.py — что в проекте НЕ покрыто тестами, по данным Kiwi TCMS.

Зачем
----
`scripts/check-coverage.py` отвечает на вопрос «сколько строк кода
затронуто», и это не то же самое, что «что я проверяю». У проекта есть
~260 тестовых классов, но нет ни одного ответа на вопрос: какие из них
ни разу не запускались, какие функции вообще не имеют теста, какие
компоненты Kiwi пусты.

Этот скрипт строит три отчёта:

  1. Компоненты без кейсов
     Kiwi-компоненты (feature.tasks, core.sync, …), в которых ноль
     тест-кейсов. Это самый грубый, но самый честный сигнал: на фичу
     нет тестов вообще. Компонент появляется только после sync.py --plan,
     так что «нет кейсов» = «тестов в этом пакете нет».

  2. Кейсы без прогона
     Кейс есть в Kiwi, но TestExecution для него не создавался ни разу.
     Наличие кейса означает «мы собираемся это проверять»; отсутствие
     прогона означает «мы это написали и ни разу не запустили» — типичная
     дыра, которую не видно ни в одном зелёном отчёте.

  3. Исходники без тестов
     Классы production-кода, в чьём имени/пакете нет соответствующего
     тестового класса. Эвристика по имени, не парсинг зависимостей:
     совпадение по basename (NotesRepository → NotesRepositoryTest).
     Ложные срабатывания есть (тест может называться иначе), поэтому
     вывод — это список кандидатов на проверку, а не приговор.

Использование
-------------
    gaps.py                 # человекочитаемый отчёт в stdout
    gaps.py --markdown      # для вставки в отчёт/issue
    gaps.py --json          # машинный вывод
    gaps.py --limit 20      # сократить списки (по умолчанию 15)
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import defaultdict
from dataclasses import dataclass
from dataclasses import field as dataclasses_field
from pathlib import Path

from kiwi_client import KiwiClient, KiwiError

REPO_ROOT = Path(__file__).resolve().parents[2]
PKG_PATH = "kotlin/com/singularity/todo"

# Производственные source set, где ищем «непокрытые» классы.
PROD_ROOTS = [
    REPO_ROOT / "shared/src/commonMain/kotlin/com/singularity/todo",
    REPO_ROOT / "shared/src/jvmMain/kotlin/com/singularity/todo",
]

SKIP_SUFFIXES = (
    "ViewModel.kt",
    "Screen.kt",
    "Coordinator.kt",
    "Dialog.kt",
    "Sheet.kt",
    "Content.kt",
    "Row.kt",
    "Item.kt",
    "Header.kt",
    "Section.kt",
    "Chip.kt",
    "Card.kt",
)


@dataclass
class GapReport:
    empty_components: list[tuple[str, int]]
    cases_without_run: list[tuple[str, str, str]]
    executions_by_status: dict[str, int]
    untested_sources: list[tuple[str, str]]
    orphan_cases: list[tuple[str, str]] = dataclasses_field(default_factory=list)


def collect_case_inventory(client: KiwiClient, product_name: str) -> dict:
    """Все кейсы продукта вместе с их свойствами и последним статусом."""
    product = client.get_product(product_name)
    if product is None:
        raise KiwiError(
            f"product '{product_name}' не найден. Сначала: sync.py --plan"
        )

    plans = client.call("TestPlan.filter", {"product__id": product["id"]}) or []
    # Свойства всех кейсов продукта — одним вызовом на план, а не по одному
    # на кейс (на 259 кейсах это 259 лишних round-trip на каждый запуск).
    bulk = client.get_all_cases_properties([p["id"] for p in plans])

    cases: dict[int, dict] = {}
    for plan in plans:
        for case in client.get_cases(plan["id"]):
            props = bulk.get(case["id"], {})
            cases[case["id"]] = {
                "summary": case.get("summary", ""),
                "plan": plan.get("name", ""),
                # Компонент берём из свойства, а не из ответа TestCase.filter:
                # Kiwi не включает component__name в .values(...), поле там
                # отсутствует, и без этого все кейсы выглядели бы как
                # «без компонента» — отчёт врал бы про покрытие фич.
                "component": props.get("component", ""),
                "properties": props,
            }

    # Последний статус по каждому кейсу: {case_id: (execution_id, status)}.
    # Кортеж, а не строка: без execution_id нельзя отличить «статус из
    # последнего прогона» от «статуса из случайно позднего по id».
    last_status: dict[int, tuple[int, str]] = {}

    # Фильтр обязателен, и это не оптимизация «на будущее». Запрос без него
    # (`TestExecution.filter({})`) возвращает ВСЕ execution за всю историю
    # стенда — включая прогоны, удалённые ротацией: удаляются TestRun, а эта
    # выборка идёт мимо них. Стоимость росла линейно с числом прогонов, и
    # единственным симптомом было «gaps.py идёт дольше с каждым прогоном».
    case_ids = list(cases)
    for start in range(0, len(case_ids), 500):
        chunk = case_ids[start : start + 500]
        for execution in client.call("TestExecution.filter", {"case__in": chunk}) or []:
            case_id = execution.get("case")
            if case_id is None:
                continue
            exec_id = execution.get("id") or 0
            # TestExecution.filter не отдаёт дату выполнения, поэтому «последний»
            # определяется по id: он монотонно растёт при каждой вставке.
            prev = last_status.get(case_id)
            if prev is None or exec_id >= prev[0]:
                last_status[case_id] = (exec_id, execution.get("status__name", "?"))

    return {"product": product, "cases": cases, "last_status": last_status}


def report_components(client: KiwiClient, product: dict, cases: dict) -> list[tuple[str, int]]:
    """Kiwi-компоненты, в которых ноль кейсов.

    Компоненты создаются sync.py --plan только для найденных тестов, поэтому
    пустой компонент означает «в этом пакете нет тестов». Компоненты, которых
    нет в Kiwi вообще, сюда не попадают: о них нельзя сказать ничего —
    их не создавали.
    """
    counts: dict[str, int] = defaultdict(int)
    for case in cases.values():
        counts[case["component"] or "(без компонента)"] += 1

    empty = []
    for comp in client.call("Component.filter", {"product": product["id"]}) or []:
        name = comp.get("name", "")
        if counts.get(name, 0) == 0:
            empty.append((name, 0))
    return sorted(empty)


def _display_path(path: Path, root: Path) -> str:
    """Путь относительно корня пакета: feature/tasks/TasksRepository.kt.

    PROD_ROOTS указывают уже на …/com/singularity/todo, поэтому префикс
    компоненты (feature/, core/) берётся из самого пути, а не добавляется
    отдельно — иначе в выводе получается «core/core/database/…».
    """
    return "/".join(path.relative_to(root).parts)


def report_without_runs(inventory: dict) -> list[tuple[str, str, str]]:
    """Кейсы, для которых нет ни одного TestExecution."""
    out = []
    for case_id, case in inventory["cases"].items():
        if case_id in inventory["last_status"]:
            continue
        path = case["properties"].get("source_path", "?")
        out.append((case["summary"], path, case["plan"]))
    return sorted(out)


def report_statuses(inventory: dict) -> dict[str, int]:
    counts: dict[str, int] = defaultdict(int)
    for _, status in inventory["last_status"].values():
        counts[status] += 1
    return dict(counts)


def report_untested_sources() -> list[tuple[str, str]]:
    """Production-классы, для которых не нашлось теста по имени.

    Эвристика: имя теста = basename класса + 'Test' (или '…Spec'). Если
    совпадений несколько (тест в другом пакете), класс считается
    покрытым по имени — точность здесь намеренно в пользу «меньше
    ложных срабатываний», потому что вывод идёт в список кандидатов.
    """
    test_basenames: set[str] = set()
    for _, root in (
        ("shared", REPO_ROOT / "shared/src/commonTest/kotlin"),
        ("shared", REPO_ROOT / "shared/src/jvmTest/kotlin"),
        ("desktop", REPO_ROOT / "desktopApp/src/jvmTest/kotlin"),
    ):
        if not root.exists():
            continue
        for path in root.rglob("*Test.kt"):
            name = path.stem
            for suffix in ("Test", "Spec", "Tests"):
                if name.endswith(suffix):
                    name = name[: -len(suffix)]
                    break
            test_basenames.add(name)

    missing: list[tuple[str, str]] = []
    for root in PROD_ROOTS:
        if not root.exists():
            continue
        for path in sorted(root.rglob("*.kt")):
            if path.name.endswith(SKIP_SUFFIXES):
                continue
            stem = path.stem
            if stem in test_basenames:
                continue
            if stem.endswith(("ViewModel", "Screen", "Coordinator")):
                # UI-слой закрыт Compose-флоу-тестами (feature.flows),
                # которые не совпадают по имени с этим классом.
                continue
            missing.append((_display_path(path, root), stem))
    return sorted(missing)


def report_orphans(inventory: dict) -> list[tuple[str, str]]:
    """Кейсы в Kiwi, для которых в репозитории нет ни одного файла.

    Так бывает после удаления/переименования теста, после отсева
    abstract-баз и хелперов (см. sync.is_runnable_test_class) — или после
    смены имени пакета. Такие кейсы не могут получить результат никогда,
    поэтому в отчёте о дырах они выглядят как вечное «не проверено» и
    тянут вниз показатель покрытия.

    Синх их не удаляет намеренно: удаление в Kiwi необратимо, а решение
    «оставить как есть / удалить / переписать summary» — за человеком.
    Здесь они просто отделены от настоящих пробелов, чтобы их нельзя было
    спутать с реальной работой.
    """
    from sync import scan_repository

    known = {t.rel_path for t in scan_repository()}
    out = []
    for case in inventory["cases"].values():
        path = case["properties"].get("source_path")
        if path and path not in known:
            out.append((case["summary"], path))
    return sorted(out)


def build_report(client: KiwiClient, product_name: str) -> GapReport:
    inventory = collect_case_inventory(client, product_name)
    return GapReport(
        empty_components=report_components(
            client, inventory["product"], inventory["cases"]
        ),
        cases_without_run=report_without_runs(inventory),
        executions_by_status=report_statuses(inventory),
        untested_sources=report_untested_sources(),
        orphan_cases=report_orphans(inventory),
    )


def render_text(rep: GapReport, limit: int) -> str:
    out: list[str] = []
    total_cases = (
        len(rep.cases_without_run) + sum(rep.executions_by_status.values())
    )
    ran = sum(rep.executions_by_status.values())

    out.append("=" * 72)
    out.append(f"Пробелы тестирования — кейсов в Kiwi: {total_cases}")
    out.append("=" * 72)

    out.append("")
    out.append(f"Последний прогон по кейсам: {ran}")
    for status, count in sorted(
        rep.executions_by_status.items(), key=lambda kv: -kv[1]
    ):
        out.append(f"  {status:<10} {count}")

    out.append("")
    if rep.cases_without_run:
        out.append(f"[1] КЕЙСОВ БЕЗ ПРОГОНА: {len(rep.cases_without_run)}")
        out.append("    Написаны, но ни разу не запускались:")
        for summary, path, plan in rep.cases_without_run[:limit]:
            out.append(f"  - {summary}")
            out.append(f"      {path}")
        if len(rep.cases_without_run) > limit:
            out.append(f"  … и ещё {len(rep.cases_without_run) - limit}")
    else:
        out.append("[1] КЕЙСОВ БЕЗ ПРОГОНА: 0 — все кейсы хотя бы раз прогонялись")

    out.append("")
    if rep.empty_components:
        out.append(f"[2] КОМПОНЕНТЫ БЕЗ КЕЙСОВ: {len(rep.empty_components)}")
        for name, _ in rep.empty_components[:limit]:
            out.append(f"  - {name}")
        if len(rep.empty_components) > limit:
            out.append(f"  … и ещё {len(rep.empty_components) - limit}")
    else:
        out.append("[2] КОМПОНЕНТЫ БЕЗ КЕЙСОВ: нет")

    out.append("")
    if rep.orphan_cases:
        out.append(f"[3] КЕЙСЫ-БЛИЗНЕЦЫ (нет файла в репозитории): {len(rep.orphan_cases)}")
        out.append("    Никогда не получат результат. НЕ считать это пробелом в тестах:")
        for summary, path in rep.orphan_cases[:limit]:
            out.append(f"  - {summary}")
            out.append(f"      {path}")
        if len(rep.orphan_cases) > limit:
            out.append(f"  … и ещё {len(rep.orphan_cases) - limit}")
    else:
        out.append("[3] КЕЙСЫ-БЛИЗНЕЦЫ: нет")

    out.append("")
    out.append(
        f"[4] PRODUCTION-КЛАССЫ БЕЗ ТЕСТА ПО ИМЕНИ: {len(rep.untested_sources)}"
    )
    out.append("    (эвристика по имени; кандидаты на проверку, не вердикт)")
    for rel, stem in rep.untested_sources[:limit]:
        out.append(f"  - {rel}")
    if len(rep.untested_sources) > limit:
        out.append(f"  … и ещё {len(rep.untested_sources) - limit}")

    out.append("")
    return "\n".join(out)


def render_markdown(rep: GapReport, limit: int) -> str:
    ran = sum(rep.executions_by_status.values())
    lines = [
        "## Пробелы тестирования",
        "",
        f"Кейсов с прогоном: {ran}; без прогона: {len(rep.cases_without_run)}",
        "",
        "### Статусы последнего прогона",
        "",
        "| Статус | Кейсов |",
        "|---|---:|",
    ]
    for status, count in sorted(rep.executions_by_status.items(), key=lambda kv: -kv[1]):
        lines.append(f"| {status} | {count} |")
    lines += ["", f"### Кейсы без прогона ({len(rep.cases_without_run)})", ""]
    for summary, path, _ in rep.cases_without_run[:limit]:
        lines.append(f"- **{summary}** — `{path}`")
    if len(rep.cases_without_run) > limit:
        lines.append(f"- … и ещё {len(rep.cases_without_run) - limit}")
    lines += ["", f"### Компоненты без кейсов ({len(rep.empty_components)})", ""]
    for name, _ in rep.empty_components[:limit]:
        lines.append(f"- {name}")
    if rep.orphan_cases:
        lines += [
            "",
            f"### Кейсы-близнецы, нет файла в репозитории ({len(rep.orphan_cases)})",
            "",
            "Не являются пробелами в тестах; требуют ручного решения.",
            "",
        ]
        for summary, path in rep.orphan_cases[:limit]:
            lines.append(f"- **{summary}** — `{path}`")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--product", default="Singularity Todo")
    ap.add_argument("--limit", type=int, default=15)
    ap.add_argument("--markdown", action="store_true")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--url", default=None)
    ap.add_argument("--user", default=None)
    ap.add_argument("--password", default=None)
    args = ap.parse_args(argv)

    client = KiwiClient()
    if args.url:
        client.url = args.url
    if args.user:
        client.user = args.user
    if args.password:
        client.password = args.password
    client.check_alive()

    rep = build_report(client, args.product)

    # Say out loud what this report is, before printing it. It answers "which
    # test *classes* have never run" over the 259 `Automated/*` cases — a
    # narrower question than the one a reader arriving at "what do we verify?"
    # is asking, and one whose unit is a file rather than a behaviour. The
    # scenario layer (`docs/testing/coverage-matrix.md`) is the answer to that
    # question, and this one is on its way out; see
    # `2026-10-05-scenario-layer-replaces-per-class-reporting.md`.
    #
    # Printed rather than documented because the documentation was already
    # there and did not prevent the misreading: the report looked authoritative
    # and nobody had to open the ADR to find out it was the wrong layer.
    if not (args.json or args.markdown):
        print(
            "!! LEGACY: this report is per test CLASS over the Automated/* plans.\n"
            "   'What do we verify?' is answered by docs/testing/coverage-matrix.md\n"
            "   (per user scenario, per platform). This report is being retired.\n"
        )

    if args.json:
        print(
            json.dumps(
                {
                    "empty_components": [c for c, _ in rep.empty_components],
                    "cases_without_run": [
                        {"summary": s, "path": p, "plan": pl}
                        for s, p, pl in rep.cases_without_run
                    ],
                    "executions_by_status": rep.executions_by_status,
                    "untested_sources": [
                        {"path": p, "class": s} for p, s in rep.untested_sources
                    ],
                    "orphan_cases": [
                        {"summary": s, "path": p} for s, p in rep.orphan_cases
                    ],
                },
                ensure_ascii=False,
                indent=2,
            )
        )
    elif args.markdown:
        print(render_markdown(rep, args.limit))
    else:
        print(render_text(rep, args.limit))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KiwiError as exc:
        print(f"ОШИБКА: {exc}", file=sys.stderr)
        sys.exit(1)
    except KeyboardInterrupt:
        sys.exit(130)
