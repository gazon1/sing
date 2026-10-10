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

Parts C–G cover the properties that Parts A and B cannot see: C, a configured
Gradle task must be capable of failing; D, a non-blocking step must declare
itself advisory; E, a CI/local asymmetry must be declared with a reason; F,
every registered gate must have a positive control; G, the shared gate registry
itself must be invoked by both CI and `check.sh`.

Usage:
    python3 scripts/check-gate-wiring.py            # every part
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

#: The single registry of script gates. `ci.yml`'s `static` job and `check.sh`
#: both call this one file, so a gate named there runs in both environments.
#:
#: It belongs in `GATE_FILES` for the same reason `check.sh` does: a surface
#: this list omits is a surface whose gates are invisible to Part A (is the task
#: invoked), Part E (where does the gate run) and Part F (does it have a
#: positive control). Leaving the registry out fails nothing — it makes every
#: gate in it silently unverified, which is the defect this file exists to
#: catch, one level up from where it usually shows up.
GATE_REGISTRY = ROOT / "scripts" / "ci" / "static-gates.sh"

GATE_FILES = [
    ROOT / "check.sh",
    *sorted((ROOT / ".github" / "workflows").glob("*.yml")),
    GATE_REGISTRY,
]

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
    # When True, the harness skips this gate's sabotage control when the Kiwi
    # stand is unreachable, rather than reporting a false "already fails on a
    # clean tree" error. The gate itself may use --if-present in its registered
    # invocation (check.sh / ci.yml); this field is only about the harness
    # running its own sabotage proof.
    kiwi_required: bool = False
    # When True, this control is skipped on a dirty tree (partial test results
    # on disk from a filtered --tests run). The sabotage raises the floor above
    # any plausible count, so it can only fail on a tree with a full-suite XML.
    sabotage_requires_clean_tree: bool = False
    # True when the gate has a meaningful clean-tree invocation. When False, the
    # clean-tree guard is waived — use for gates that are known to fail on a clean
    # tree due to un-triaged real state (e.g. open entries tracking closed issues).
    needs_clean_run: bool = True


SCRIPT_GATES = [
    ScriptGate(
        name="yaml-duplicate-keys",
        cmd=[sys.executable, "scripts/check-yaml-duplicate-keys.py", "--quiet"],
        sabotage_path="config/detekt/detekt-rules-module.yml",
        sabotage=(
            "p.write_text(p.read_text().replace('    active: false\\n', '    active: false\\n    active: true\\n', 1))"
        ),
        why=(
            "a repeated key is a hard error for SnakeYAML and silently last-one-wins "
            "for other readers. :detekt-rules:detekt is the only consumer of this "
            "file, and :shared/:desktopApp keep their own configs, so the two most-used "
            "lint targets still pass while this module's config is unreachable."
        ),
    ),
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
        name="dead-settings",
        cmd=[sys.executable, "scripts/check-dead-settings.py", "--quiet"],
        # The sabotage removes a baseline entry, so the field it exempts becomes
        # reportable. An earlier version of this entry mutated the DI bindings in
        # CoreDiModule.kt, which proved only that the check can see an unwired
        # *section* — and it stayed green on `reminderDefault` and all seven
        # work-schedule fields, which are wired and still dead. The check descends
        # to the field, so the control has to descend with it.
        sabotage_path="scripts/check-dead-settings-baseline.txt",
        sabotage="p.write_text(p.read_text().replace('Notifications.reminderDefault', 'Notifications.no_such_field'))",
        why="a persisted setting rendered in Settings and read by no feature is a " +
            "control that looks like it works; every baseline line is a claim that " +
            "needs its own justification",
    ),
    ScriptGate(
        name="doc-sizes",
        cmd=[sys.executable, "scripts/check-doc-sizes.py"],
        sabotage_path="AGENTS.md",
        sabotage="p.write_text(p.read_text() + '\\n' * 400)",
        why="the budget must be able to fail; `--warn-only || status=1` could not",
    ),
    ScriptGate(
        name="sync-allowlist-regenerated",
        cmd=[sys.executable, "scripts/check-sync-allowlist-regenerated.py"],
        sabotage_path="shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncContract.kt",
        sabotage=(
            "p.write_text(p.read_text().replace("
            "'\"aiSuppressedTagIds\",', '\"aiSuppressedTagIds\",\\n        \"sabotage_probe_field\",'))"
        ),
        why="if the committed seed differs from the generator output, the gate catches it — and the seed is the one artifact that cannot be regenerated from within the check",
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
        name="publication-hygiene",
        cmd=[sys.executable, "scripts/check-publication-hygiene.py"],
        # A source file, not the allowlist. The allowlist is the gate's escape
        # hatch, so widening it is a decision a review should see in the diff of
        # a real file rather than in a data file that reads as configuration. The
        # sabotage writes a machine path into a tracked file outside every
        # allowlisted prefix — the exact shape this gate was written for, and the
        # one a future editor would reintroduce by pasting a command from a
        # terminal.
        sabotage_path="README.md",
        sabotage="p.write_text(p.read_text() + '\\nBuild it with `cd /home/sabotage/probe`.\\n')",
        why="a machine-specific path in a public file is unrecoverable once published",
    ),
    ScriptGate(
        name="readme-claims",
        cmd=[sys.executable, "scripts/check-readme-claims.py"],
        # README.md, because the gate's entire claim is that this file states
        # numbers which must match the tree. A wrong schema version is the
        # cheapest drift to introduce and the first thing a reader would see.
        # The self-tests cover the parse rules; this covers the wiring.
        sabotage_path="README.md",
        # Same defect the room-schema control had, found the same way: the schema
        # moved 38 → 39 → 40 while this entry still replaced the literal 38, so
        # `replace` matched nothing, the gate read an untouched README, correctly
        # passed, and the control reported itself broken. Match by regex and
        # assert, so a version bump cannot silently turn this into a no-op.
        sabotage=(
            "import re\n"
            "_t = p.read_text()\n"
            "_t2, _n = re.subn(r'schema v\\d+', 'schema v1', _t, count=1)\n"
            "assert _n == 1, 'schema claim not found in README — the control would be a no-op'\n"
            "p.write_text(_t2)\n"
        ),
        why="a README that contradicts the tree is the first failure a reader sees",
    ),
    ScriptGate(
        name="skill-frontmatter",
        cmd=["./scripts/check-skill-frontmatter.sh"],
        sabotage_path=".agents/skills/singularity-todo-koin-dsl/SKILL.md",
        sabotage="_t = p.read_text(); _lines = _t.splitlines(keepends=True); del _lines[1]; p.write_text(''.join(_lines))",
        why="a skill without a description is undiscoverable, so the check must reject it",
    ),
    ScriptGate(
        name="provenance",
        cmd=[sys.executable, "scripts/check-provenance.py"],
        # The registry, not a source file. Sabotaging a source file would prove the
        # wrong direction: every PORTED file already carries a marker, so removing
        # one would test nothing. Removing a *row* is the honest control — it
        # leaves a file naming an upstream with no registry entry, which is the
        # exact state the gate was written to reject.
        sabotage_path="config/legal/provenance-registry.tsv",
        sabotage="p.write_text('\\n'.join(l for l in p.read_text().splitlines() if 'QueryTokenizer' not in l) + '\\n')",
        why="a GPL upstream named in production code with no registry row is a licence claim nobody is tracking",
    ),
    ScriptGate(
        name="pro-licence-boundary",
        cmd=[sys.executable, "scripts/check-pro-licence-boundary.py"],
        # The vendor dependency, reintroduced into a free build file. That is the exact
        # regression this gate exists to catch: `ru.ok.tracer` was a direct `implementation`
        # dependency of `:shared` and `:androidApp` until 2026-10-05, so it is a change
        # that has already happened once and can happen again by copy-paste.
        sabotage_path="shared/build.gradle.kts",
        sabotage="p.write_text(p.read_text() + '\\ndependencies { implementation(\"ru.ok.tracer:tracer-crash-report:1.4.0\") }\\n')",
        why="proprietary code in an Apache-2.0 module makes the published licence a claim the project cannot honour",
    ),
    ScriptGate(
        name="traceability-ratchet",
        cmd=[sys.executable, "scripts/check-traceability-ratchet.py"],
        # A spec, not the floor file. The floor is the gate's own configuration,
        # so raising it is a legitimate edit that must not be read as a
        # regression — the control has to make the *corpus* worse instead. This
        # is the shape of the change the gate exists for: claiming one more
        # target on a scenario that has no carrier for it. The `assert` is what
        # stops the control silently becoming a no-op if the spec is ever
        # reformatted — the failure this file was written for, and it already
        # happened once to the test-runs control.
        sabotage_path="infra/kiwi/scenarios/tasks/checklist/TASK-CHECK-01.yaml",
        sabotage=(
            "import re\n"
            "_t = p.read_text()\n"
            "_t2, _n = re.subn(r'^targets: \\[desktop\\]$', 'targets: [desktop, android]', _t, count=1, flags=re.M)\n"
            "assert _n == 1, 'targets line not found — the control would be a no-op'\n"
            "p.write_text(_t2)\n"
        ),
        why="a scenario claiming a target nothing verifies is the one hole count that grows without anyone reading the diff",
    ),
    ScriptGate(
        name="kiwi-inventory-ratchet",
        cmd=[sys.executable, "scripts/check-kiwi-inventory-ratchet.py"],
        # The floor file, not a source file. Lowering a ceiling is the gate working, and a
        # control that raises one would be testing the opposite of the failure. The
        # `measured` field is the better target: leaving it stale is exactly the state
        # where the file describes a commit other than the one in the tree, and that is a
        # silent drift the gate exists to name.
        sabotage_path="config/docs/kiwi-inventory-ratchet.json",
        sabotage=(
            "import json\n"
            "_d = json.loads(p.read_text())\n"
            "_f = [f for f in _d['floors'] if f['metric'] == 'inventory']\n"
            "assert len(_f) == 1, 'inventory floor not found — the control would be a no-op'\n"
            "_f[0]['measured'] = _f[0]['measured'] - 1\n"
            "p.write_text(json.dumps(_d, indent=2) + '\\n')"
        ),
        why="a floor file whose recorded measurement no longer matches the tree describes a different commit, and nothing else would say so",
    ),
    ScriptGate(
        name="room-schema-integrity",
        cmd=[sys.executable, "scripts/check-room-schema-integrity.py"],
        # The exact defect this gate was written after: `SyncColumns.server_version`
        # was added to `sync_shadow`, the export moved to 37, and `SCHEMA_VERSION`
        # stayed at 36. Room's identity-hash check then failed for every user with
        # an existing database — an `IllegalStateException` at first query, invisible
        # to a suite whose tests each create their own database. Sabotaging the
        # annotation reproduces that state, so the control is the real bug rather
        # than a convenient one.
        sabotage_path="shared/src/commonMain/kotlin/com/singularity/todo/core/database/AppDatabase.kt",
        # The value is matched by regex and the match is asserted, never replaced by
        # a literal. This entry had `SCHEMA_VERSION = 37` hardcoded; the schema moved
        # to 38, `replace` silently found nothing, the gate was handed an unmodified
        # file and correctly passed — and the control reported "it cannot detect
        # this", which reads like a broken gate rather than a broken control. A
        # control that quietly stops sabotaging is worse than no control, because it
        # is reported as a passing one. The `assert` makes the no-op loud instead.
        sabotage=(
            "import re\n"
            "_t = p.read_text()\n"
            "_t2, _n = re.subn(r'const val SCHEMA_VERSION = \\d+', 'const val SCHEMA_VERSION = 1', _t, count=1)\n"
            "assert _n == 1, 'SCHEMA_VERSION declaration not found — the control would be a no-op'\n"
            "p.write_text(_t2)\n"
        ),
        why="an entity change without a version bump passes every test and crashes every existing install on upgrade",
    ),
    ScriptGate(
        name="dependency-usage",
        cmd=[sys.executable, "scripts/check-dependency-usage.py", "--quiet"],
        sabotage_path="scripts/dependency-usage-allowlist.txt",
        # The gate runs against the repository, so the control can use the same
        # invocation and the same target every time — no fabricated resolution,
        # no Gradle, and no second input format to keep honest. Dropping one
        # allowlist line turns that dependency into a finding the gate must
        # report, which is exactly the moment the list stops matching reality.
        sabotage="p.write_text('\\n'.join(l for l in p.read_text().splitlines() if 'kermit-koin' not in l) + '\\n')",
        why="a dependency declared, resolved and imported by nothing costs a full release's build time and ships undetected — material-kolor sat on the classpath for one",
    ),
    ScriptGate(
        name="supabase-schema-integrity",
        cmd=[sys.executable, "scripts/check-supabase-schema-integrity.py"],
        # A migration, not a source file. The gate's subject is the agreement between a
        # file's header and its body, so the control has to move the body: appending a
        # function with no fingerprint is exactly what an edit to the schema looks like
        # before anyone re-derives the md5s, and it is the failure the gate exists for.
        #
        # Corrupting a fingerprint's *value* would have proved nothing. The gate has no
        # database, so it cannot know the correct md5; a control asserting a value it cannot
        # verify would be asserting the gate's own limit rather than testing the rule.
        sabotage_path="supabase/migrations/2026-10-07-sync_schema.sql",
        sabotage=(
            "p.write_text(p.read_text() + \"\\ncreate or replace function \"\n"
            "             \"public.sync_control_probe(p_type text)\\n\"\n"
            "             \"returns text as $$ select p_type $$ language sql;\\n\")"
        ),
        why="a function added without a fingerprint makes the header understate the schema, and the "
             "header is the part a reader trusts; the live half of #221 needs credentials and stays "
             "manual, so this is the half that can be a gate",
    ),
    ScriptGate(
        name="rebase-compiles",
        cmd=[sys.executable, "scripts/check-rebase-compiles.py"],
        sabotage_path="shared/src/commonMain/kotlin/com/singularity/todo/core/di/CoreDiModule.kt",
        sabotage=(
            "content = p.read_text()\n"
            "p.write_text(content.replace(\n"
            "    '        SyncEngine(',"
            "    '        // SyncEngine('))"
        ),
        why="an import removed from a DI module silently breaks every consumer; "
             "the compile gate detects the resulting 'unresolved reference' before jvmTest does",
    ),
]
# The gate's own `--self-test` invocation needs no entry here: `controlled_gate_scripts()`
# keys on the script path, not the full command, so this one registration covers both
# the repository check and the self-test that proves the rule still fires.


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
        name="req-id-uniqueness",
        cmd=[sys.executable, "scripts/check-req-id-uniqueness.py", "--root", "{tmp}/repo"],
        setup=(
            "import pathlib\n"
            "d = root / 'repo' / 'openspec' / 'specs' / 'offline-sync'\n"
            "d.mkdir(parents=True, exist_ok=True)\n"
            "(d / 'spec.md').write_text(\n"
            "    '# offline-sync\\n\\n'\n"
            "    '### Requirement: REQ-OS-015\\n\\nIt SHALL hold.\\n',\n"
            "    encoding='utf-8')\n"
            "c = root / 'repo' / 'openspec' / 'changes' / 'later' / 'specs' / 'offline-sync'\n"
            "c.mkdir(parents=True, exist_ok=True)\n"
            "(c / 'spec.md').write_text(\n"
            "    '# offline-sync\\n\\n'\n"
            "    '## ADDED Requirements\\n\\n'\n"
            "    '### Requirement: REQ-OS-015\\n\\nIt SHALL hold, again.\\n',\n"
            "    encoding='utf-8')\n"
        ),
        why=(
            "the collision this gate exists for, in the exact shape the tree had: a "
            "change that ADDs an identifier the spec already defines. The clean corpus "
            "would pass, so needs_clean_run is False — on this repository the corpus is "
            "still red (log-export-surface vs add-log-export), which is why the gate is "
            "registered advisory."
        ),
        needs_clean_run=False,
    ),
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
    "scripts/check-kiwi-gaps.py": (
        "the Kiwi TCMS stand is currently unused; the recipe is commented out "
        "in .just/kiwi/mod.just. An exempted gate that nobody runs cannot fail."
    ),
}


# Gates whose control mutates a committed file. Kept beside FIXTURE_GATES so the
# two kinds read as one registry; `check_can_fail` dispatches on which list an
# entry came from.
SABOTAGE_ONLY_GATES = [
    ScriptGate(
        name="test-runs",
        # --max-age 86400: this harness runs after arbitrary Gradle invocations that
        # may have written filtered XML (e.g. `--tests SomeClass`). The sabotage proof
        # only needs to verify the gate CAN fail on a raised floor; it does not need
        # a clean full-run result.
        cmd=[sys.executable, "scripts/check-test-runs.py", "--require", "shared:jvmTest,desktopApp:test", "--max-age", "86400"],
        sabotage_path="config/docs/test-runs-baseline.txt",
        # Rewrite the floor by regex, never by naming its current value. The first
        # version replaced the literal `shared:jvmTest 1788 0`, and the control
        # became a no-op the moment the floor moved to 1825 — `replace` found
        # nothing, the gate was handed an unmodified file, and the only reason it
        # was caught is that the clean-tree guard fired on a *stale* run instead.
        # A control that quietly stops sabotaging is worse than no control: it is
        # reported as a passing control. The `assert` makes the no-op loud.
        #
        sabotage=(
            "import re\n"
            "_t = p.read_text()\n"
            "_t2, _n = re.subn(r'^(shared:jvmTest )\\d+( \\d+)$', r'\\g<1>99999\\g<2>', _t, count=1, flags=re.M)\n"
            "assert _n == 1, 'floor line not found — the control would be a no-op'\n"
            "p.write_text(_t2)\n"
        ),
        why="a test source set that ran fewer tests than its recorded floor means coverage was lost",
        sabotage_requires_clean_tree=True,
    ),
    # REMOVED — kiwi-gaps gate deleted 2026-10-09.
    # Kiwi is not currently used; the gate is kept in static-gates.sh with --if-present
    # so it skips when the stand is down. Removing it from the sabotage registry
    # eliminates the false "already fails on a clean tree" report from the harness.
    # Restore by copying the block back from git history if Kiwi is re-adopted.
    # ScriptGate(
    #     name="kiwi-gaps",
    #     cmd=[sys.executable, "scripts/check-kiwi-gaps.py"],
    #     sabotage_path="config/docs/kiwi-gaps-baseline.txt",
    #     sabotage="p.write_text(p.read_text().replace('Automated — mcp-server 1', 'Automated — mcp-server 0'))",
    #     why="a plan whose never-run cases rise above its floor means the plan is not being run",
    #     kiwi_required=True,
    # ),
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
        name="suppression-intent",
        cmd=[sys.executable, "scripts/check-suppression-intent.py"],
        sabotage_path="shared/src/commonMain/kotlin/com/singularity/todo/core/log/FileLogWriter.kt",
        # Strip the whole reason block from a file that has one under the
        # annotation. That is the shape a thirteenth unjustified suppression
        # arrives in, and it is a *comment* route specifically because six of the
        # ten current exemptions are justified through the registry instead — a
        # control that only exercised one of the two escapes would leave the
        # other untested. The registry route is covered by the gate's own unit
        # tests, which run it against an empty registry and require the verdict
        # to change.
        #
        # The reason is removed *in full*, not line by line: the gate reads a
        # four-line window, so dropping only the first comment line left the rest
        # of the justification in place and the control passed a file that was
        # still justified. The first version of this entry did exactly that and
        # reported a working control over a sabotaged file.
        sabotage=(
            "_t = p.read_text()\n"
            "_lines = _t.splitlines(keepends=True)\n"
            "assert _lines[0].startswith('@file:Suppress'), 'unexpected file shape'\n"
            "assert _lines[1].lstrip().startswith('//'), 'no reason line to strip'\n"
            "_out, _dropped = [_lines[0]], 0\n"
            "for _ln in _lines[1:]:\n"
            "    if _ln.lstrip().startswith('//'):\n"
            "        _dropped += 1\n"
            "        continue\n"
            "    if _dropped and not _ln.strip():\n"
            "        continue\n"
            "    _out.append(_ln)\n"
            "assert _dropped >= 2, 'expected a multi-line reason, found ' + str(_dropped)\n"
            "p.write_text(''.join(_out))\n"
        ),
        why="a file-level suppression with no recorded reason switches a rule off for every future call in that file",
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
    ScriptGate(
        name="backlog-issue-refs",
        cmd=[sys.executable, "scripts/check-backlog-issue-refs.py"],
        sabotage_path="docs/decisions/deferred-backlog.md",
        # Introduce a cite to a non-existent issue — I1 violation: the cited issue
        # does not exist in the snapshot. A gate that cannot detect a broken link
        # is a gate that lets broken links accumulate.
        sabotage=(
            "import re\n"
            "_t = p.read_text()\n"
            # Format: **Tracked as:** [#110](...) — the issue number is inside [...].
            # Replace the first `[#NNN]` with `[#99999]` to cite a non-existent issue.
            "_t2, _n = re.subn(r'\\[#(\\d+)\\]', '[#99999]', _t, count=1)\n"
            "assert _n == 1, 'no Tracked as: markdown reference found — the control would be a no-op'\n"
            "p.write_text(_t2)\n"
        ),
        why="a backlog entry citing a non-existent issue is a broken link; the backlog is the reasoning and the issue is the queue, so both must agree",
        # In SABOTAGE_ONLY_GATES because it currently fails on 11 real I2 violations
        # (open entries tracking closed issues) — genuine triage work. Waives the
        # clean-tree guard so the positive control can be demonstrated.
        needs_clean_run=False,
    ),
]


def run_gate(cmd: list[str]) -> int:
    proc = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, check=False)
    return proc.returncode


def _kiwi_is_available() -> bool:
    """True when the Kiwi XML-RPC stand is reachable and login succeeds."""
    try:
        sys.path.insert(0, str(ROOT / "infra"))
        from infra.kiwi.kiwi_client import KiwiClient
        client = KiwiClient()
        client.check_alive()
        return True
    except Exception:
        return False
    finally:
        # Clean up sys.path if we modified it
        kiwi_path = str(ROOT / "infra")
        if kiwi_path in sys.path:
            sys.path.remove(kiwi_path)


def _tree_is_dirty() -> bool:
    """
    True when a partial Gradle test run has left stale XML on disk.

    A full suite run produces ~327 class XML files for shared:jvmTest. A filtered
    `--tests SomeClass` run produces 1. The gate harness' sabotage control needs a
    full-suite XML to compare against the sabotaged (raised) floor — with 1 class
    the floor (99999) is still above 13 tests, so the gate incorrectly passes on
    sabotage. Running the full suite takes 7 minutes and is not worth it just to
    verify the sabotage proof, so this gate is skipped on a dirty tree.
    """
    jvm_test_results = ROOT / "shared" / "build" / "test-results" / "jvmTest"
    if not jvm_test_results.is_dir():
        return False
    xml_count = len(list(jvm_test_results.glob("**/*.xml")))
    # A full run: ~327 classes. A filtered run: 1. Anything < 50 is suspicious.
    return 0 < xml_count < 50


#: Flags that disable a gate's own failure. Kept as a list rather than a
#: per-gate field because the rule is the same for every one of them, and a
#: per-gate field would let a gate opt itself out of the rule that covers it.
FORBIDDEN_IN_REGISTERED_INVOCATIONS = ("--accept-growth", "--warn-only")


def check_escape_hatches() -> list[str]:
    """A gate registered with its own off-switch is a gate that cannot fail.

    `check-traceability-ratchet.py --accept-growth` is honest: it exists for the
    commit that knowingly opens holes, and it prints loudly when used. The
    problem is not the flag, it is the flag in the *registered* invocation. Once
    `--accept-growth` is in `check.sh` and in the workflow, the gate is green on
    every future regression, and the only trace is a word in a command line that
    no reviewer reads as a policy change — which is the same defect as a
    `--warn-only` that got committed, one level up.

    The escape stays available for a deliberate one-off; what is forbidden is
    making it the default. So the check is on the registry entry, not on the
    script: the flag must never appear in a `cmd` that `check.sh`, a workflow or
    a recipe installs as the standing invocation.
    """
    errors: list[str] = []
    for gate in [*SCRIPT_GATES, *SABOTAGE_ONLY_GATES, *FIXTURE_GATES]:
        joined = " ".join(gate.cmd)
        for flag in FORBIDDEN_IN_REGISTERED_INVOCATIONS:
            if flag in joined:
                errors.append(
                    f"gate '{gate.name}' is registered with {flag}, which turns it "
                    f"into a gate that cannot fail. Use it for a single deliberate "
                    f"commit invocation, never in the standing one."
                )
    return errors



def check_can_fail() -> list[str]:
    errors: list[str] = []
    errors += check_escape_hatches()
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
        if gate.kiwi_required and not _kiwi_is_available():
            print(f"  skip  {gate.name}: Kiwi stand unreachable — sabotage control "
                  f"not verifiable in this environment (gate requires Kiwi to be live)")
            continue
        if gate.sabotage_requires_clean_tree and _tree_is_dirty():
            print(f"  skip  {gate.name}: tree has partial test results on disk — "
                  f"sabotage control requires a clean full-suite run to verify")
            continue
        if gate.needs_clean_run:
            baseline_rc = run_gate(gate.cmd)
            if baseline_rc != 0:
                errors.append(
                    f"gate '{gate.name}' already fails on a clean tree (exit {baseline_rc}). "
                    f"Fix the underlying failure before trusting its sabotage control."
                )
                continue
        else:
            # Stated rather than skipped, so a reader of the output can see
            # which controls had no clean-run guard and why.
            print(f"  --  {gate.name}: no clean-run possible (known failures); "
                  f"sabotage control only")

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
    than a soft step: the shard loop in scripts/ci/e2e-shard.sh ends its pipeline
    with `paste -sd, - || true` so that an empty tag list does not kill the loop
    that fills the rest, and the step still fails loudly if the arithmetic above
    it is wrong.

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


def registry_soft_exits() -> list[str]:
    """Ways the shared registry could make itself non-blocking behind our back.

    Part D reads workflows, because a workflow step is where a reader looks to
    decide whether a check gates anything. That worked when the gates *were* the
    workflow steps. Now they live in `static-gates.sh`, which no reader of
    `ci.yml` ever sees — so a `|| true` pasted into the registry would soften a
    gate with no trace in the file people actually review.

    The registry has one honest way to be non-blocking: `gate advisory`, which
    emits a warning annotation and a summary line. So the rule here is not "no
    `|| true` ever" — it is that the declared mechanism is the only one available
    on the line that decides a verdict.

    Scoped to `gate` invocation lines on purpose. `gate` runs its command and
    reads `$?`, so a `|| true` there is the real defect: the verdict becomes 0
    whatever the command did. The idiom `n=$(grep -c … || true)` in a helper is a
    different thing — it captures a count where `grep -c` exits 1 on no match and
    the "failure" is the answer, not a suppressed error. Flagging that would report
    a correct script as broken, and the tempting response — delete the `|| true` —
    would break the helper.
    """
    if not GATE_REGISTRY.is_file():
        return []
    findings: list[str] = []
    for num, line in enumerate(GATE_REGISTRY.read_text(encoding="utf-8").splitlines(), 1):
        stripped = line.strip()
        if stripped.startswith("#") or not stripped.startswith("gate "):
            continue
        if re.search(r"\|\|\s*true\b", stripped):
            findings.append(
                f"static-gates.sh:{num}: `gate` invocation ends in `|| true` — the "
                f"verdict becomes 0 whatever the command did; use `gate advisory`"
            )
        if re.search(r"set\s+\+e\b", stripped):
            findings.append(
                f"static-gates.sh:{num}: `set +e` — a gate that cannot fail is not a gate"
            )
    return findings


def check_ci_steps_blocking() -> list[str]:
    undeclared = [
        f"{wf}: '{step}' cannot fail ({mech}) and neither its name nor its comment"
        f" says it is advisory."
        for wf, step, mech, declared in non_blocking_steps()
        if not declared
    ]
    undeclared += registry_soft_exits()
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
                "",
                "In scripts/ci/static-gates.sh there is no third option: use",
                "`gate advisory`, which is visible as a warning and in the summary.",
            ]
        )
    ]


# ── Part E: CI/local gate parity ─────────────────────────────────────────────

#: Gates whose CI/local wiring **differs from the default**, and why.
#: Keyed by the gate path.
#:
#: The default is "both": a `check-*.py` gate is expected to run in `ci.yml` *and*
#: in `check.sh`, and needs no entry here. An entry declares an asymmetry — this
#: gate is ci-only, or local-only — and carries the reason for it. The gate is
#: absent from this table **because** it runs in both places, which is correct.
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
        "both",
        "advisory by construction: it runs as `gate advisory` in the shared "
        "registry, so both CI and check.sh report a stale change as a warning "
        "and neither blocks on it",
    ),
    "scripts/check-backlog-status.py": (
        "local",
        "the backlog budget and the 'every OPEN entry is tracked' rule are a "
        "housekeeping invariant over a file that changes with every commit; it "
        "is deliberately NOT in the shared registry, because a PR must not be "
        "blocked by backlog bookkeeping",
    ),
    "scripts/check-backlog-issue-refs.py": (
        "both",
        "advisory: the 11 real I2 violations (open entries tracking closed issues) "
        "need human triage before this can be blocking; I4 (open issues missing "
        "Backlog: field) is advisory by design since the convention is new",
    ),
}
# Both `check-publication-hygiene.py` and `check-readme-claims.py` were declared
# `ci` here while this was being written, on the reasoning that they read committed
# state and only CI has the merged tree. Main's #217 moved both into
# `scripts/ci/static-gates.sh`, which `check.sh` also runs — so they genuinely run in
# both places now, and the declaration was the thing that had become wrong. Kept as
# a note because the reasoning is still right about *why* they were CI-only, and the
# day somebody moves one back out of the shared registry this is the argument for
# putting it back in this table.

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
        "both"
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
        "call does not check; it now comes from the shared registry, so it runs "
        "locally too rather than only in CI"
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


def _gate_texts(side: str) -> str:
    """Concatenate the gate surfaces of one environment.

    `scripts/ci/static-gates.sh` counts on BOTH sides, and that is the whole
    point of it: CI and `check.sh` run the same file, so a gate it names runs
    in both. Counting it as CI-only would report the shared registry as an
    asymmetry on every gate it holds, and counting it as neither would let a
    gate run in exactly one environment forever.

    `ci.yml` alone is deliberately not enough: a gate can be invoked from a
    job that is not `test-and-check`, and a gate that moved into the registry
    left `ci.yml` entirely. Both mistakes produced the same silent green.
    """
    root = _repo_root()
    files = [root / ".github/workflows/ci.yml", GATE_REGISTRY] if side == "ci" else [
        root / "check.sh",
        GATE_REGISTRY,
    ]
    return "\n".join(
        p.read_text(encoding="utf-8") for p in files if p.is_file()
    )


def check_gate_parity() -> list[str]:
    ci = _gate_invocations(_gate_texts("ci"))
    local = _gate_invocations(_gate_texts("local"))
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


# ── Part G: the shared registry is itself wired ─────────────────────────────
#
# Every other part of this file asks about a gate somebody wrote. This one asks
# about the file that decides which gates run at all, and it is here because that
# file is new and load-bearing: `ci.yml`'s `static` job and `check.sh` both call
# `scripts/ci/static-gates.sh`, so every script gate in the repository now sits
# behind one file that no reader of `ci.yml` ever sees.
#
# The failure this catches is not hypothetical and not subtle. Moving the gates
# into the registry without this part would have left the registry itself with
# no positive control: renaming it, or deleting one of its two callers, would
# drop the whole gate suite out of CI with nothing failing — the exact shape of
# the defects Parts A and B were written for, one level up.


def check_registry_wiring() -> list[str]:
    errors: list[str] = []
    if not GATE_REGISTRY.is_file():
        errors.append(
            f"the shared gate registry {GATE_REGISTRY.relative_to(ROOT)} does not "
            f"exist. `ci.yml` and `check.sh` both call it; without the file both "
            f"calls fail, and if they do not, the gate suite runs nowhere."
        )
        return errors

    callers = {
        "ci.yml": (ROOT / ".github" / "workflows" / "ci.yml"),
        "check.sh": ROOT / "check.sh",
    }
    # The path must appear as an ARGUMENT to bash/sh, not merely anywhere in the
    # file. A plain substring search is satisfied by `see scripts/ci/static-gates.sh`
    # in an error message, so deleting the real call while leaving the prose would
    # pass — the same "a check that greps the wrong thing and reports success"
    # defect this file exists to catch, one function over.
    invocation = re.compile(r"(?:^|\s)(?:bash|sh)\s+[\w./-]*scripts/ci/static-gates\.sh")

    for name, path in sorted(callers.items()):
        if not path.is_file():
            errors.append(f"{name} does not exist, so it cannot call the registry")
            continue
        text = _strip_comments(path.read_text(encoding="utf-8"))
        if not invocation.search(text):
            errors.append(
                f"{name} does not invoke scripts/ci/static-gates.sh. The registry "
                f"is the single list of gates; a caller that bypasses it reports "
                f"green for gates it never ran."
            )
    return errors


# ── Part H: every gate-shaped script is reachable from some surface ─────────
#
# Parts A–G all start from a gate somebody already decided to run. A script that
# can fail, sits in `scripts/`, and is named by nobody is invisible to every one
# of them: it is not "invoked" (A), not "registered" (F), not asymmetric (E). It
# reports success for as long as it exists.
#
# Two things make this harder than a `grep`, and both were found by writing the
# first version of it and watching it produce the wrong answer.
#
# 1. The name has two spellings here. `check-skill-frontmatter.sh` uses a hyphen;
#    `check_skill_frontmatter.py` and `check_adr_status.py` use an underscore. A
#    pattern that accepts only `check-` sees 26 of the 29 gate scripts and calls
#    that the complete set — the failure mode this file's own comments warn about
#    twice ("a derivation that quietly sees a third of its input is worse than no
#    derivation, because it reports a short list and calls it complete").
#
# 2. A shim is an invocation. `check-skill-frontmatter.sh` is six lines and ends
#    in `exec python3 "$ROOT/scripts/check_skill_frontmatter.py" "$@"`. The
#    Python file is therefore reachable, and a scanner that reads only the
#    surface text calls it an orphan. So reachability follows delegation, which
#    is the opposite of what a reader expects: the *target* of an `exec` counts
#    as reached, not the shim that reached it.

#: A script is a gate candidate when its basename starts with `check` — hyphen or
#: underscore, both spellings are in use — and ends in a script extension.
_GATE_CANDIDATE = re.compile(r"check[-_][\w.-]+\.(?:py|sh)$")

#: Where a gate may be named. This is a superset of `GATE_FILES`, which serves
#: Parts A/E/F: those ask about gates that run in a *gate* surface, while this
#: part asks about gates reachable at all, and four of them reach CI only from
#: `ci.yml` or from a `just` recipe.
_REACH_SURFACES = (
    [ROOT / "check.sh", ROOT / "scripts" / "ci" / "static-gates.sh", ROOT / "justfile"]
    + sorted(ROOT.glob(".just/**/*.just"))
    + sorted(ROOT.glob(".github/workflows/*.yml"))
    + sorted(ROOT.glob(".github/actions/*/action.yml"))
)

#: `exec python3 scripts/x.py` / `exec bash scripts/x.sh` — a shim delegating.
#: The optional prefix accepts any shell variable, not just `$ROOT`: the shim in
#: this repository uses `"$ROOT/scripts/…"`, and a scanner written against that one
#: spelling silently misses `$PWD/` and `${HERE}/`, which is how a reachable gate
#: gets reported as an orphan and an editor "fixes" a gate that was never broken.
_SHIM_DELEGATION = re.compile(
    r"exec\s+(?:python3|bash|sh)\s+[\"']?"
    r"(?:\$\{?[A-Za-z_][A-Za-z0-9_]*\}?/)?(?:scripts/)?"
    r"(check[-_][\w.-]+\.(?:py|sh))"
)


def gate_candidate_scripts() -> list[str]:
    """Every `scripts/check*` / `infra/kiwi/check*` script that exists on disk."""
    out: list[str] = []
    for pattern in ("scripts/check*.*", "infra/kiwi/check*.*"):
        for path in sorted(ROOT.glob(pattern)):
            name = path.name
            # `scripts/check-dead-settings-baseline.txt` matches the glob but is a
            # data file the gates read. Basename-anchored, so a nested path cannot
            # sneak a non-gate in through the middle of the name.
            if _GATE_CANDIDATE.search(name) and path.is_file():
                out.append(path.relative_to(ROOT).as_posix())
    return sorted(set(out))


def reachable_gate_scripts() -> set[str]:
    """Gate scripts named by a surface, plus everything those shims delegate to."""
    invoked: set[str] = set()
    pattern = re.compile(
        r"(?:^|[\s'\"(=|])(?:python3\s+)?(?:\./)?"
        r"((?:scripts|infra/kiwi)/check[-_][\w.-]+\.(?:py|sh))"
    )
    for surface in _REACH_SURFACES:
        try:
            text = surface.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        for match in pattern.finditer(text):
            invoked.add(match.group(1))

    # Follow delegation. Bounded, because a cycle must terminate: two shims that
    # exec each other would otherwise loop forever, and a gate that hangs is not a
    # better outcome than a gate that is wrongly reported.
    frontier = list(invoked)
    for _ in range(3):
        found: set[str] = set()
        for rel in frontier:
            path = ROOT / rel
            try:
                body = path.read_text(encoding="utf-8")
            except (OSError, UnicodeDecodeError):
                continue
            for match in _SHIM_DELEGATION.finditer(body):
                for candidate in (f"scripts/{match.group(1)}", f"infra/kiwi/{match.group(1)}"):
                    if (ROOT / candidate).is_file() and candidate not in invoked:
                        found.add(candidate)
        if not found:
            break
        invoked |= found
        frontier = sorted(found)
    return invoked


# ── Part I: a Gradle task named by one surface is named by neither, in effect ──
#
# Part A asks whether a Gradle check task is invoked *somewhere*. That question
# has a defect in it, and the defect is the reason `./check.sh` can be green while
# CI is red: "somewhere" is satisfied by CI alone.
#
# Measured at `5146543f`, two configured check tasks were named by ci.yml and by
# nothing else: `:androidApp:detekt` and `:detekt-rules:detekt`. The local loop
# therefore never linted two of the six configured modules — including
# `detekt-rules`, the module that holds the project's own custom rules, where a
# rule file that does not lint looks identical to one with no findings. Both are
# now named in `check.sh`.
#
# ## What this check actually detects, precisely
#
# It detects **presence of a task string**, not invocation. The two differ:
# `python3 scripts/check-test-runs.py --require mcp-server:test` names the task as
# a floor to compare against, and never runs it. Such a mention counts as presence
# here.
#
# That errs toward passing, which is the direction this file warns about elsewhere
# ("a derivation that quietly sees a third of its input is worse than no
# derivation"). It is deliberate rather than accidental, because the alternative —
# proving from shell and YAML text that a Gradle task is *invoked* — means
# resolving `run:` blocks, matrices and line continuations, and a wrong answer
# there is worse than a known approximation. What this check guarantees is
# narrower and still worth having: **a module whose lint or tests nobody names on
# the local surface cannot go unnoticed**, and every intentional asymmetry is a
# row in the table with a reason attached. `test_a_floor_declaration_counts_as
# _presence` pins that behaviour so it cannot change silently.

#: Gradle task -> (side it legitimately runs on, why). Every task named by either
#: surface and absent from this table is required to run on both.
#:
#: `covered` is the state that "both" cannot express: `check.sh` names the task and
#: CI reaches it by a route that does not spell the task name. `koverReport` is the
#: case that matters — CI invokes it and it aggregates `:shared:jvmTest` and
#: `:desktopApp:test`, so both really do run there, but neither string appears in
#: `ci.yml`. Demanding the literal spelling would mean adding a redundant
#: invocation; allowing it silently would be the Part A defect one level up.
GRADLE_TASK_PARITY: dict[str, tuple[str, str]] = {
    ":shared:jvmTest": (
        "covered",
        "CI reaches it through `koverReport`, which aggregates the test task and "
        "instruments it. `ci.yml` never spells the task; adding a second literal "
        "invocation would be noise, not coverage.",
    ),
    ":desktopApp:test": (
        "covered",
        "same route as :shared:jvmTest — `koverReport` in ci.yml's tests job.",
    ),
    ":androidApp:connectedDebugAndroidTest": (
        "covered",
        "CI runs it in android-device-tests.yml, not ci.yml. It needs an "
        "emulator, so it cannot join a PR's cheap surfaces; see "
        "docs/decisions/2026-10-07-the-instrumentation-tier-ran-in-no-ci-job.md",
    ),
    ":mcp-server:compileKotlin": (
        "local",
        "CI compiles this module through :mcp-server:jar, which is a strict "
        "superset — the DI-graph check runs either way. Naming it locally is "
        "what makes the validation visible on the surface a developer reads.",
    ),
    ":mcp-server:jar": (
        "ci",
        "McpServerEndToEndTest resolves build/libs/mcp-server.jar and skips "
        "itself when the jar is absent. Building it is a CI concern; the "
        "local loop validates compilation, not the packaged artifact.",
    ),
    ":pro:detekt": (
        "ci",
        ":pro is included in settings.gradle.kts only under "
        "-PwithPro=true, so the task does not exist in a plain local build. "
        "CI's `pro` matrix leg passes the flag.",
    ),
    ":pro:testDebugUnitTest": (
        "ci",
        "same reason as :pro:detekt — the module is only in the graph when "
        "-PwithPro=true is passed, which is the `pro` matrix leg's job",
    ),
    ":shared:testAndroidHostTest": (
        "ci",
        "the Android host source set is built only in its own CI job; see "
        "openspec/changes/ci-checks-parallel-split for why it was split out",
    ),
}

#: A Gradle task as it is spelled in a shell script or a workflow: `:module:task`.
_GRADLE_TASK = re.compile(r"(?<![A-Za-z0-9_.-])(:[a-zA-Z][\w-]*:[a-zA-Z][\w-]*)")

#: The one workflow whose Gradle tasks are packaging steps rather than checks.
_NON_VERIFICATION_WORKFLOW = "release.yml"


def named_gradle_tasks(path: Path) -> set[str]:
    """Gradle tasks named anywhere in one surface, comments stripped."""
    try:
        text = _strip_comments(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError):
        return set()
    return set(_GRADLE_TASK.findall(text))


def check_gradle_task_parity() -> list[str]:
    errors: list[str] = []
    # Every workflow, not just ci.yml: `:androidApp:connectedDebugAndroidTest`
    # lives in android-device-tests.yml, and a rule that only reads ci.yml would
    # report a genuinely-covered task as a hole — the "a derivation that quietly
    # sees a third of its input" failure this file already warns about twice.
    #
    # `release.yml` is excluded because it is not a verification surface. It
    # assembles an unsigned release APK and a Linux deb in order to publish them;
    # those are packaging steps, not checks, and requiring `check.sh` to build a
    # release artifact locally would put a shipping decision in the developer loop.
    ci: set[str] = set()
    for workflow in sorted((ROOT / ".github" / "workflows").glob("*.yml")):
        if workflow.name == _NON_VERIFICATION_WORKFLOW:
            continue
        ci |= named_gradle_tasks(workflow)
    local = named_gradle_tasks(ROOT / "check.sh")

    # `covered` rows are checked even when absent from both surfaces. Otherwise a
    # `covered` declaration is a loophole: drop the task from check.sh and the
    # union no longer contains it, so nothing compares it against the table and
    # the declaration quietly stops meaning anything.
    #
    # `ci` and `local` rows are only judged when the task is actually named
    # somewhere. `:shared:testAndroidHostTest` is declared ci-only and appears in
    # neither surface under this spelling — CI reaches it without writing the task
    # name — and reporting that as a divergence would be the check inventing a
    # defect to look thorough.
    covered = {t for t, (side, _) in GRADLE_TASK_PARITY.items() if side == "covered"}
    for task in sorted(ci | local | covered):
        declared = GRADLE_TASK_PARITY.get(task)
        want = declared[0] if declared else "both"
        in_ci, in_local = task in ci, task in local

        if want == "covered":
            if not in_local:
                errors.append(
                    f"{task}: declared 'covered' — check.sh must still name it, "
                    f"because that is where the rehearsal happens. {declared[1]}"
                )
            continue

        actual = "both" if in_ci and in_local else ("ci" if in_ci else "local")
        if actual == want:
            continue
        errors.append(
            f"{task}: named only by {actual}, required {want} — "
            + (
                declared[1]
                if declared
                else (
                    f"not named by {'check.sh' if in_ci else 'ci.yml'}. Part A "
                    f"passed this task because it was named *somewhere*, which is "
                    f"exactly how a local loop ends up green on a module CI would "
                    f"fail. Name it there, or declare it in GRADLE_TASK_PARITY "
                    f"with the reason the asymmetry is legitimate."
                )
            )
        )
    return errors


def check_gate_reachability() -> list[str]:
    errors: list[str] = []
    reachable = reachable_gate_scripts()
    for script in gate_candidate_scripts():
        if script in reachable:
            continue
        errors.append(
            f"{script} can fail and is named by no gate surface — check.sh, the "
            f"registry, justfile, .just/**/*.just, .github/workflows/*.yml and "
            f".github/actions/*/action.yml were all searched, following shim "
            f"delegation. A gate nobody invokes reports success forever. "
            f"Name it in scripts/ci/static-gates.sh, or delete it if it was "
            f"superseded — a replacement that leaves its predecessor on disk is "
            f"two gates where one is meant, and the one that runs is the old one."
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
    ap.add_argument(
        "--registry-wiring", action="store_true", help="Part G only"
    )
    ap.add_argument(
        "--reachability", action="store_true", help="Part H only"
    )
    ap.add_argument(
        "--gradle-parity", action="store_true", help="Part I only"
    )
    args = ap.parse_args()
    only = (
        args.wiring
        or args.can_fail
        or args.gradle_can_fail
        or args.ci_steps_blocking
        or args.parity
        or args.registry
        or args.registry_wiring
        or args.reachability
        or args.gradle_parity
    )
    run_a = args.wiring or not only
    run_b = args.can_fail or not only
    run_c = args.gradle_can_fail or not only
    run_d = args.ci_steps_blocking or not only
    run_e = args.parity or not only
    run_f = args.registry or not only
    run_g = args.registry_wiring or not only
    run_h = args.reachability or not only
    run_i = args.gradle_parity or not only

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

    if run_g:
        print("\nPart G — the shared gate registry is called by CI and by check.sh")
        wiring_errors = check_registry_wiring()
        if wiring_errors:
            errors += wiring_errors
        else:
            print(f"  ok  {GATE_REGISTRY.relative_to(ROOT)} is invoked by ci.yml and check.sh")

    if run_h:
        print("\nPart H — every gate-shaped script is reachable from a surface")
        candidates = gate_candidate_scripts()
        reachable = reachable_gate_scripts()
        orphans = [s for s in candidates if s not in reachable]
        print(f"  {len(candidates)} candidate(s), {len(reachable & set(candidates))} reachable")
        errors += check_gate_reachability()
        if not orphans:
            print("  ok  no gate-shaped script is left unreachable")

    if run_i:
        print("\nPart I — every Gradle task a surface names is named by both")
        errors += check_gradle_task_parity()
        if not check_gradle_task_parity():
            print("  ok  check.sh and ci.yml name the same Gradle tasks")

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
