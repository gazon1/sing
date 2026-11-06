#!/usr/bin/env python3
"""AI benchmark harness for stale-knowledge detection (plan §6).

Branches: A baseline (no knowledge), B unmarked (knowledge, no status), C status-aware (show/status).
Runs a task N times per branch; records stale detection, invariants, sources.

This script only scaffolds runs and aggregates results; the actual agent invocation
is performed by the operator (model + commit pinned). Output: CSV rows.
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--branch", choices=["A", "B", "C"], required=True)
    ap.add_argument("--task", required=True)
    ap.add_argument("--run", type=int, required=True)
    ap.add_argument("--stale-detected", choices=["yes", "no"], required=True)
    ap.add_argument("--invariants-missed", type=int, default=0)
    ap.add_argument("--false-claims", type=int, default=0)
    ap.add_argument("--out", default="benchmark-results.csv")
    args = ap.parse_args()

    row = {
        "branch": args.branch,
        "task": args.task,
        "run": args.run,
        "stale_detected": args.stale_detected,
        "invariants_missed": args.invariants_missed,
        "false_claims": args.false_claims,
    }
    out = Path(args.out)
    write_header = not out.exists()
    with out.open("a", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(row.keys()))
        if write_header:
            w.writeheader()
        w.writerow(row)
    print(json.dumps(row))


if __name__ == "__main__":
    main()
