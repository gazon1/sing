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
import os
import pathlib
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
    sabotage: str  # python source evaluated with `p` bound to the target
    why: str
    # A directory target is restored by mtime, not by content: `check-openspec-stale`
    # reads staleness from `stat().st_mtime`, so a content comparison would report
    # a failed restore for a mutation that was correct.
    target_is_dir: bool = False


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
        name="backlog-status",
        cmd=[sys.executable, "scripts/check-backlog-status.py"],
        sabotage_path="docs/decisions/deferred-backlog.md",
        # Strip the status line from the first entry, which is what 42 of them
        # looked like before 2026-10-05. A gate that only ever sees a file where
        # every entry is annotated has never been shown to catch the case it
        # exists for.
        sabotage="p.write_text(p.read_text().replace('**Status: OPEN**', '', 1))",
        why="an entry with no readable status cannot be triaged, and a queue nobody can triage is not a queue",
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


# ── Part F: the registry is derived from registration ───────────────────────
#
# `SCRIPT_GATES` above is a hand-written list, and a hand-written list of what
# has been verified is the same defect one level up from the ones this file
# exists to catch. Measured 2026-10-05: 18 gate scripts are invoked by a gate,
# 8 had a control, and the 10 without one were indistinguishable in the
# registry from the 8 with one. The three that decide whether a test run counts
# as evidence — `check-test-runs.py`, `check-coverage.py`, `check-flaky-tests.py`
# — were among the ten.
#
# So the list is checked against the registration instead of trusted. A gate
# named by `check.sh`, a workflow, or a `just` recipe must appear here or in
# `GATE_EXEMPTIONS` with a reason, and adding a gate to a recipe is what makes
# it show up — which is the whole point. Part A already derives Gradle tasks
# from build files for the same reason.
#
# Two kinds of control, because "sabotage one file" does not cover every gate:
#
#   SABOTAGE — the gate reads a committed file, so mutating that file and
#              re-running it is a complete control. `check-test-runs.py` against
#              a raised floor, `check-rule-intent.py` against an undeclared
#              rule in the baseline.
#   FIXTURE  — the gate reads something that does not exist in the repository:
#              a directory of JUnit XML, a captured Gradle log, a Kover report.
#              Mutating a file proves nothing, because no file is the input. The
#              control synthesises the input instead and asserts the gate
#              rejects it. This is the form the run-evidencing gates need, and
#              it is a real control rather than a weaker one — `check-flaky-tests`
#              rejecting a test that passed yesterday and fails today is exactly
#              the property it exists to catch.
#
# Every entry below was measured: each was run clean (exit 0) and then run
# against its own control (non-zero). An entry that was not measured is not
# here.

@dataclass(frozen=True)
class FixtureGate:
    """A gate whose input is synthesised rather than mutated.

    `setup` is python source with `root` bound to a temporary directory; it must
    create whatever the gate reads and return nothing. `cmd` is then run with
    `{tmp}` substituted, and must exit non-zero.

    `needs_clean_run` records whether the gate can be run at all without its
    input. Two of the three here cannot: `check-flaky-tests.py` requires
    `--current` and `check-coverage-measurement.py` requires a `log`, so there
    is no invocation that means "the clean repository" to them. The clean-run
    guard is not weakened for them — it is *inapplicable*, and saying so
    explicitly is the point. A guard that is silently skipped for an unstated
    reason is the same defect one level up: a check that cannot run, reported
    as a check that passed.
    """

    name: str
    cmd: list[str]
    setup: str
    why: str
    # True when the gate has a meaningful clean-tree invocation, i.e. the
    # "already red would mask the sabotage" guard applies.
    needs_clean_run: bool = True


FIXTURE_GATES = [
    FixtureGate(
        name="flaky-tests",
        cmd=[sys.executable, "scripts/check-flaky-tests.py", "--current", "{tmp}/cur", "--previous", "{tmp}/prev"],
        setup=(
            "import pathlib\n"
            "S = ('<testsuite name=\"{n}\" tests=\"1\" failures=\"{f}\">'\n"
            "     '<testcase classname=\"A\" name=\"{n}\">{b}</testcase></testsuite>')\n"
            "for sub, state in (('cur', 'fail'), ('prev', 'pass')):\n"
            "    d = root / sub\n"
            "    d.mkdir(parents=True, exist_ok=True)\n"
            "    body = '<failure message=\"boom\"/>' if state == 'fail' else ''\n"
            "    (d / 'TEST-A.xml').write_text(S.format(n='one', f=1 if state == 'fail' else 0, b=body))\n"
        ),
        why="a test that passed yesterday and fails today must be reported until it is acknowledged",
        needs_clean_run=False,
    ),
    FixtureGate(
        name="coverage",
        cmd=[sys.executable, "scripts/check-coverage.py", "--report", "{tmp}/report.xml"],
        setup=(
            "root.joinpath('report.xml').write_text(\n"
            "    '<?xml version=\"1.0\"?><report name=\"Kover\"><package name=\"p\">'\n"
            "    '<class name=\"A\" sourcefilename=\"A.kt\"><method name=\"m\" covered=\"false\">'\n"
            "    '<counter type=\"INSTRUCTION\" missed=\"10\" covered=\"0\"/></method>'\n"
            "    '</class></package></report>')\n"
        ),
        why="a coverage report that measures nothing must not satisfy a floor that was recorded from a real run",
        needs_clean_run=False,
    ),
    FixtureGate(
        name="coverage-measurement",
        cmd=[sys.executable, "scripts/check-coverage-measurement.py", "{tmp}/build.log"],
        setup=(
            "root.joinpath('build.log').write_text(\n"
            "    '> Task :shared:jvmTest UP-TO-DATE\\nBUILD SUCCESSFUL in 3s\\n')\n"
        ),
        why="a coverage report produced without the tests running describes no execution at all",
        needs_clean_run=False,
    ),
]


# Gates that are invoked but deliberately have no control in this file. Each one
# names what its control is instead, because "no control" is only acceptable
# when something else is doing the proving — and the reader has to be told what.
GATE_EXEMPTIONS: dict[str, str] = {
    "scripts/check-gate-wiring.py": (
        "this file. Its controls are its own Parts, and its self-tests "
        "(scripts/tests/test_check_gate_wiring.py) pin each part; a registry "
        "that had to sabotage itself to prove itself would be circular."
    ),
    "scripts/check-gate-honesty.py": (
        "this file's own premise, one level down: it plants a real detekt "
        "violation and asserts the report names it, so it is a positive control "
        "rather than a gate needing one. Its control is scripts/tests/"
        "test_check_gate_honesty.py, including "
        "`test_a_probe_that_does_not_fire_is_a_failure_not_a_pass`."
    ),
}


# Gates whose control mutates a committed file. Kept beside FIXTURE_GATES so the
# two kinds read as one registry; `check_can_fail` dispatches on which list an
# entry came from.
SABOTAGE_ONLY_GATES = [
    ScriptGate(
        name="test-runs",
        cmd=[sys.executable, "scripts/check-test-runs.py", "--require", "shared:jvmTest,desktopApp:test"],
        sabotage_path="config/docs/test-runs-baseline.txt",
        sabotage="p.write_text(p.read_text().replace('shared:jvmTest 1788 0', 'shared:jvmTest 99999 0'))",
        why="a test source set that ran fewer tests than its recorded floor means coverage was lost",
    ),
    ScriptGate(
        name="kiwi-gaps",
        cmd=[sys.executable, "scripts/check-kiwi-gaps.py"],
        sabotage_path="config/docs/kiwi-gaps-baseline.txt",
        sabotage="p.write_text(p.read_text().replace('Automated — mcp-server 1', 'Automated — mcp-server 0'))",
        why="a plan whose never-run cases rise above its floor means the plan is not being run",
    ),
    ScriptGate(
        name="adr-references",
        cmd=[sys.executable, "scripts/check-adr-references.py"],
        sabotage_path="README.md",
        sabotage="p.write_text(p.read_text() + '\\nSee `docs/decisions/2026-10-05-nonexistent-adr.md` for details.\\n')",
        why="a prose reference to a dated-ADR slug carries no path, so a moved or deleted ADR leaves a dangling claim nothing else sees",
    ),
    ScriptGate(
        name="rule-intent",
        cmd=[sys.executable, "scripts/check-rule-intent.py"],
        sabotage_path="config/detekt/baseline-shared.xml",
        sabotage="p.write_text(p.read_text().replace('</CurrentIssues>', '    <ID>TotallyMadeUpRule:Probe.kt:1</ID>\\n  </CurrentIssues>', 1))",
        why="a rule producing findings that nobody declared means it is running on detekt's defaults, unchosen",
    ),
    ScriptGate(
        name="openspec-stale",
        cmd=[sys.executable, "scripts/check-openspec-stale.py"],
        sabotage_path="openspec/changes/detekt-rule-has-positive-control",
        # A directory, not a file, and the mutation is its mtime: the gate reads
        # staleness from `stat().st_mtime`, so no file content would move it.
        sabotage="import os, time; old = time.time() - 30 * 86400; os.utime(p, (old, old))",
        why="an active change nobody has returned to in three weeks is a decision deferred past the point of usefulness",
        target_is_dir=True,
    ),
    ScriptGate(
        name="test-task-inputs",
        cmd=[sys.executable, "scripts/check-test-task-inputs.py"],
        sabotage_path="shared/build.gradle.kts",
        # Remove the *covering input*, not the root property. The rule is
        # one-directional — a root declared but uncovered — so deleting the
        # property instead leaves nothing to check and the gate passes on a
        # tree that has lost the property entirely. The first attempt at this
        # control did exactly that and returned 0, which is the failure this
        # file exists to catch, in its own registry.
        sabotage=(
            "p.write_text(p.read_text().replace("
            "'    inputs.dir(layout.projectDirectory.dir(\"../Maestro\"))\\n'"
            "        '        .withPropertyName(\"maestroFlows\")\\n'\n"
            "        '        .withPathSensitivity(PathSensitivity.RELATIVE)\\n', '', 1))"
        ),
        why="a test task reading a tree outside its module reports a stale verdict when that tree is not an input",
    ),
]


def run_gate(cmd: list[str]) -> int:
    proc = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, check=False)
    return proc.returncode


def check_can_fail() -> list[str]:
    errors: list[str] = []
    # `SCRIPT_GATES + SABOTAGE_ONLY_GATES` is the sabotage registry;
    # `FIXTURE_GATES` is the same property proved a different way. Both are
    # checked here so the two kinds cannot drift apart in *when* they run.
    for gate in [*SCRIPT_GATES, *SABOTAGE_ONLY_GATES]:
        target = ROOT / gate.sabotage_path
        exists = target.is_dir() if gate.target_is_dir else target.is_file()
        if not exists:
            kind = "directory" if gate.target_is_dir else "file"
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

        if gate.target_is_dir:
            # Only the mtime is disturbed, so only the mtime has to come back.
            original_mtime = target.stat().st_mtime
            try:
                ns: dict[str, object] = {"p": target}
                exec(gate.sabotage, ns)  # noqa: S102 — a fixed literal from the registry
                sabotaged_rc = run_gate(gate.cmd)
            finally:
                os.utime(target, (original_mtime, original_mtime))
        else:
            original = target.read_text(encoding="utf-8")
            try:
                ns = {"p": target}
                exec(gate.sabotage, ns)  # noqa: S102 — a fixed literal from the registry
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

    errors += check_fixture_gates()
    return errors


def check_fixture_gates() -> list[str]:
    """Prove a gate that reads a synthesised input can fail on it.

    The clean-tree run comes first for the same reason as the sabotage path: a
    gate that is already red proves nothing, and a fixture that makes it red for
    an unrelated reason is indistinguishable from one that caught the fixture.
    """
    errors: list[str] = []
    for gate in FIXTURE_GATES:
        if gate.needs_clean_run:
            baseline_rc = run_gate(gate.cmd)
            if baseline_rc != 0:
                errors.append(
                    f"gate '{gate.name}' already fails on a clean tree (exit {baseline_rc}). "
                    f"Fix the underlying failure before trusting its fixture control."
                )
                continue
        else:
            # Stated rather than skipped, so that a reader of the output can see
            # which controls had no clean-run guard and why.
            print(f"  --  {gate.name}: no clean-run possible (input is required); "
                  f"fixture control only")

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            try:
                exec(gate.setup, {"root": root})  # noqa: S102 — a fixed literal from FIXTURE_GATES
            except Exception as exc:  # noqa: BLE001 — report, never crash the gate
                errors.append(f"gate '{gate.name}': fixture setup raised {exc!r}")
                continue
            cmd = [part.replace("{tmp}", tmp) for part in gate.cmd]
            sabotaged_rc = run_gate(cmd)

        if sabotaged_rc == 0:
            errors.append(
                f"gate '{gate.name}' PASSED a deliberately sabotaged input "
                f"(a synthesised {gate.why}). It cannot detect it, so it is not a gate."
            )
        else:
            print(f"  ok  {gate.name}: fails on a synthesised input")
    return errors


# ── Part C: a configured Gradle check task must be able to fail ──────────────
#
# Part A asks whether a task is *invoked*. Part B proves a *script* can fail.
# Neither asks whether an invoked Gradle task can fail — which is how
# `mcp-server:detekt` sat in ci.yml looking enforced while `ignoreFailures = true`
# made it incapable of failing, and stayed out of check.sh so it never ran
# locally at all.
#
# This part is a static read of the build file rather than a sabotage run,
# because for this failure the build file *is* the claim: the flag is the
# mechanism. A module that wants a non-enforcing lint task has to say so in a
# comment, the same way a rule has to be declared in detekt.yml.

_DETEKT_BLOCK = re.compile(r"detekt\s*\{(.*?)\n\s*\}", re.DOTALL)


def non_enforcing_gradle_tasks() -> list[str]:
    """`module:detekt` tasks whose own block sets `ignoreFailures = true`."""
    out: list[str] = []
    for build in sorted(ROOT.glob("*/build.gradle.kts")):
        module = build.parent.name
        text = build.read_text(encoding="utf-8")
        for block in _DETEKT_BLOCK.finditer(text):
            if re.search(r"ignoreFailures\s*=\s*true", block.group(1)):
                out.append(f":{module}:detekt")
                break
    return out


def check_gradle_can_fail() -> list[str]:
    bad = non_enforcing_gradle_tasks()
    if not bad:
        print("  ok  every configured detekt task is enforcing (ignoreFailures != true)")
        return []
    return [
        "\n".join(
            [
                f"  {task} sets `ignoreFailures = true`, so it cannot fail a build.",
                "  An invoked task that cannot fail reports a verdict nobody derived —",
                "  the same defect as a task nothing invokes, and harder to spot because",
                "  the workflow lists the command.",
                "  Set `ignoreFailures = false` and add a baseline if the module has",
                "  pre-existing findings. If a non-enforcing task is genuinely wanted,",
                "  say why in a comment in the same block.",
            ]
        )
        for task in bad
    ]


# ── Part D: a CI step must be blocking unless it declares itself advisory ────
#
# `continue-on-error: true` and a trailing `|| true` both turn a step into a
# report. That is legitimate — some steps have no useful failure mode — but it
# was previously indistinguishable from a step that is supposed to block and
# silently does not. The remedy is not "no `|| true` ever"; it is that a soft
# step has to say so where a reader of the workflow will see it.
#
# `Refresh DIGEST || true` followed by a blocking `Enforce DIGEST size budget`
# was the instructive case: had the refresh failed, the budget check would have
# validated a stale file and reported green.

_ADVISORY_MARKER = re.compile(r"advisory|best[- ]effort|non[- ]blocking", re.I)


def non_blocking_steps() -> list[tuple[str, str, str, bool]]:
    """(workflow, step name, mechanism, declares_itself) for every soft step.

    `declares_itself` is true when the step *name* or a comment in the step body
    says it is advisory. Scanning the body matters: a step whose explanation sits
    in a comment directly under it is declared for every reader of the workflow,
    and this check read the name only at first — which reported a documented
    step as undeclared and would have pushed someone to delete a correct comment
    instead of fixing a real one.
    """
    out: list[tuple[str, str, str, bool]] = []
    for wf in sorted((ROOT / ".github" / "workflows").glob("*.yml")):
        text = wf.read_text(encoding="utf-8")
        for m in re.finditer(
            r"^\s*- name: (.+?)\n(.*?)(?=\n\s*- name:|\njobs:|\Z)", text, re.M | re.S
        ):
            name, body = m.group(1).strip(), m.group(2)
            # Search the step's *commands*, not its prose. A comment reading
            # "Was `|| true` …" made this step look soft when it is blocking —
            # the same self-grep that `_strip_comments` was written to prevent on
            # the shell side, reproduced one function over because the YAML side
            # was not given the same treatment.
            commands = "\n".join(
                line for line in body.splitlines() if not line.strip().startswith("#")
            )
            comments = "\n".join(
                line for line in body.splitlines() if line.strip().startswith("#")
            )
            mechanism = ""
            if re.search(r"continue-on-error:\s*true", commands):
                mechanism = "continue-on-error: true"
            elif _softens_the_step(commands):
                mechanism = "`|| true` on the last command"
            if mechanism:
                declared = bool(
                    _ADVISORY_MARKER.search(name) or _ADVISORY_MARKER.search(comments)
                )
                out.append((wf.name, name, mechanism, declared))
    return out


def _softens_the_step(commands: str) -> bool:
    """True when a `|| true` swallows the *step's* exit status.

    GitHub runs a `run:` block under `bash -eo pipefail`, so a failing command
    aborts the step. That makes an interior `|| true` a defensive idiom rather
    than a soft step: `nth_shard` in maestro-nightly.yml ends its pipeline with
    `grep . || true` so that an empty shard range does not kill the loop that
    fills the other three, and the step still fails loudly if the arithmetic
    above it is wrong.

    Only a `|| true` on the last command of the block can swallow the step's own
    status. Flagging interior ones reports a correct workflow as broken, and the
    tempting response — deleting the `|| true` — would break the shard loop.
    Widening the pattern to catch a shape that is not a defect is the same
    mistake as a check that matches less than it intended: the fix looks like it
    worked and the real bug is untouched.

    Comment lines are skipped here rather than by the caller, so the function is
    correct on its own and there is one place that knows the rule. A trailing
    comment after a soft command must not hide the soft tail.
    """
    lines = [
        ln for ln in commands.splitlines() if ln.strip() and not ln.strip().startswith("#")
    ]
    if not lines:
        return False
    return bool(re.search(r"\|\|\s*true\b", lines[-1]))


def check_ci_steps_blocking() -> list[str]:
    undeclared = [
        f"{wf}: '{step}' cannot fail ({mech}) and neither its name nor its comment"
        f" says it is advisory."
        for wf, step, mech, declared in non_blocking_steps()
        if not declared
    ]
    if not undeclared:
        print("  ok  every non-blocking CI step declares itself advisory")
        return []
    return [
        "\n".join(
            [
                *undeclared,
                "",
                "A soft step is fine; a soft step nobody declared is a step that reads",
                "as a gate. Either make it blocking, or put 'advisory' in the step name",
                "or in a comment on the step, so the next reader of the workflow can",
                "tell which kind it is.",
            ]
        )
    ]


# ── Part E: CI/local gate parity ─────────────────────────────────────────────

#: Where each `check-*.py` gate is *expected* to run, and why when it is not
#: both. Keyed by the gate path; "both" gates need no entry.
#:
#: The reason this exists (2026-10-05): the `--partial` incident in
#: `traceability results`. Both wirings existed, both were present, and Part B
#: proved both could fail — and they still meant different things, because one
#: read a fact only the other knew (whether the run was tag-filtered). Parts A
#: and B cannot see that class of defect: a gate wired everywhere and able to
#: fail is still wrong if its verdict depends on a fact the two environments
#: hold differently.
#:
#: An asymmetry is not a defect. A **silent** one is: the next reader cannot
#: tell a deliberate difference from a typo, and defaults to assuming the two
#: are equivalent. So every asymmetry is declared here with its reason, and an
#: undeclared one fails the check.
GATE_PARITY: dict[str, tuple[str, str]] = {
    "scripts/check-flaky-tests.py": (
        "ci",
        "needs two runs of JUnit XML to compare; a single local run has no "
        "previous run to diff against, so it is not runnable locally at all",
    ),
    "scripts/check-openspec-stale.py": (
        "ci",
        "advisory by construction (`|| true` in ci.yml): a stale change is "
        "reported, not blocked, so it is not part of the local pass either",
    ),
    "scripts/check-backlog-status.py": (
        "local",
        "the backlog budget and the 'every OPEN entry is tracked' rule are a "
        "housekeeping invariant over a file that changes with every commit; CI "
        "does not enforce it, so an untracked OPEN entry can reach main",
    ),
}

#: Argument-level asymmetries on gates that run in both places. Keyed by
#: (gate, leading argument), value is where it is expected.
#:
#: `--since` and `--skill-symbols` change *what* is measured rather than
#: *whether* a gate runs, so a name-only comparison would call them both and
#: miss the difference that matters.
GATE_ARG_PARITY: dict[tuple[str, str], str] = {
    ("scripts/check-coverage.py", "--since"): (
        "ci"
    ),
    ("scripts/check-doc-dead-refs.py", "--skill-symbols"): (
        "ci"
    ),
    ("scripts/check-test-runs.py", "--require shared:testAndroidHostTest"): "ci",
    ("scripts/check-test-runs.py", "--require mcp-server:test"): "ci",
}

_ADVISORY_NOTE = {
    ("scripts/check-coverage.py", "--since"): (
        "CI measures the delta since the run started; the bare call measures "
        "the absolute floor. Same gate, different question."
    ),
    ("scripts/check-doc-dead-refs.py", "--skill-symbols"): (
        "a second variant covering SKILL.md symbol references, which the bare "
        "call does not check"
    ),
    ("scripts/check-test-runs.py", "--require shared:testAndroidHostTest"): (
        "the Android host source set is built only in its own CI job"
    ),
    ("scripts/check-test-runs.py", "--require mcp-server:test"): (
        "the mcp-server test task is not part of the local script at all, so "
        "its floor is enforced in CI only — worth knowing before adding tests "
        "there"
    ),
}


def _gate_invocations(text: str) -> dict[str, list[str]]:
    """Every `check-*.py` invocation in a file, keyed by gate, with its args.

    Line continuations are joined first: `ci.yml` wraps its long invocations,
    and a regex over raw lines would read `--require shared:jvmTest,\n  desktopApp:test`
    as two different calls.
    """
    joined = re.sub(r"\\\n\s*", " ", text)
    out: dict[str, list[str]] = {}
    # `[^\n|]*` rather than a character class alternation with `\n`: the latter
    # is greedy across lines and swallowed every invocation between this one and
    # the next `|`, which reported five gates as CI-only when they run in both.
    for match in re.finditer(r"python3 (scripts/check-[\w-]+\.py)([^\n|]*)", joined):
        name = match.group(1)
        args = " ".join(match.group(2).split()).rstrip("\\").strip()
        out.setdefault(name, []).append(args)
    return out


def _leading_arg(args: str) -> str:
    """The first argument token pair, which is what the parity table keys on."""
    parts = args.split()
    if not parts:
        return ""
    if parts[0].startswith("--require"):
        return "--require " + parts[1] if len(parts) > 1 else "--require"
    return parts[0]


def check_gate_parity() -> list[str]:
    root = _repo_root()
    ci = _gate_invocations((root / ".github/workflows/ci.yml").read_text())
    local = _gate_invocations((root / "check.sh").read_text())
    errors: list[str] = []

    for gate in sorted(set(ci) | set(local)):
        expected = GATE_PARITY.get(gate)
        want = expected[0] if expected else "both"
        in_ci, in_local = gate in ci, gate in local
        actual = "both" if in_ci and in_local else ("ci" if in_ci else "local")
        if actual != want:
            errors.append(
                f"{gate}: runs in {actual}, declared {want}"
                + (f" — {expected[1]}" if expected else " — no reason declared")
            )

    for key, want in sorted(GATE_ARG_PARITY.items()):
        gate, arg = key
        ci_args = [a for a in ci.get(gate, []) if _leading_arg(a) == arg]
        local_args = [a for a in local.get(gate, []) if _leading_arg(a) == arg]
        if want == "ci" and local_args:
            errors.append(
                f"{gate} {arg}: also invoked locally, declared ci-only — "
                f"{_ADVISORY_NOTE.get(key, '')}"
            )
        if want == "local" and ci_args:
            errors.append(
                f"{gate} {arg}: also invoked in CI, declared local-only — "
                f"{_ADVISORY_NOTE.get(key, '')}"
            )

    return errors


def _repo_root() -> pathlib.Path:
    return pathlib.Path(__file__).resolve().parent.parent


# ── Part F: the registry is checked against the registration ────────────────
#
# `registered_gate_scripts()` is the derivation. It reads the same places a gate
# is actually invoked from — `check.sh`, the workflows, and the `just` recipes —
# so a gate that nobody registered a control for is a finding rather than an
# absence. A hand-written list of what has been verified is exactly the thing
# this file exists to distrust.


_JUST_FILES = "**/*.just"


def registered_gate_scripts() -> dict[str, list[str]]:
    """Gate script path -> the files that invoke it.

    `just` recipes are included, not just `check.sh` and CI: four gates
    (`check-adr-references`, `check-coverage-measurement`, `check-gate-honesty`,
    `check-kiwi-gaps`) are reachable only through a recipe, and a registry that
    looked only at `check.sh` would not see them at all.
    """
    surfaces = list(GATE_FILES) + sorted(ROOT.glob(f".just/{_JUST_FILES}"))
    out: dict[str, list[str]] = {}
    for surface in surfaces:
        try:
            text = surface.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        # `python3 scripts/x.py`, `./scripts/x.sh`, `python3 ./scripts/x.py`.
        # The `./` is optional *and* the `python3 ` prefix is optional, but the
        # two cannot be collapsed into one another: an earlier version wrote
        # `(?:python3\s+)?\./?`, which requires a literal `.` and so matched
        # only the `./scripts/*.sh` invocations — 3 of the 18 registered gates.
        # A derivation that quietly sees a third of its input is worse than no
        # derivation, because it reports a short list and calls it complete.
        #
        # The leading `[\s'"(=|]` is a word boundary, and it earns its place:
        # without it, `Maestro/scripts/check-tags.sh` matched as
        # `scripts/check-tags.sh`, a path that does not exist, and the registry
        # then demanded a control for a gate it had invented.
        for match in re.finditer(
            r"(?:^|[\s'\"(=|])(?:python3\s+)?(?:\./)?((?:scripts|infra/kiwi)/check-[\w.-]+\.(?:py|sh))",
            text,
            re.MULTILINE,
        ):
            out.setdefault(match.group(1), []).append(str(surface.relative_to(ROOT)))
    return out


def controlled_gate_scripts() -> set[str]:
    """Every gate script that some control in this file covers.

    Paths are normalised to `scripts/…`: the two shell gates are registered with
    a `./` prefix (they are executed, not imported), and comparing that spelling
    against the path the registration surfaces spell with no prefix reported two
    controlled gates as uncontrolled.
    """
    controlled: set[str] = set()
    for gate in [*SCRIPT_GATES, *SABOTAGE_ONLY_GATES, *FIXTURE_GATES]:
        for part in gate.cmd:
            normalised = part[2:] if part.startswith("./") else part
            if normalised.startswith("scripts/"):
                controlled.add(normalised)
    return controlled


def check_registry_completeness() -> list[str]:
    errors: list[str] = []
    registered = registered_gate_scripts()
    controlled = controlled_gate_scripts()

    for script, sites in sorted(registered.items()):
        if script in controlled or script in GATE_EXEMPTIONS:
            continue
        errors.append(
            f"gate '{script}' is invoked by {', '.join(sorted(set(sites)))} but has no "
            f"positive control. Add it to SCRIPT_GATES (if a committed file can be "
            f"mutated to make it fail) or FIXTURE_GATES (if its input is synthesised), "
            f"or to GATE_EXEMPTIONS with the reason it does not need one. A gate nobody "
            f"has tried to break is a gate nobody knows can fail."
        )
    return errors


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--wiring", action="store_true", help="Part A only")
    ap.add_argument("--can-fail", action="store_true", help="Part B only")
    ap.add_argument(
        "--gradle-can-fail", action="store_true", help="Part C only"
    )
    ap.add_argument(
        "--ci-steps-blocking", action="store_true", help="Part D only"
    )
    ap.add_argument(
        "--parity", action="store_true", help="Part E only"
    )
    ap.add_argument(
        "--registry", action="store_true", help="Part F only"
    )
    args = ap.parse_args()
    only = (
        args.wiring
        or args.can_fail
        or args.gradle_can_fail
        or args.ci_steps_blocking
        or args.parity
        or args.registry
    )
    run_a = args.wiring or not only
    run_b = args.can_fail or not only
    run_c = args.gradle_can_fail or not only
    run_d = args.ci_steps_blocking or not only
    run_e = args.parity or not only
    run_f = args.registry or not only

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

    if run_c:
        print("\nPart C — every configured Gradle check task can fail")
        errors += check_gradle_can_fail()

    if run_d:
        print("\nPart D — every non-blocking CI step declares itself advisory")
        for wf, step, mech, declared in non_blocking_steps():
            label = "declared  " if declared else "UNDECLARED"
            print(f"  {label} {wf}: {step}  ({mech})")
        errors += check_ci_steps_blocking()

    if run_e:
        print("\nPart E — CI/local gate asymmetries are declared with a reason")
        errors += check_gate_parity()

    if run_f:
        print("\nPart F — every registered gate script has a positive control")
        registered = registered_gate_scripts()
        controlled = controlled_gate_scripts()
        for script, sites in sorted(registered.items()):
            state = (
                "controlled" if script in controlled
                else "exempt" if script in GATE_EXEMPTIONS
                else "NO CONTROL"
            )
            print(f"  {state:11} {script}  <- {', '.join(sorted(set(sites)))}")
        errors += check_registry_completeness()
        if not check_registry_completeness():
            print(f"  ok  {len(registered)} registered gate(s), all controlled or exempt")

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
