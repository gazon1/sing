#!/usr/bin/env python3
"""prune.py — убрать старые прогоны, оставив историю.

## Зачем

Kiwi хранит каждый TestRun и каждый TestExecution навсегда. Прогоны приходят
по одному на план за каждый цикл CI, и каждый несёт ~200 записей о выполнении.
За год при 5 планах и росте в день это десятки тысяч строк, и единственный
способ узнать — прочитать их.

Ротация здесь не про место на диске: `gaps.py` читает **все** TestExecution
без фильтра, чтобы найти последний статус по каждому кейсу, и это
O(все прогоны за всю историю) на каждом запуске. Пока история копится
медленно, это незаметно; потом каждый вызов отчёта начинает ждать.

## Что удаляется, а что нет

Удаляются прогоны **за пределами окна по плану**: `TestRun.remove` каскадит на
TestExecution, свойства, теги и вложения, поэтому вместе со старым прогоном
уходит ровно его содержимое и ничего больше. Кейсы не трогаются — они
описывают намерение, а не результат, и переживают ротацию.

Стратегия «последние N на план» вместо «всё старше даты» выбрана потому,
что планы наполняются неравномерно: у `shared` прогоны на каждом цикле, у
`android instrumented` — почти никогда. Окно по дате стёрло бы историю
`android` целиком после первого же неудачного месяца, а N на план сохраняет
у каждого плана сопоставимую глубину.

## Безопасность

Удаление необратимо, поэтому **по умолчанию скрипт ничего не удаляет** — он
печатает, что удалил бы. `--apply` включает удаление, `--yes` не требуется
для неинтерактивного запуска по расписанию: решение о ротации принимает
тот, кто её настроил.

    ./infra/kiwi/prune.py                 # что будет удалено (ничего не удаляет)
    ./infra/kiwi/prune.py --apply         # удалить
    ./infra/kiwi/prune.py --keep 40       # окно шире
"""

from __future__ import annotations

import argparse
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from kiwi_client import KiwiClient, KiwiError  # noqa: E402

DEFAULT_KEEP = 20


def plan_prune(
    client: KiwiClient, product_name: str, keep: int
) -> dict[str, list[dict]]:
    """Прогоны к удалению, сгруппированные по плану. Ничего не удаляет.

    Возвращает {plan_name: [run, …]} — только те прогоны, что выходят за окно.
    """
    product = client.get_product(product_name)
    if product is None:
        raise KiwiError(f"product '{product_name}' не найден — выполните sync.py --plan")

    doomed: dict[str, list[dict]] = {}
    for plan in client.call("TestPlan.filter", {"product__id": product["id"]}) or []:
        runs = sorted(
            client.get_runs(plan["id"]), key=lambda r: r.get("id") or 0, reverse=True
        )
        stale = runs[keep:]
        if stale:
            doomed[plan["name"]] = stale
    return doomed


def apply_prune(
    client: KiwiClient, product_name: str, keep: int
) -> dict[str, int]:
    """Удалить прогоны за окном. Возвращает {plan_name: удалено}."""
    product = client.get_product(product_name)
    assert product is not None  # plan_prune уже проверил
    removed: dict[str, int] = {}
    for plan in client.call("TestPlan.filter", {"product__id": product["id"]}) or []:
        runs = sorted(
            client.get_runs(plan["id"]), key=lambda r: r.get("id") or 0, reverse=True
        )
        for run in runs[keep:]:
            # Фильтр по id, а не по run__id: у TestRun.remove поле называется
            # `id`, и запрос с run__id тихо вернул бы 0 удалённых строк —
            # то есть скрипт рапортовал бы об успехе, ничего не сделав.
            client.call("TestRun.remove", {"id": run["id"]})
            removed[plan["name"]] = removed.get(plan["name"], 0) + 1
    return removed


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--keep", type=int, default=DEFAULT_KEEP,
                    help=f"сколько последних прогонов оставить на план (default {DEFAULT_KEEP})")
    ap.add_argument("--apply", action="store_true",
                    help="удалить (без этого флага скрипт только печатает план)")
    ap.add_argument("--product", default="Singularity Todo")
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

    if args.keep < 1:
        ap.error("--keep должен быть >= 1: иначе стенд остаётся без истории")

    if not args.apply:
        doomed = plan_prune(client, args.product, args.keep)
        total = sum(len(v) for v in doomed.values())
        if not total:
            print(f"ротация не требуется: у каждого плана <= {args.keep} прогонов")
            return 0
        print(f"К удалению (окно: {args.keep} последних на план):")
        for plan_name, runs in sorted(doomed.items()):
            print(f"  {plan_name}: {len(runs)}")
            for run in runs[:5]:
                print(f"    #{run['id']} {run.get('summary', '')[:60]}")
            if len(runs) > 5:
                print(f"    … и ещё {len(runs) - 5}")
        print()
        print(f"Всего {total}. Ничего не удалено — повторите с --apply.")
        return 0

    removed = apply_prune(client, args.product, args.keep)
    total = sum(removed.values())
    for plan_name, count in sorted(removed.items()):
        print(f"удалено {count:4} прогонов: {plan_name}")
    print(f"итого удалено: {total}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KiwiError as exc:
        print(f"ОШИБКА: {exc}", file=sys.stderr)
        sys.exit(1)
    except KeyboardInterrupt:
        sys.exit(130)
