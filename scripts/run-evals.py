#!/usr/bin/env python3
"""
Workflow Evals Runner

Runs agent evaluation tasks and grades results.
Usage: python3 scripts/run-evals.py [--task <name>|all]
"""

import argparse
import subprocess
import sys
import os
from pathlib import Path
import json
import re

EVALS_DIR = Path(__file__).parent.parent / "evals"
TASKS_DIR = EVALS_DIR / "tasks"
BASELINE_FILE = EVALS_DIR / "baseline.json"


def load_yaml(path: Path) -> dict:
    import yaml
    with open(path) as f:
        return yaml.safe_load(f)


def run_task(task_name: str, verbose: bool = False) -> dict:
    task_file = TASKS_DIR / f"{task_name}.yaml"
    if not task_file.exists():
        return {"error": f"Task not found: {task_name}"}

    task_def = load_yaml(task_file)

    # For now, we just verify the task definition is valid
    # A real implementation would invoke the AI agent with the task
    result = {
        "task": task_name,
        "status": "defined",
        "description": task_def.get("description", ""),
        "has_task": "task" in task_def,
        "has_constraints": "constraints" in task_def,
        "has_expected": "expected" in task_def,
    }
    return result


def grade_task(task_name: str, result: dict) -> dict:
    """Grade a task result. Returns pass/fail and evidence."""
    task_file = TASKS_DIR / f"{task_name}.yaml"
    task_def = load_yaml(task_file)

    passed = True
    evidence = []

    # Check task definition
    if "error" in result:
        return {"grade": "error", "evidence": [result["error"]]}

    # Grader 1: task defined
    if result.get("has_task"):
        evidence.append("✓ task definition present")
    else:
        evidence.append("✗ task definition missing")
        passed = False

    # Grader 2: constraints defined
    if result.get("has_constraints"):
        evidence.append("✓ constraints defined")
    else:
        evidence.append("✗ constraints missing")
        passed = False

    # Grader 3: expected outcome defined
    if result.get("has_expected"):
        evidence.append("✓ expected outcome defined")
    else:
        evidence.append("✗ expected outcome missing")
        passed = False

    return {
        "grade": "pass" if passed else "fail",
        "evidence": evidence,
    }


def main():
    parser = argparse.ArgumentParser(description="Run workflow evals")
    parser.add_argument("--task", default="all", help="Task name or 'all'")
    parser.add_argument("--verbose", "-v", action="store_true")
    parser.add_argument("--json", action="store_true", help="Output JSON")
    args = parser.parse_args()

    if args.task == "all":
        tasks = [f.stem for f in TASKS_DIR.glob("*.yaml")]
    else:
        tasks = [args.task]

    results = []
    for task_name in sorted(tasks):
        result = run_task(task_name, verbose=args.verbose)
        graded = grade_task(task_name, result)
        results.append({
            "task": task_name,
            "definition": result,
            "grade": graded,
        })

    if args.json:
        print(json.dumps(results, indent=2))
        return

    # Human-readable output
    all_passed = True
    for r in results:
        status = "✅" if r["grade"]["grade"] == "pass" else "❌"
        print(f"{status} {r['task']}")
        for e in r["grade"]["evidence"]:
            print(f"   {e}")
        if r["grade"]["grade"] != "pass":
            all_passed = False

    total = len(results)
    passed = sum(1 for r in results if r["grade"]["grade"] == "pass")
    print(f"\n{passed}/{total} tasks passed")

    sys.exit(0 if all_passed else 1)


if __name__ == "__main__":
    main()
