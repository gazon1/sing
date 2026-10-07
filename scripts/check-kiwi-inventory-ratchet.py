#!/usr/bin/env python3
"""check-kiwi-inventory-ratchet.py — the Kiwi test inventory has not grown past its floor.

Why this exists (2026-10-07): the inventory count was a literal inside a test assertion, and
two unrelated commits raised it by hand on the same day — `abf88fc2` and `4733aec4` — each
landing on the same value, each producing a merge conflict in `scripts/tests/test_kiwi_sync.py`.
The number was fine; where it lived was the defect. A metric edited inside a test file is a
metric two branches edit, and the merge that reconciles them resolves a number rather than a
decision.

So it is here, in the shape `check-traceability-ratchet.py` already uses: the measurement lives
in a JSON floor file next to its own reason, and growth is a one-line edit there instead of an
edit to a test both branches are touching.

## What this is and is not

It is **not** a coverage claim. A run where it fires is a prompt to look, not evidence that
anything regressed — the two commits that set it off were both legitimate.

It is also not the primary check, and the things it looks like it should be are already covered
elsewhere:

- every skip is justified by the file that earns it (`unjustified_skips` in test_kiwi_sync);
- every `*Test.kt` on disk is in the inventory or justified-skip (same file);
- the runnable-test predicate has synthetic controls (`RunnableTestClassTest`).

None of those can catch a regression *in the predicate*, because they all ask it the same
question. What remains here is the coarse alarm for an inventory that moved in a way no
synthetic control anticipated, which is exactly what fired twice on 2026-10-07.

Usage:
    python3 scripts/check-kiwi-inventory-ratchet.py [--config config/docs/kiwi-inventory-ratchet.json]
    python3 scripts/check-kiwi-inventory-ratchet.py --accept-growth

Exit codes:
    0 — both metrics at or under their floor
    1 — a metric grew past its floor, or the floor file is unusable
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_CONFIG = "config/docs/kiwi-inventory-ratchet.json"

#: Metric key -> whether a larger number is worse. Both are "cap", so neither
#: ratchets downward the way `holes` does.
METRICS = ("inventory", "skipped")


def load_scanner():
    """`infra/kiwi/sync.py`, by path.

    Loaded rather than imported so the gate and `scripts/tests/test_kiwi_sync.py`
    are two readers of one scanner instead of two scanners. A gate that
    recomputed the predicate would agree with itself forever, which is the
    failure this file was written next to.
    """
    path = ROOT / "infra" / "kiwi" / "sync.py"
    spec = importlib.util.spec_from_file_location("kiwi_sync_inventory_ratchet", path)
    module = importlib.util.module_from_spec(spec)
    # @dataclass resolves cls.__module__ through sys.modules at class-definition
    # time, so the entry must exist before exec_module — without it the load
    # dies with "'NoneType' object has no attribute '__dict__'".
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def measure() -> dict[str, int]:
    module = load_scanner()
    inventory = module.scan_repository()
    return {"inventory": len(inventory), "skipped": len(module.SKIPPED_NON_TESTS)}


def check(config: dict, measured: dict[str, int]) -> tuple[list[tuple[str, int, int]], list[str]]:
    """Floors exceeded and recorded measurements gone stale, as lists of text."""
    floors = {f["metric"]: f for f in config.get("floors", [])}
    missing = [m for m in METRICS if m not in floors]
    if missing:
        raise ValueError(f"no floor for {', '.join(missing)} — a ratchet metric without a recorded number cannot fail")

    exceeded: list[tuple[str, int, int]] = []
    stale: list[str] = []
    for metric in METRICS:
        allowed = int(floors[metric]["max"])
        actual = int(measured[metric])
        if actual > allowed:
            exceeded.append((metric, allowed, actual))

        # `measured` is the number as of the recorded commit, and it is what catches a
        # floor nobody updated. Deliberately *not* a "you are below your ceiling" line:
        # headroom above a cap is the point, and a gate that says so on every green run
        # teaches its reader to skip its output — which is how the next real finding goes
        # unread. Staleness is rare and worth saying; permanent headroom is not.
        recorded = floors[metric].get("measured")
        if recorded is not None and int(recorded) != actual:
            stale.append(
                f"{metric}: the floor file records measured={recorded} but the scanner "
                f"finds {actual} — update `measured` (and `commit`) in the floor file"
            )
    return exceeded, stale


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--config", default=DEFAULT_CONFIG, help=f"floor file (default: {DEFAULT_CONFIG})")
    parser.add_argument(
        "--accept-growth",
        action="store_true",
        help=(
            "report growth as a warning instead of a failure. This exists for the commit "
            "that knowingly adds test classes; check-gate-wiring.py asserts the flag is "
            "absent from the registered invocation, because a permanently-passed escape "
            "hatch is not an escape hatch."
        ),
    )
    args = parser.parse_args(argv)

    config_path = ROOT / args.config
    if not config_path.is_file():
        print(f"ERROR: ratchet file not found: {config_path}")
        return 1
    try:
        config = json.loads(config_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        print(f"ERROR: {config_path} is not valid JSON: {exc}")
        return 1

    measured = measure()
    try:
        exceeded, stale = check(config, measured)
    except ValueError as exc:
        print(f"ERROR: {config_path}: {exc}")
        return 1

    if stale:
        for line in stale:
            print(f"check-kiwi-inventory-ratchet: {line}")
        print(
            "\nThe recorded measurement has drifted from the tree. That is not a failure of "
            "the scanner — it is a floor file describing a different commit.",
            file=sys.stderr,
        )
        return 1

    if exceeded:
        for metric, allowed, actual in exceeded:
            print(
                f"check-kiwi-inventory-ratchet: {metric} is {actual}, over its ceiling of "
                f"{allowed} (+{actual - allowed})"
            )
        print(
            "\nBoth ceilings cap the inventory from above, so growth in this direction is "
            "usually legitimate — a new test class. Read the floor's `note` before deciding; "
            "if it is legitimate, raise `max` there and say why in the commit message.\n"
            "If it is not, the filter has started counting things it should not."
        )
        if args.accept_growth:
            return 0
        return 1

    print(
        "check-kiwi-inventory-ratchet: OK — "
        + ", ".join(f"{m}={measured[m]}" for m in METRICS)
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())