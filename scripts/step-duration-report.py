#!/usr/bin/env python3
"""Aggregates step durations from desktop test step-recorder output.

Reads every steps.txt under desktopApp/build/diagnostics/ (one per test class,
written on failure always and on success with -Dsingularity.test.steps=true) and
prints a per-step-name duration profile: count, median, p95, max.

Usage:
    ./gradlew :desktopApp:test -Dsingularity.test.steps=true   # produce steps.txt
    python3 scripts/step-duration-report.py                    # print the profile

Lines look like:
    +0.12s  tapTab(Inbox)    OK  0.31s
    +0.00s  awaitTag(tag)     FAIL  5.01s  AssertionError: ...
"""

import re
import sys
from collections import defaultdict
from pathlib import Path

DIAGNOSTICS = Path("desktopApp/build/diagnostics")

# name(detail) is recovered up to the double-space separator; detail may itself
# contain spaces, so the separator run is what delimits fields.
STEP_LINE = re.compile(r"^\+(?P<offset>[\d.]+)s\s+(?P<name>.+?)\s{2,}(?P<ok>OK|FAIL)\s+(?P<dur>[\d.]+)s")

# detail varies in width; name without detail is recovered by trimming a trailing
# " (detail)" only when the detail was parenthesised, otherwise kept whole.


def main() -> int:
    if not DIAGNOSTICS.is_dir():
        print(f"no diagnostics dir at {DIAGNOSTICS}; run the suite first", file=sys.stderr)
        return 1

    durations: dict[str, list[float]] = defaultdict(list)
    fails: dict[str, int] = defaultdict(int)
    files = 0

    for steps in DIAGNOSTICS.glob("*/attempt-*/steps.txt"):
        files += 1
        for line in steps.read_text().splitlines():
            m = STEP_LINE.match(line)
            if not m:
                continue
            # The step name as recorded: "name" or "name(detail)". Aggregate on the
            # name without the detail — detail values (tags, titles) are per-test.
            raw = m["name"]
            name = raw.split("(", 1)[0] if "(" in raw and raw.endswith(")") else raw
            dur = float(m["dur"])
            durations[name].append(dur)
            if m["ok"] == "FAIL":
                fails[name] += 1

    if files == 0:
        print(f"no steps.txt files under {DIAGNOSTICS}", file=sys.stderr)
        return 1

    def pct(values: list[float], p: float) -> float:
        ordered = sorted(values)
        idx = min(len(ordered) - 1, int(round(p / 100 * (len(ordered) - 1))))
        return ordered[idx]

    print(f"{files} steps.txt file(s)\n")
    print(f"{'step':<28} {'n':>4} {'median':>8} {'p95':>8} {'max':>8} {'fails':>6}")
    for name in sorted(durations, key=lambda n: -pct(durations[n], 95)):
        ds = durations[name]
        print(
            f"{name:<28} {len(ds):>4} {pct(ds, 50):>7.2f}s {pct(ds, 95):>7.2f}s"
            f" {max(ds):>7.2f}s {fails[name]:>6}"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
