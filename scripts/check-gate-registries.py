#!/usr/bin/env python3
"""Both gate registries load, and the two agree on what exists.

There are two places a gate can be declared, and they are not the same place:

``scripts/ci/static-gates.sh``
    what ``sgate run-all`` reads, and what ``ci.yml``'s ``static`` job runs.
``scripts/src/sgate/commands/_registry.py``
    ``GATES``, what ``sgate <name>`` and ``load_gates()`` read.

``import sgate`` sees only the second one, so the first can be emptied,
truncated or renamed and the import stays green. Measured, before this script
existed: blanking ``static-gates.sh`` left ``python3 -c "import sgate"``
succeeding, left ``load_gates()`` still returning all 39 entries, and left
``sgate run-all`` printing ``No gates found.`` and **exiting 0** — a passing
``static`` job that had evaluated nothing. ``run-all`` now exits non-zero for
that case; this script is what notices before any gate runs.

Both halves are needed. The emptiness check catches a registry that lost its
contents, which is the failure that a zero exit code could hide. The agreement
check catches drift, which is a separate and currently *real* condition: the
shell registry holds 29 entries and the Python one holds 39, with 16 present
only in Python. Drift is reported as a warning rather than an error, because it
predates this script and failing on it would block every run on someone else's
debt; the count and the diff go to the log where they get read.

Run standalone::

    python3 scripts/check-gate-registries.py

Exit 0 when both load. Exit 1 when either is empty, or when the two disagree
under --strict.
"""
from __future__ import annotations

import argparse
import sys


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--strict",
        action="store_true",
        help="also fail when the two registries disagree (drift is a warning by default)",
    )
    args = parser.parse_args(argv)

    from sgate.commands._registry import GATES
    from sgate.commands.run_all import _load_static_gates
    from sgate.gates import load_gates

    try:
        static_gates = _load_static_gates()
        registry_gates = load_gates()
    except Exception as exc:  # noqa: BLE001 — any failure here means "not loadable"
        print(f"ERROR: the gate registries did not load: {exc}", file=sys.stderr)
        return 1

    if not static_gates:
        print(
            "ERROR: scripts/ci/static-gates.sh declared zero gates. "
            "`sgate run-all` would evaluate nothing at all, which is not a pass.",
            file=sys.stderr,
        )
        return 1
    if not registry_gates or not GATES:
        print(
            "ERROR: the Python gate registry is empty.",
            file=sys.stderr,
        )
        return 1

    static_names = {name for _, name, _ in static_gates}
    registry_names = {spec.name for spec in GATES}
    only_static = sorted(static_names - registry_names)
    only_registry = sorted(registry_names - static_names)

    print(
        f"gate registries: {len(static_gates)} gates in static-gates.sh, "
        f"{len(registry_gates)} in the Python registry"
    )

    if only_static or only_registry:
        message = (
            "the two gate registries disagree — "
            f"only in static-gates.sh: {only_static}; "
            f"only in _registry.GATES: {only_registry}. "
            "`sgate run-all` evaluates the first, `sgate <name>` the second, "
            "so which surface a caller uses decides which gates run."
        )
        if args.strict:
            print(f"ERROR: {message}", file=sys.stderr)
            return 1
        print(f"::warning ::{message}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())