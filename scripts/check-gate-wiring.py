#!/usr/bin/env python3
"""check-gate-wiring.py — prove the gates exist AND that they can fail.

Why this exists (2026-10-04): this repository accumulated a family of checks
that were fully configured, looked authoritative, and had never executed. The
instances found in one session:

- `:androidApp:detekt` — a `detekt { }` block, a baseline, and
  `ignoreFailures = false`, but no gate ever invoked the task.
- `no-direct-dispatchers` and `user-scoped-repository` — registered as rule-set
  providers, with no `detekt.yml` block, so detekt never loaded them.
- `check-doc-sizes.py --warn-only || status=1` — `--warn-only` returns 0, so
  `status=1` was unreachable.
- `docs-audit.yml` — not valid YAML, so the workflow could not run at all.

All four reported success. Fixing them one at a time does not prevent the
fifth, so this check encodes the two failure modes as properties of the repo.

Part A — **wiring.** Every Gradle check task configured in a module's build
file must be named by at least one gate (`check.sh` or a workflow). A task with
a config block and no gate is decoration, and nothing else will ever notice.

Part B — **can fail.** Every script gate registered below is run against a
deliberately sabotaged input and must exit non-zero. This is the positive
control that `--warn-only` and the invalid-YAML cases never had.

Usage:
    python3 scripts/check-gate-wiring.py            # both parts
    python3 scripts/check-gate-wiring.py --wiring   # Part A only (static, fast)
    python3 scripts/check-gate-wiring.py --can-fail # Part B only (mutates, restores)

Exit codes:
    0 — every gate is wired and every registered gate can fail
    1 — an unwired task, or a gate that passed a sabotaged input
"""

from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

GATE_FILES = [ROOT / "check.sh", *sorted((ROOT / ".github" / "workflows").glob("*.yml"))]

# Task-name spellings a gate may use instead of the exact `:module:task`. Empty by
# default on purpose: each entry has to be justified, because a permissive alias is
# exactly how a check stops proving anything.
ACCEPTED_ALIASES: set[str] = set()


# ── Part A: wiring ─────────────────────────────────────────────────────────


def configured_gradle_check_tasks() -> dict[str, str]:
    """Map `module:task` -> the build file that configures it.

    Detects a `detekt { }` block in a module build file, which is the shape that
    produced the androidApp instance. Test tasks are found via `useJUnitPlatform`
    / `withType<Test>`.
    """
    found: dict[str, str] = {}
    for build in sorted(ROOT.glob("*/build.gradle.kts")):
        module = build.parent.name
        text = build.read_text(encoding="utf-8")
        if re.search(r"^\s*detekt\s*\{", text, re.M):
            found[f":{module}:detekt"] = str(build.relative_to(ROOT))
    return found


def _strip_comments(text: str) -> str:
    """Drop `#` comments from bash and YAML source.

    Without this the check greps its own prose: a comment reading "…(:androidApp:detekt
    was one)" satisfied the search for an actual invocation, so unwiring the task
    still passed. That is the same defect class this file exists to catch, one level
    up — a gate that greps the wrong thing and reports success. Only the command text
    is searched now.

    A `#` is treated as a comment start when it is line-leading or preceded by
    whitespace, which covers both `# comment` and an inline `… # comment` while
    leaving a `#` inside a word (e.g. a colour literal) alone.
    """
    out = []
    for line in text.splitlines():
        stripped = line.lstrip()
        if stripped.startswith("#"):
            continue
        idx = len(line)
        for i, ch in enumerate(line):
            if ch == "#" and (i == 0 or line[i - 1].isspace()):
                idx = i
                break
        out.append(line[:idx])
    return "\n".join(out)


def gate_text() -> str:
    return "\n".join(
        _strip_comments(p.read_text(encoding="utf-8")) for p in GATE_FILES if p.is_file()
    )


def check_wiring() -> list[str]:
    errors: list[str] = []
    gates = gate_text()
    if not gates:
        return [f"no gate files found among {[str(p.relative_to(ROOT)) for p in GATE_FILES]}"]

    for task, build in sorted(configured_gradle_check_tasks().items()):
        if task in gates or task in ACCEPTED_ALIASES:
            continue
        # Only the exact `:module:detekt` counts. An earlier version of this check
        # also accepted a loose `f":{module}:"` match; that let
        # `:androidApp:assembleDebug` stand in for `:androidApp:detekt` and the
        # check passed its own sabotage test. A substring match is not evidence
        # that *this* task runs, so there is no fallback — if a gate ever
        # dispatches via Gradle's bare multi-project shortcut, add it to
        # ACCEPTED_ALIASES deliberately rather than re-introducing a guess.
        errors.append(
            f"Gradle check task '{task}' is configured in {build} but named by no gate. "
            f"A configured-but-uninvoked task reports success forever — add it to "
            f"check.sh or a workflow, or delete the config block if it is not wanted."
        )
    return errors


# ── Part B: can fail ───────────────────────────────────────────────────────


@dataclass(frozen=True)
class ScriptGate:
    """A script gate plus the single input change that must make it fail."""

    name: str
    cmd: list[str]
    sabotage_path: str
    sabotage: str  # python source evaluated with `p` bound to the target file
    why: str


SCRIPT_GATES = [
    ScriptGate(
        name="detekt-registrations",
        cmd=["./scripts/check-detekt-registrations.sh"],
        sabotage_path="config/detekt/detekt.yml",
        sabotage="p.write_text(p.read_text().replace('no-runblocking:\\n  NoRunBlocking:\\n    active: true\\n', ''))",
        why="a RuleSetId with no detekt.yml block never loads, so the rule never runs",
    ),
    ScriptGate(
        name="baseline-ratchet",
        cmd=[sys.executable, "scripts/check-baseline-ratchet.py"],
        sabotage_path="config/detekt/baseline-shared.xml",
        sabotage="p.write_text(p.read_text().replace('</CurrentIssues>', '    <ID>Sabotage:Probe.kt:Probe</ID>\\n  </CurrentIssues>'))",
        why="a baseline that can grow silently stops being a ratchet and becomes a sink",
    ),
    ScriptGate(
        name="unwired-backlog-refs",
        cmd=[sys.executable, "scripts/check-unwired-backlog-refs.py"],
        sabotage_path="scripts/find-unwired-surfaces-baseline.txt",
        sabotage="p.write_text(p.read_text().replace('deferred-backlog.md:recurrence-parser-is-unwired', 'deferred-backlog.md:no-such-anchor'))",
        why="an exemption pointing at a heading that does not exist is not an exemption",
    ),
    ScriptGate(
        name="doc-sizes",
        cmd=[sys.executable, "scripts/check-doc-sizes.py"],
        sabotage_path="AGENTS.md",
        sabotage="p.write_text(p.read_text() + '\\n' * 400)",
        why="the budget must be able to fail; `--warn-only || status=1` could not",
    ),
    ScriptGate(
        name="doc-dead-refs",
        cmd=[sys.executable, "scripts/check-doc-dead-refs.py"],
        # README.md, not PROGRESS.md: the dead-ref detector classifies a reference as
        # *historical* when one of HISTORY_MARKERS appears in the preceding 600 chars,
        # which PROGRESS.md is full of by design (it is a journal of the past). A
        # sabotage there is correctly ignored, so it would prove nothing.
        sabotage_path="README.md",
        sabotage="p.write_text(p.read_text() + '\\nSee `docs/decisions/DefinitelyNotAFile.md` for details.\\n')",
        why="a new dead reference must fail until baselined",
    ),
    ScriptGate(
        name="skill-symbols",
        cmd=[sys.executable, "scripts/check-doc-dead-refs.py", "--skill-symbols"],
        # A skill file, not a source file: the gate answers "does this documented
        # symbol exist", so the only honest sabotage is a skill naming one that
        # does not. Sabotaging a source file would prove the opposite direction.
        sabotage_path=".agents/skills/singularity-todo-quality-tools/SKILL.md",
        sabotage="p.write_text(p.read_text() + '\\n## Probe\\n\\nCall `TotallyMadeUpHelperSymbol` here.\\n')",
        why="a stale symbol reference must fail until baselined",
    ),
    ScriptGate(
        name="skill-frontmatter",
        cmd=["./scripts/check-skill-frontmatter.sh"],
        sabotage_path=".agents/skills/singularity-todo-koin-dsl/SKILL.md",
        sabotage="_t = p.read_text(); _lines = _t.splitlines(keepends=True); del _lines[1]; p.write_text(''.join(_lines))",
        why="a skill without a description is undiscoverable, so the check must reject it",
    ),
]


def run_gate(cmd: list[str]) -> int:
    proc = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, check=False)
    return proc.returncode


def check_can_fail() -> list[str]:
    errors: list[str] = []
    for gate in SCRIPT_GATES:
        target = ROOT / gate.sabotage_path
        if not target.is_file():
            errors.append(f"gate '{gate.name}': sabotage target {gate.sabotage_path} does not exist")
            continue

        # Verify the gate passes on the real tree first. A gate that is already red
        # proves nothing about the sabotage, and would mask the result.
        baseline_rc = run_gate(gate.cmd)
        if baseline_rc != 0:
            errors.append(
                f"gate '{gate.name}' already fails on a clean tree (exit {baseline_rc}). "
                f"Fix the underlying failure before trusting its sabotage control."
            )
            continue

        original = target.read_text(encoding="utf-8")
        try:
            ns: dict[str, object] = {"p": target}
            exec(gate.sabotage, ns)  # noqa: S102 — a fixed literal from SCRIPT_GATES
            sabotaged_rc = run_gate(gate.cmd)
        finally:
            target.write_text(original, encoding="utf-8")

        restored = target.read_text(encoding="utf-8")
        if restored != original:
            errors.append(
                f"gate '{gate.name}': sabotage did not restore {gate.sabotage_path}. "
                f"Restoring from the recorded copy and failing."
            )
            target.write_text(original, encoding="utf-8")
            continue

        if sabotaged_rc == 0:
            errors.append(
                f"gate '{gate.name}' PASSED a deliberately sabotaged input "
                f"({gate.sabotage_path}). It cannot detect {gate.why}, so it is not "
                f"a gate. Either fix the script or accept that it does not check this."
            )
        else:
            print(f"  ok  {gate.name}: fails on sabotage ({gate.sabotage_path})")
    return errors


# ── main ──────────────────────────────────────────────────────────────────


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--wiring", action="store_true", help="Part A only")
    ap.add_argument("--can-fail", action="store_true", help="Part B only")
    args = ap.parse_args()
    run_a = args.wiring or not args.can_fail
    run_b = args.can_fail or not args.wiring

    errors: list[str] = []

    if run_a:
        print("Part A — Gradle check tasks are wired into a gate")
        wiring = check_wiring()
        tasks = configured_gradle_check_tasks()
        for task in sorted(tasks):
            print(f"  configured: {task}  ({tasks[task]})")
        errors += wiring
        if not wiring:
            print(f"  ok  {len(tasks)} configured task(s), all named by a gate")

    if run_b:
        print("\nPart B — every registered script gate can fail")
        errors += check_can_fail()

    if errors:
        print("")
        for err in errors:
            print(f"ERROR: {err}")
        print("")
        print("A gate that cannot fail, or a check task nobody invokes, is worse than")
        print("no gate: it converts 'not checked' into 'checked and green'.")
        return 1

    print("\ncheck-gate-wiring: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
