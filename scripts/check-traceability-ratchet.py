#!/usr/bin/env python3
"""check-traceability-ratchet.py — scenario coverage holes may shrink, never grow.

Why this exists (2026-10-05): the scenario layer reached 19 specs, 34 claimed
cells and 3 carriers, and nothing anywhere compared the hole count to anything.
A hole is not a mistake — it is the point of the matrix, the honest statement of
what the suite does not verify — but a *growing* hole count is a regression
nobody notices, and that is not a hypothetical here. The 15-spec auth/sync
tranche landed with zero carriers and took the matrix from 2 holes to 32 in one
commit. The diff was large, obvious, and green: no gate reads these numbers,
and `traceability validate` reports holes as information ("это не ошибка — это и
есть смысл матрицы") on the same run that a CI step then treats as a pass.

The contract is one-directional, and deliberately identical to
`check-baseline-ratchet.py` next door: filling a hole is always allowed, opening
one fails and must be justified in review. This is cheaper and more honest than
the alternative the plan rejected — a list saying "every scenario must have a
carrier" — because a hole is a legitimate state. A *ratcheting* hole count
makes growth loud and improvement optional, which is the same bargain the detekt
baseline makes and the only one that works when the honest answer is "not yet".

Two metrics, and the second one is not redundant
------------------------------------------------
`holes` is the count the user asked for. It sums over *cells*, so it cannot see
a change that leaves the number of cells alone while the number of scenarios in
trouble goes up. Measured by brute force over every per-scenario
(claimed, automated) state on both targets: 85 such trades exist, and the
simplest is a spec split. One scenario claiming `[android, desktop]` with no
carrier is 2 holes and 1 dark scenario. Split it into two specs, each claiming
one target, and give neither a carrier: still 2 holes, but now 2 dark
scenarios. Nothing was added to the suite, and one user scenario quietly
became two that nobody verifies. Splitting a spec per platform is exactly the
kind of change that looks like tidying up, so the trade is not hypothetical.

`dark_scenarios` counts rows instead of cells, and moves on that trade. Both
numbers fall when real work lands, and both are cheap to compute from files that
are already the input to `traceability validate` (no stand, no network, no test
run).

What this gate does NOT catch, stated rather than implied
---------------------------------------------------------
Two things, and the first is the deliberate way to accept growth: raising a
floor in the JSON. That edit is legal and is how a commit that knowingly opens
holes is recorded, so nothing here fails on it — the defence is that it is a
one-line diff in review next to the specs that caused it, and the ADR asks for
the reason in the commit message. It is *not* diffed against `HEAD` the way
`check-baseline-ratchet.py` diffs the baseline file, because that check has no
floor to raise: growth there is the file itself growing. Anyone who finds a way
to raise a floor without it showing in a diff has found a real hole, and the
cheapest detector is a reviewer, not another mechanism.

The second is deleting a scenario spec, which lowers both metrics. That is the
intended reading — removing an obligation is not a regression in the coverage of
what remains — but it does mean a commit that deletes specs and adds a carrier
passes here while losing coverage. The spec set is the reviewable artefact, and
`traceability validate` still fails if a spec claims a target with two carriers
or is linked twice, so deletion is visible in the diff and bounded by the
validation rules. What is deliberately not added is a floor on the number of
specs: freezing scenario count would make deleting a wrong spec a gate failure,
and a gate that punishes corrections is a gate people route around.

Usage:
  python3 scripts/check-traceability-ratchet.py [--config <path>] [--accept-growth]

Exit codes:
  0 — no metric grew (or a metric improved); 1 — a metric grew, or the check
  could not run.
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# The traceability package is a source-tree package under `infra/kiwi`, run as
# `python3 -m traceability` with PYTHONPATH set. Importing it here rather than
# shelling out to the CLI means the numbers this gate compares were produced by
# the same code the matrix was rendered from — a subprocess that failed to
# import would otherwise be indistinguishable from a clean run.
sys.path.insert(0, str(ROOT / "infra" / "kiwi"))

DEFAULT_CONFIG = "config/docs/traceability-ratchet.json"


@dataclass(frozen=True)
class Metric:
    """One ratcheted number and the direction that counts as regression."""

    key: str
    label: str
    worse_when: str  # "higher" — a hole count rises by getting worse


def _metrics(coverage) -> tuple[dict[str, int], list[str]]:
    """Compute the ratcheted numbers, and the ids behind the second one.

    `holes` is the matrix's own definition, reused rather than reimplemented: if
    the glyph logic ever gains a state, the hole count and the rendered `○` must
    not be able to disagree, and duplicating the predicate here is exactly how
    they would.

    `dark_scenarios` is defined over the same cells, skipping deprecated rows —
    a retired scenario is not a gap, the same argument that removed them from
    `Coverage.holes()` and, at the time, was itself a bug (a retired scenario
    was being reported as the one hole the system existed to surface). The ids
    come back separately because a failure that says "18" without saying which
    18 is a number, not a work item.
    """
    dark = [
        scenario
        for scenario, row in sorted(coverage.cells.items())
        if coverage.specs[scenario].is_claimed
        and any(cell.claimed for cell in row.values())
        and not any(cell.claimed and cell.automated for cell in row.values())
    ]
    return {"holes": len(coverage.holes()), "dark_scenarios": len(dark)}, dark


METRICS: tuple[Metric, ...] = (
    Metric(
        key="holes",
        label="claimed (scenario, target) cells with no automation",
        worse_when="higher",
    ),
    Metric(
        key="dark_scenarios",
        label="declared scenarios with a claim and no automation on any target",
        worse_when="higher",
    ),
)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", default=DEFAULT_CONFIG, help=f"floor file (default: {DEFAULT_CONFIG})")
    parser.add_argument(
        "--accept-growth",
        action="store_true",
        help=(
            "report growth as a warning instead of a failure. This exists for the "
            "commit that knowingly adds holes; the gate wiring in "
            "scripts/check-gate-wiring.py asserts the flag is absent from the "
            "registered invocation, because a permanently-passed escape hatch is "
            "not an escape hatch."
        ),
    )
    args = parser.parse_args()

    config_path = ROOT / args.config
    if not config_path.is_file():
        print(f"ERROR: ratchet file not found: {config_path}")
        return 1
    try:
        config = json.loads(config_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        print(f"ERROR: {config_path} is not valid JSON: {exc}")
        return 1

    # Imported here, after the config check, so a missing config reports itself
    # rather than surfacing as an ImportError from inside a try/except below.
    from traceability.coverage import build_coverage  # noqa: PLC0415
    from traceability.links import scan_all  # noqa: PLC0415
    from traceability.spec import load_specs  # noqa: PLC0415
    from traceability import SCENARIOS_DIR  # noqa: PLC0415

    specs = load_specs(SCENARIOS_DIR)
    links = scan_all(specs, ROOT)
    measured, dark_ids = _metrics(build_coverage(specs, links))

    floors = {f["metric"]: f for f in config.get("floors", [])}
    missing = [m.key for m in METRICS if m.key not in floors]
    if missing:
        print(
            f"ERROR: {config_path} has no floor for {', '.join(missing)}. A ratchet "
            f"metric without a recorded number cannot fail."
        )
        return 1

    grew: list[tuple[Metric, int, int]] = []
    for metric in METRICS:
        floor = floors[metric.key]
        allowed = int(floor["max"])
        actual = int(measured[metric.key])
        if actual > allowed:
            grew.append((metric, allowed, actual))
        elif actual < allowed:
            print(
                f"check-traceability-ratchet: {metric.key} is {actual}, below its "
                f"floor of {allowed} — lower the floor in {args.config} so a later "
                f"regression is caught at this level"
            )

    if not grew:
        total = measured["holes"]
        print(
            f"check-traceability-ratchet: OK — {total} hole(s), "
            f"{measured['dark_scenarios']} dark scenario(s), "
            f"{len(links)} carrier(s) across {len(specs)} spec(s)"
        )
        return 0

    print("")
    for metric, allowed, actual in grew:
        print(
            f"check-traceability-ratchet: FAIL — {metric.key} grew from {allowed} "
            f"to {actual} (+{actual - allowed})"
        )
        print(f"  {metric.label}")
    print("")
    print("These are scenarios the suite claims to verify and does not. Holes are")
    print("normal — a matrix with none of them is a matrix nobody believes — but a")
    print("count that only ever goes up is a queue nobody drains:")
    for scenario in dark_ids:
        print(f"  ○ {scenario}")
    print("")
    print("Either attach a carrier (the cheapest path is a reachability probe")
    print("first — see .agents/skills/singularity-todo-kiwi-tcm-stand/SKILL.md), or")
    print("state the growth in the commit message and re-run with --accept-growth.")
    print("Note that the spec's own comment header is the only place the plan is")
    print("recorded, so a hole opened here can be opened without anyone noticing.")
    return 0 if args.accept_growth else 1


if __name__ == "__main__":
    sys.exit(main())
