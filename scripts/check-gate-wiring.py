#!/usr/bin/env python3
"""check-gate-wiring.py — prove the gates exist AND that they can fail.

Why this exists (2026-10-04): this repository accumulated a family of checks
that were fully configured, looked authoritative, and had never executed. The
instances found in one session:

- `:androidApp:detekt` — a `detekt { }` block, a baseline, and
  `ignoreFailures = false`, but no gate ever invoked the task.
- `no-direct-dispatchers` and `user-scoped-repository` — registered as rule-set
  providers, with no `detekt.yml` block, so detekt never loaded them.
- the doc-sizes gate with `--warn-only || status=1` — `--warn-only` returns 0, so
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
itself must be invoked by both CI and `check.sh`. Part J cross-checks the
SCRIPT_GATES sabotage controls against _registry.GATES.

Usage:
    python3 scripts/check-gate-wiring.py              # every part
    python3 scripts/check-gate-wiring.py --wiring     # Part A only (static, fast)
    python3 scripts/check-gate-wiring.py --can-fail   # Part B only (mutates, restores)

Every gate runs under a deadline (`GATE_TIMEOUT_S` / `GRADLE_GATE_TIMEOUT_S`);
a hang reports "no verdict", never "failed". Gradle-backed controls are
skipped unless `GATE_WIRING_GRADLE=1` — `check.sh` sets it because its daemon
is already warm, and a casual run must not cold-boot one.

Exit codes:
    0 — every gate is wired and every registered gate can fail
    1 — an unwired task, or a gate that passed a sabotaged input
"""

from __future__ import annotations

import os
import pathlib
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
from collections.abc import Callable
import dataclasses
from dataclasses import dataclass
from pathlib import Path

import click
from sgate.commands.base import run
import yaml

# Line-buffer both streams: a killed run used to leave a 0-byte log, because
# stdout is block-buffered on redirect and this file prints one line per gate.
# The last line printed is then the step that was still running — which is the
# only diagnostic a freeze leaves behind.
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(line_buffering=True)  # type: ignore[union-attr]
    except (AttributeError, ValueError):
        pass

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

#: Modules that have a `detekt { }` block, populated by _populate_derived_tables().
#: Populated from file scan (discovery); registry carries exemptions as data.
CONFIGURED_DETEKT_TASKS: dict[str, str]

#: Modules declared in the registry as having detekt { } but deliberately not wired.
#: Format: {module_name: reason_string}
DETEKT_TASK_EXEMPTIONS: dict[str, str]


# ── Part A: wiring ─────────────────────────────────────────────────────────


def _strip_comments(text: str) -> str:
    """Drop `#` comments from shell source, preserving line structure.

    Without this the check greps its own prose: a comment reading "…(:androidApp:detekt
    was one)" satisfied the search for an actual invocation, so unwiring the task
    still passed. That is the same defect class this file exists to catch, one level
    up — a gate that greps the wrong thing and reports success. Only the command text
    is searched now.

    Uses `shlex` which understands quote boundaries, so a `#` inside a quoted string
    is not treated as a comment marker. Falls back to the original line for lines
    that `shlex` cannot parse (e.g. YAML block scalars, which are not shell syntax).
    """
    out_lines = []
    for line in text.splitlines():
        stripped = line.lstrip()
        if stripped.startswith("#"):
            out_lines.append("")
        elif not line.strip():
            out_lines.append("")
        else:
            try:
                tokens = shlex.split(line, comments=True)
                out_lines.append(" ".join(tokens))
            except ValueError:
                # Not shell syntax (e.g. YAML `run: |` block, `on:` key, etc.)
                out_lines.append(line)
    return "\n".join(out_lines)


def gate_text() -> str:
    return "\n".join(
        _strip_comments(p.read_text(encoding="utf-8")) for p in GATE_FILES if p.is_file()
    )


def check_wiring() -> list[str]:
    errors: list[str] = []
    gates = gate_text()
    if not gates:
        return [f"no gate files found among {[str(p.relative_to(ROOT)) for p in GATE_FILES]}"]

    for task, build in sorted(CONFIGURED_DETEKT_TASKS.items()):
        # Exempt modules are declared in DETEKT_TASK_EXEMPTIONS (from the registry).
        # They have detekt { } but are deliberately not wired — skip them.
        module = task.split(":")[1] if ":" in task else ""
        if module in DETEKT_TASK_EXEMPTIONS:
            continue
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
#
# A control mutates its target into the state the gate must reject. Controls are
# plain functions of the path, not source text: as strings they had to be
# escaped into shape (`\n`, `\'`) and `exec` raised SyntaxError on one wrong
# escape — mid-Part-B, after the clean run, taking every control below it in the
# list down with it. The gate-liveness entry carried exactly that defect and
# only showed itself on a day its baseline happened to be green, which reads
# like a regression in whatever changed that day. Functions are written once,
# parsed once, at import.

Control = Callable[[Path], None]


def _replace(old: str, new: str, *, count: int = -1) -> Control:
    """Substitute `old` → `new`, refusing when `old` is not in the file.

    The refusal is the point. Four controls in this registry once matched
    nothing after their target moved on (the test-runs floor, the Room schema
    version, an unwired-backlog anchor, a README schema claim): `replace`
    changed nothing, the gate read an untouched file, correctly passed, and the
    harness reported a control that had proved nothing. A no-op control is
    worse than no control — it is reported as a passing one.
    """

    def control(p: Path) -> None:
        text = p.read_text(encoding="utf-8")
        assert old in text, f"sabotage would be a no-op: {old[:60]!r} not in {p}"
        p.write_text(text.replace(old, new, count), encoding="utf-8")

    return control


def _regex_sub(pattern: str, repl: str, *, count: int = 1, flags: int = 0) -> Control:
    """`re.subn` with the match asserted, so a value that moved cannot no-op."""

    def control(p: Path) -> None:
        text = p.read_text(encoding="utf-8")
        new, n = re.subn(pattern, repl, text, count=count, flags=flags)
        assert n >= 1, f"sabotage would be a no-op: {pattern!r} matched nothing in {p}"
        p.write_text(new, encoding="utf-8")

    return control


def _append(text: str) -> Control:
    """Append text — the shape for "introduce the thing this gate hunts"."""

    def control(p: Path) -> None:
        p.write_text(p.read_text(encoding="utf-8") + text, encoding="utf-8")

    return control


def _drop_lines(needle: str) -> Control:
    """Delete every line containing `needle`; at least one must exist."""

    def control(p: Path) -> None:
        lines = p.read_text(encoding="utf-8").splitlines()
        kept = [ln for ln in lines if needle not in ln]
        assert len(kept) < len(lines), f"sabotage would be a no-op: no line with {needle!r} in {p}"
        p.write_text("\n".join(kept) + "\n", encoding="utf-8")

    return control


_BACKLOG_FIELD_RE = re.compile(r"(?i)^\s*Backlog:\s*[^\n]+", re.M)

def _add_then_remove_backlog_field(p: Path) -> None:
    """backlog-issue-refs sabotage: add a Backlog field to one OPEN issue, then remove it.

    The snapshot has 94 OPEN issues and none carry a Backlog field — the gate is
    already failing on a clean tree. A plain "remove Backlog field" sabotage fails
    with "no OPEN issue has a Backlog field to remove".

    The fix: the sabotage adds a Backlog field to the first OPEN issue that lacks
    one. This changes the gate's verdict from "94 issues missing Backlog" to
    "93 issues missing Backlog" — still failing, but measurably different.
    A subsequent sabotage run (or restore) removes that same field, returning to
    the 94-missing state. The control is the delta in the gate's error count.
    """
    import json as _json

    data = _json.loads(p.read_text(encoding="utf-8"))
    for item in data:
        if item.get("state") != "OPEN":
            continue
        body = item.get("body") or ""
        if _BACKLOG_FIELD_RE.search(body):
            continue  # already has one — skip
        # Add a Backlog field at the end of the body.
        item["body"] = body.rstrip("\n") + "\n\nBacklog: gazon-sabotage-probe\n"
        p.write_text(_json.dumps(data, indent=2) + "\n", encoding="utf-8")
        return
    raise AssertionError("sabotage would be a no-op: no OPEN issue without a Backlog: field")


def _age_directory(days: int) -> Control:
    """Set a directory's mtime into the past — for gates that read staleness."""

    def control(p: Path) -> None:
        old = time.time() - days * 86400
        os.utime(p, (old, old))

    return control


# Controls that fit no builder: they move git state or reshape a block of lines
# rather than substitute one string. They are ordinary functions for the same
# reason the builders exist — a control is read, parsed and debugged once.
def _hooks_path_sabotage(p: Path) -> None:
    """Set `core.hooksPath` and untrack the hooks; content alone cannot undo it."""
    repo = p.parent.parent
    subprocess.run(["git", "config", "core.hooksPath", ".githooks"], cwd=repo, check=True)
    subprocess.run(["git", "rm", "-r", "--cached", "-q", ".githooks"], cwd=repo, check=True)


def _hooks_path_restore(p: Path) -> None:
    """Put the hooks back in the index — see `ScriptGate.sabotage_restore`."""
    subprocess.run(["git", "add", "--", ".githooks"], cwd=p.parent.parent, check=True)


def _strip_suppression_reason(p: Path) -> None:
    """suppression-intent: remove the comment justification under `@file:Suppress`."""
    lines = p.read_text(encoding="utf-8").splitlines(keepends=True)
    assert lines[0].startswith("@file:Suppress"), "unexpected file shape"
    assert lines[1].lstrip().startswith("//"), "no reason line to strip"
    out, dropped = [lines[0]], 0
    for line in lines[1:]:
        if line.lstrip().startswith("//"):
            dropped += 1
            continue
        if dropped and not line.strip():
            continue
        out.append(line)
    assert dropped >= 2, f"expected a multi-line reason, found {dropped}"
    p.write_text("".join(out), encoding="utf-8")


@dataclass(frozen=True)
class ScriptGate:
    """A script gate plus the single input change that must make it fail."""

    name: str
    cmd: list[str]
    sabotage_path: str
    sabotage: Control
    why: str
    # A directory target is restored by mtime, not by content: `check-openspec-stale`
    # reads staleness from `stat().st_mtime`, so a content comparison would report
    # a failed restore for a mutation that was correct.
    target_is_dir: bool = False
    # When True, this control is skipped on a dirty tree (partial test results
    # on disk from a filtered --tests run). The sabotage raises the floor above
    # any plausible count, so it can only fail on a tree with a full-suite XML.
    sabotage_requires_clean_tree: bool = False
    # True when the gate has a meaningful clean-tree invocation. When False, the
    # clean-tree guard is waived — use for gates that are known to fail on a clean
    # tree due to un-triaged real state (e.g. open entries tracking closed issues).
    needs_clean_run: bool = True
    # Extra restoration, run after the content is put back.
    #
    # The default restore is `write_text(original)` and that restores *content* only.
    # A sabotage that also changes git state — `git rm --cached` is the one that
    # mattered — therefore left the index describing a different tree than the files
    # on disk, and the residue survived into the next run: `.githooks/pre-push` came
    # back as "deleted in index, present untracked", which is exactly the condition
    # check-hooks-path.py is supposed to report. The control was breaking the gate it
    # was proving.
    #
    # So a sabotage that touches anything but file content must say how to put it
    # back. Leaving this empty for such a sabotage is the bug this field exists for.
    sabotage_restore: Control | None = None


SCRIPT_GATES = [
    ScriptGate(
        name="detekt-registrations",
        cmd=["./scripts/check-detekt-registrations.sh"],
        sabotage_path="config/detekt/detekt.yml",
        sabotage=_replace('no-runblocking:\n  NoRunBlocking:\n    active: true\n', ''),
        why="a RuleSetId with no detekt.yml block never loads, so the rule never runs",
    ),
    ScriptGate(
        name="baseline-ratchet",
        cmd=[sys.executable, "scripts/check-baseline-ratchet.py"],
        sabotage_path="config/detekt/baseline-shared.xml",
        sabotage=_replace('</CurrentIssues>', '    <ID>Sabotage:Probe.kt:Probe</ID>\n  </CurrentIssues>'),
        why="a baseline that can grow silently stops being a ratchet and becomes a sink",
    ),
    ScriptGate(
        name="hooks-path",
        cmd=[sys.executable, "scripts/check-hooks-path.py", "--quiet"],
        # Untrack *every* hook, not one. The gate asks "does the configured hooks
        # directory contain tracked files?", so removing a single hook while three
        # others stay tracked is a state the gate is right to accept — and a control
        # that expects it to fail proves nothing. The first version of this control
        # emptied one file, which the gate correctly ignored.
        sabotage_path=".githooks/pre-push",
        # Set core.hooksPath as part of the sabotage: on a CI runner the config
        # is unset, so the gate takes its "unset → advisory, exit 0" branch and
        # passes no matter what — the control would prove nothing (and did, on
        # the first CI run that reached it, 2026-10-11). Setting it to .githooks
        # is what a correctly configured machine already has; the restore leaves
        # it there, which is the value the gate itself recommends.
        sabotage=_hooks_path_sabotage,
        # Content restore alone cannot undo this one — see `sabotage_restore`.
        sabotage_restore=_hooks_path_restore,
        why=(
            "hooks travel with the repository; local git config does not. A configured "
            "hooks path with nothing tracked under it is a gate that reports success "
            "because nothing ever asked it a question"
        ),
    ),
    ScriptGate(
        name="skill-drift",
        cmd=[sys.executable, "scripts/check-skill-drift.py"],
        # A wrong command name *inside* the generated block. Editing it by hand is
        # exactly the mistake the gate exists to catch, and it is a content change
        # the default restore puts back — no index games, no custom restore.
        sabotage_path=".agents/skills/singularity-todo-decisions-workflow/SKILL.md",
        sabotage=_replace('`singularity-adr validate`', '`singularity-adr verifai`', count=1),
        why=(
            "a skill that lists commands the tool does not have sends an agent to run "
            "something that does not exist"
        ),
    ),
    ScriptGate(
        name="dead-settings",
        cmd=[sys.executable, "scripts/check-dead-settings.py", "--quiet"],
        # The sabotage is declared in the registry (see _registry.GATE_CONTROLS);
        # this entry exists so Part B proves the gate can actually fail.
        #
        # It used to remove a baseline entry, so the field it exempts became
        # reportable. That was a good control while the baseline carried one —
        # it exercised the whole chain, waiver to report — and it became a
        # control that proved nothing twice: first when `Notifications.reminderDefault`
        # got wired (#657) and the baseline no longer needed it, then when
        # `Notifications.sound` stopped being dead because `PLUMBING` was
        # narrowed to file kinds. Both times the match went stale and the
        # control raised "sabotage would be a no-op".
        #
        # Then the weekend flags were wired and their waivers were removed, and
        # the baseline became empty — which left the control matching a line
        # that only existed in a *comment* and changing that, while the gate
        # correctly stayed green. A control that edits a file without changing
        # what the gate reads is not a control.
        #
        # So the sabotage now goes at the gate's own promise: a persisted
        # setting that no feature reads. It adds a field to the settings model,
        # and the gate must report it.
        sabotage_path="shared/src/commonMain/kotlin/com/singularity/todo/core/settings/SettingsBundle.kt",
        sabotage=None,  # filled from _registry.GATE_CONTROLS by _prefer_registry_controls
        why="a persisted setting rendered in Settings and read by no feature is a " +
            "control that looks like it works; every baseline line is a claim that " +
            "needs its own justification",
    ),
    ScriptGate(
        name="sync-allowlist-regenerated",
        cmd=[sys.executable, "scripts/check-sync-allowlist-regenerated.py"],
        sabotage_path="shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncContract.kt",
        sabotage=_replace(
            '"aiSuppressedTagIds",',
            '"aiSuppressedTagIds",\n        "sabotage_probe_field",',
        ),
        why="if the committed seed differs from the generator output, the gate catches it — and the seed is the one artifact that cannot be regenerated from within the check",
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
        sabotage=_drop_lines('QueryTokenizer'),
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
        sabotage=_append('\ndependencies { implementation("ru.ok.tracer:tracer-crash-report:1.4.0") }\n'),
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
        sabotage_path="scripts/src/traceability/scenarios/tasks/checklist/TASK-CHECK-01.yaml",
        sabotage=_regex_sub(r'^targets: \[android\]$', 'targets: [android, desktop]', flags=re.M),
        why="a scenario claiming a target nothing verifies is the one hole count that grows without anyone reading the diff",
    ),
    ScriptGate(
        name="workflow-exit-status",
        cmd=[sys.executable, "scripts/check-workflow-exit-status.py"],
        # The defect this gate exists for, restored exactly: four CI steps piped their
        # output into `tee`/`tail` and inherited that command's exit status, so a failing
        # `./gradlew` — 109 detekt findings on main — reported success. The sabotage puts
        # the pipe back on the step that runs every test and detekt task.
        sabotage_path=".github/workflows/ci.yml",
        sabotage=_replace(
            "          set -o pipefail\n          ./gradlew --continue \\\n",
            "          ./gradlew --continue \\\n",
        ),
        why=(
            "a step whose status is the pipeline's last command cannot fail on its "
            "producer; the JVM job stayed green through 109 detekt findings for that reason"
        ),
    ),
    ScriptGate(
        name="workflow-env-context",
        cmd=[sys.executable, "scripts/check-workflow-env-context.py"],
        # The sabotage is declared in the registry (see _registry.GATE_CONTROLS);
        # this entry exists so Part B proves the gate can actually fail.
        sabotage_path=".github/workflows/e2e.yml",
        sabotage=None,  # filled from _registry.GATE_CONTROLS by _prefer_registry_controls
        why=(
            "the `env` context holds the variables a workflow defines, not the "
            "runner's environment, so `${{ env.ANDROID_HOME }}` expands to the empty "
            "string and a path built from it names nothing — silently, with no warning"
        ),
    ),
    ScriptGate(
        name="maestro-flow-catalogue",
        cmd=[sys.executable, "scripts/check-maestro-flow-catalogue.py"],
        # The sabotage is declared in the registry (see _registry.GATE_CONTROLS);
        # this entry exists so Part B proves the gate can actually fail.
        sabotage_path="config/docs/maestro-flows.txt",
        sabotage=None,  # filled from _registry.GATE_CONTROLS by _prefer_registry_controls
        why=(
            "a flow removed from the tree without a word leaves the suite smaller "
            "and the run green — the run reports the flows that are left, so the "
            "one that is gone is only visible in a diff nobody reads"
        ),
    ),
    ScriptGate(
        name="ui-wait-budgets",
        cmd=[sys.executable, "scripts/check-ui-wait-budgets.py"],
        # The sabotage is declared in the registry (see _registry.GATE_CONTROLS);
        # this entry exists so Part B proves the gate can actually fail.
        sabotage_path="config/docs/ui-wait-budgets.txt",
        sabotage=None,  # filled from _registry.GATE_CONTROLS by _prefer_registry_controls
        why=(
            "the instrumented suite and the Maestro flows wait for the same screen "
            "from two runners that cannot share a constant, and they drifted apart "
            "until the instrumented suite's 8 s made `android-device-tests` red on "
            "every run of `main` — one written budget is what keeps them honest"
        ),
    ),
    ScriptGate(
        name="release-tags",
        cmd=[sys.executable, "scripts/check-release-tags.py"],
        # A tag that produced no release is the failure this gate was written after:
        # `meta` proved the tag was on main with a green CI run, `publish` still did
        # not run, and the run ended with a red X that nobody read as "no release
        # exists". Three tags had already done it. Appending an unreleased tag to the
        # snapshot reproduces exactly that state — recorded as neither released nor
        # excused in `tags_without_release`.
        sabotage_path="config/docs/releases-snapshot.json",
        sabotage=_replace(
            '    "v0.2.1-rc.1"\n  ],',
            '    "v0.2.1-rc.1",\n    "v9.9.9-probe"\n  ],',
        ),
        why=(
            "a tag whose pipeline ran to green `meta` and produced no release leaves "
            "the version tagged but unshipped, and nothing in the pipeline says so"
        ),
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
        sabotage=_regex_sub(r'const val SCHEMA_VERSION = \d+', 'const val SCHEMA_VERSION = 1'),
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
        sabotage=_drop_lines('kermit-koin'),
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
        sabotage=_append(
            "\ncreate or replace function public.sync_control_probe(p_type text)\n"
            "returns text as $$ select p_type $$ language sql;\n"
        ),
        why="a function added without a fingerprint makes the header understate the schema, and the "
             "header is the part a reader trusts; the live half of #221 needs credentials and stays "
             "manual, so this is the half that can be a gate",
    ),
    # doc-dead-refs: sabotage is in GATE_CONTROLS, filled by _prefer_registry_controls.
    # Part J requires a ScriptGate entry here so the control is recognised as present.
    #
    # `--strict` in the command, because the gate itself runs strict (the registry
    # passes it as a script argument). A control whose clean run is more lenient
    # than the gate it proves would pass on a tree the gate rejects — the control
    # would certify a state the build never accepts.
    ScriptGate(
        name="doc-dead-refs",
        cmd=[sys.executable, "scripts/check-doc-dead-refs.py", "--strict"],
        sabotage_path="README.md",
        sabotage=_append('\nSee `docs/decisions/DefinitelyNotAFile.md` for details.\n'),
        why="a new dead reference must fail until baselined",
    ),
    # log-interpolation: the executable twin of the registry control. It plants
    # a log call that interpolates a type the allowlist does not carry, and the
    # gate must report it. The plant does not compile — it is restored right
    # after, and nothing compiles during the control.
    ScriptGate(
        name="log-interpolation",
        cmd=[sys.executable, "scripts/check-log-interpolation-allowlist.py"],
        sabotage_path="shared/src/commonMain/kotlin/com/singularity/todo/core/config/RemoteConfigCacheRepositoryImpl.kt",
        sabotage=_append(
            '\n\n// TEMPORARY probe planted by the log-interpolation control.\n'
            'private fun logInterpolationControlProbe() {\n'
            '    log.i { "planted=$Task" }\n'
            '}\n'
        ),
        why="a log call that interpolates a type nobody allowlisted must be reported, or the allowlist is a list nobody reads",
    ),
    # doc-links: the lychee gate. The command is the wrapper script, so
    # `_control_identity` resolves it to the same path the registry knows and
    # Part J can join the two. The sabotage appends a *markdown link* to a file
    # that does not exist, which is what this gate and only this gate looks at
    # — `check-doc-dead-refs.py` matches backticked paths, so the same text
    # would pass it. That is the point: the two gates cover different shapes of
    # reference, and a control that only one of them can fail proves nothing
    # about the other.
    ScriptGate(
        name="doc-links",
        cmd=["./scripts/check-doc-links.sh"],
        sabotage_path="docs/agents/domain.md",
        sabotage=_append('\nSee [DefinitelyNotAFile](docs/decisions/DefinitelyNotAFile.md) for details.\n'),
        why="a documentation link that points at a file which is not there is a dead reference the reader follows and finds nothing",
    ),
    # backlog-issue-refs: removed — the gate and its script are gone, and the
    # snapshot it policed (94 OPEN issues, none with a Backlog field) was the
    # failure state it detected, so no control could tell "clean run" from
    # "sabotaged run": both exited 1. See the 2026-10-19 ADR on the three
    # removed gates.
]


# ── Part F: every gate-shaped script is registered ─────────────────────────
#
# Every gate script in `SCRIPT_GATES`, `SABOTAGE_ONLY_GATES`, or `FIXTURE_GATES`
# must appear in the registry (checked by Part J), and every registry gate with
# a script must appear in one of those three lists (or in GATE_EXEMPTIONS).
# Part A already derives Gradle tasks from build files for the same reason.
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

    `setup` writes whatever the gate reads into a temporary directory — a
    directory of JUnit XML, a captured Gradle log, a Kover report — and
    `cmd` is then run with `{tmp}` substituted, and must exit non-zero.
    It is a function rather than source text for the same reason controls
    are: written once, parsed once, at import.

    `needs_clean_run` records whether the gate can be run at all without its
    input. One of the three here cannot: `check-flaky-tests.py` requires
    `--current`, so there is no invocation that means "the clean repository" to it. The clean-run
    guard is not weakened for them — it is *inapplicable*, and saying so
    explicitly is the point. A guard that is silently skipped for an unstated
    reason is the same defect one level up: a check that cannot run, reported
    as a check that passed.
    """

    name: str
    cmd: list[str]
    setup: Callable[[Path], None]
    why: str
    # True when the gate has a meaningful clean-tree invocation, i.e. the
    # "already red would mask the sabotage" guard applies.
    needs_clean_run: bool = True


# ── Fixture inputs ──────────────────────────────────────────────────────────
# One function per synthesised input. As source strings these were pages of
# `'...\\n'` concatenation with every embedded quote doubled; as functions they
# are the files they write.

def _setup_flaky_tests(root: Path) -> None:
    """One test failing now that passed in the previous run."""
    suite = ('<testsuite name="{n}" tests="1" failures="{f}">'
             '<testcase classname="A" name="{n}">{b}</testcase></testsuite>')
    for sub, state in (("cur", "fail"), ("prev", "pass")):
        d = root / sub
        d.mkdir(parents=True, exist_ok=True)
        body = '<failure message="boom"/>' if state == "fail" else ""
        (d / "TEST-A.xml").write_text(
            suite.format(n="one", f=1 if state == "fail" else 0, b=body)
        )


def _setup_measure_flake_rate(root: Path) -> None:
    """Two runs that flip status plus an empty baseline: an unacknowledged flake.

    run-01 passes, run-02 fails — a status flip (Flake A). An empty baseline
    means the flip is unacknowledged, so the gate must report it and exit
    non-zero. A regression that swallows the flip would exit 0 and this probe
    would fail.
    """
    suite = ('<testsuite name="{n}" tests="1">'
             '<testcase classname="A" name="{n}">{b}</testcase></testsuite>')
    for sub, state in (("run-01", "pass"), ("run-02", "fail")):
        d = root / sub
        d.mkdir(parents=True, exist_ok=True)
        body = '<failure message="boom"/>' if state == "fail" else ""
        (d / "TEST-A.xml").write_text(suite.format(n="one", b=body))
    (root / "empty-baseline.txt").write_text("", encoding="utf-8")




FIXTURE_GATES = [
    FixtureGate(
        name="flaky-tests",
        cmd=[sys.executable, "scripts/check-flaky-tests.py", "--current", "{tmp}/cur", "--previous", "{tmp}/prev"],
        setup=_setup_flaky_tests,
        why="a test that passed yesterday and fails today must be reported until it is acknowledged",
        needs_clean_run=False,
    ),
    FixtureGate(
        name="measure-flake-rate",
        cmd=[
            sys.executable,
            "scripts/measure-flake-rate.py",
            "--current", "{tmp}/run-01", "{tmp}/run-02",
            "--runs", "2",
            "--baseline", "{tmp}/empty-baseline.txt",
            "--max-flaky-rate", "0.0",
        ],
        setup=_setup_measure_flake_rate,
        why=(
            "a status flip between two runs must be reported as an unacknowledged "
            "flake until it is baselined — the same property check-flaky-tests.py "
            "is probed for, but here it is the N-run aggregator's own gate logic "
            "(rate ceiling + baseline acknowledgement) that must not be swallows"
        ),
        needs_clean_run=False,
    ),
    # The detekt-rule-coverage fixture that used to close this list was retired
    # when the branch-coverage floor moved into detekt-rules/build.gradle.kts and
    # the gate became `:detekt-rules:koverVerify` (ci.yml + check.sh). Its two
    # halves live elsewhere now: the *declaration* is held by Part C's
    # `kover_verify_rules_without_usable_floor`, and the *run* by the SABOTAGE
    # control `detekt-rule-coverage-floor`, which raises the constant and
    # requires the Gradle task to fail — see the note at the top of Part C.
]


def control_from_edit(edit) -> Control:
    """Build a sabotage callable from a registry `Edit`.

    The registry's edit kinds and this file's builders describe the same
    mutations, so a control declared as data can be executed here without a
    hand-copied twin. An unrecognised kind raises rather than falling back to
    "no control": a control that silently disappeared is exactly what this file
    exists to report, and it would report it as green.
    """
    kind = str(getattr(edit, "kind", ""))
    if kind == "replace":
        return _replace(edit.old, edit.new, count=getattr(edit, "count", -1))
    if kind == "append":
        return _append(edit.new)
    if kind == "drop_lines":
        return _drop_lines(edit.old)
    if kind == "regex":
        flags = re.MULTILINE if "MULTILINE" in str(getattr(edit, "flags", "")) else 0
        return _regex_sub(edit.pattern, edit.repl, count=1, flags=flags)
    if kind == "age_dir":
        return _age_directory(edit.days)
    raise ValueError(
        f"registry control edit kind {kind!r} has no builder in this file; the "
        f"control for {getattr(edit, 'path', '?')} would be silently dropped"
    )


def _prefer_registry_controls(gates: list[ScriptGate]) -> list[ScriptGate]:
    """Fill in any gate whose sabotage is declared in the registry, by name.

    Only entries whose `sabotage` is `None` are filled from `GATE_CONTROLS`, so
    this converts the ones given up to a hand-written twin and leaves every
    other entry exactly as it was.

    The twin is what rotted. `build-output-readers` carried two copies of one
    control: this file's inserted a second Gradle invocation above the readers
    and worked, while the registry's appended a bare invocation to the end of
    `ci.yml` — landing it in `nightly-alert`, a job with no reader and one
    test-capable invocation, which the rule ignores by design. The data copy
    passed every sabotage run and proved nothing, and nothing compared the two.
    """
    reg = _load_registry()
    resolved: list[ScriptGate] = []
    for gate in gates:
        control = reg.GATE_CONTROLS.get(gate.name)
        if control is None or gate.sabotage is not None or control.kind != "sabotage":
            resolved.append(gate)
            continue
        if control.edit is None:
            raise ValueError(f"{gate.name}: registry control declares no edit")
        resolved.append(
            dataclasses.replace(
                gate,
                sabotage_path=control.path,
                sabotage=control_from_edit(control.edit),
                why=control.why,
                needs_clean_run=bool(control.needs_clean_run),
            )
        )
    return resolved


def _load_registry():
    """Import the gate registry from **this** checkout, or fail loudly.

    `sgate` is installed as an editable package, and a sibling worktree can be
    what is installed: on this host `import sgate` resolved into
    `…/sprat/scripts/src/sgate` while the gate under test was `…/noddy`. A bare
    import would then cross-check a different checkout's registry and report a
    clean bill of health about the wrong repository — the one thing a wiring gate
    must never do.

    So the local `scripts/src` is put first on the path, the resolved package
    location is checked against this repo, and a mismatch is an error rather than
    a warning. There is no fallback: a gate that cannot find its own registry has
    no verdict, and "skipped" would be indistinguishable from "checked and green".
    """
    src_root = ROOT / "scripts" / "src"
    if str(src_root) not in sys.path:
        sys.path.insert(0, str(src_root))
    import sgate  # noqa: PLC0415 — the path must be set first

    resolved = Path(sgate.__file__).resolve()
    if not resolved.is_relative_to(ROOT):
        raise RuntimeError(
            f"imported sgate from {resolved}, which is outside this checkout ({ROOT}). "
            f"This gate is checking a registry that is not this repository's. "
            f"Reinstall it from this tree: `uv pip install --system --editable scripts/src/`"
        )
    from sgate.commands import _registry  # noqa: PLC0415 — path already resolved

    return _registry


# ── Derived from the registry ───────────────────────────────────────────────────
#
# These tables are populated once at module load time from the gate registry
# (scripts/src/sgate/commands/_registry.py).  They are the single source of
# truth for Parts E, F, H and J.
#
# GATE_EXEMPTIONS    (script path -> reason):    from _registry.GATE_EXEMPTIONS
# GATE_PARITY        (script path -> (scope, reason)): from GateSpec.parity
# CONTROLLED_GATE_SCRIPTS (set of script paths): from _registry.GATE_CONTROLS[].proves

# Gate scripts that are exempt from the positive-control requirement.
# The key is the repo-relative script path (e.g. "scripts/tests").
# Populated once at module load time below.
GATE_EXEMPTIONS: dict[str, str] = {}

# Derived from the registry. GateSpec.parity is Parity(scope, arg, why).
# We derive scope+reason, ignoring arg (that's GATE_ARG_PARITY).
GATE_PARITY: dict[str, tuple[str, str]] = {}

# Scripts covered by a positive control (via the proves field).
# Populated once at module load time below.
CONTROLLED_GATE_SCRIPTS: set[str] = set()


def _populate_derived_tables() -> None:
    """Populate GATE_EXEMPTIONS, GATE_PARITY, CONTROLLED_GATE_SCRIPTS,
    GRADLE_TASK_PARITY, and KOVER_MIN_FLOOR from the registry.

    Called once at import. The registry is in scripts/src/sgate/commands/_registry.py.
    A bare `import sgate` would pick up whatever is installed — which on this host
    was a different checkout. The foreign-tree guard in _load_registry() prevents that.
    """
    reg = _load_registry()
    # GATE_EXEMPTIONS: keyed by script path -> reason string
    for script_path, control in reg.GATE_EXEMPTIONS.items():
        GATE_EXEMPTIONS[str(script_path)] = control.reason
    # GATE_PARITY: gates with a non-"both" scope
    for spec in reg.GATES:
        if spec.parity and spec.parity.scope != "both":
            GATE_PARITY[str(spec.script)] = (spec.parity.scope, spec.parity.why)
    # CONTROLLED_GATE_SCRIPTS: scripts named in a control's proves field
    for control in reg.GATE_CONTROLS.values():
        if control.proves:
            CONTROLLED_GATE_SCRIPTS.add(control.proves)
    # GRADLE_TASK_PARITY: CI/local asymmetries for individual Gradle tasks (Part I)
    global GRADLE_TASK_PARITY, KOVER_MIN_FLOOR
    GRADLE_TASK_PARITY = {
        task: (p.scope, p.why)
        for task, p in reg.GRADLE_TASK_PARITY.items()
    }
    KOVER_MIN_FLOOR = reg._KOVER_MIN_FLOOR
    # CONFIGURED_DETEKT_TASKS: modules that have a `detekt { }` block (Part A)
    global CONFIGURED_DETEKT_TASKS
    CONFIGURED_DETEKT_TASKS = {}
    for build in sorted(ROOT.glob("*/build.gradle.kts")):
        module = build.parent.name
        text = build.read_text(encoding="utf-8")
        if re.search(r"^\s*detekt\s*\{", text, re.M):
            CONFIGURED_DETEKT_TASKS[f":{module}:detekt"] = str(
                build.relative_to(ROOT)
            )
    # DETEKT_TASK_EXEMPTIONS: registry-declared exemptions for Part A
    global DETEKT_TASK_EXEMPTIONS
    DETEKT_TASK_EXEMPTIONS = reg.DETEKT_TASK_EXEMPTIONS


_populate_derived_tables()



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
        # A control that quietly stops sabotaging is worse than no control: it is
        # reported as a passing control. `_regex_sub` asserts the match.
        sabotage=_regex_sub(r'^(shared:jvmTest )\d+( \d+)$', r'\g<1>99999\g<2>', flags=re.M),
        why="a test source set that ran fewer tests than its recorded floor means coverage was lost",
        sabotage_requires_clean_tree=True,
    ),
    ScriptGate(
        name="instrumented-test-floor",
        # The fixture report has the shape of a real run, so the control fails
        # the gate by moving the floor rather than by having no report at all.
        cmd=[
            sys.executable,
            "scripts/check-instrumented-test-floor.py",
            "--report-dir",
            "scripts/tests/fixtures/instrumented-report",
        ],
        sabotage_path="config/docs/instrumented-test-floor.txt",
        # Rewrite the floor by regex, never by naming its current value: a
        # literal `tests 19` stops matching the moment the floor moves, and a
        # control that quietly stops sabotaging is reported as a passing one.
        sabotage=_regex_sub(r'^tests \d+$', 'tests 99999', flags=re.M),
        why=(
            "the instrumented tier runs on a schedule, on pushes to main and on "
            "demand — a suite whose size nobody watches loses a class quietly, "
            "and the run stays green because the report lists what is left"
        ),
    ),
    ScriptGate(
        name="adr-references",
        cmd=[sys.executable, "scripts/check-adr-references.py"],
        sabotage_path="README.md",
        sabotage=_append('\nSee `docs/decisions/2026-10-05-nonexistent-adr.md` for details.\n'),
        why="a prose reference to a dated-ADR slug carries no path, so a moved or deleted ADR leaves a dangling claim nothing else sees",
    ),
    ScriptGate(
        name="rule-intent",
        cmd=[sys.executable, "scripts/check-rule-intent.py"],
        sabotage_path="config/detekt/baseline-shared.xml",
        sabotage=_replace(
            '</CurrentIssues>',
            '    <ID>TotallyMadeUpRule:Probe.kt:1</ID>\n  </CurrentIssues>',
            count=1,
        ),
        why="a rule producing findings that nobody declared means it is running on detekt's defaults, unchosen",
    ),
    ScriptGate(
        name="openspec-stale",
        cmd=[sys.executable, "scripts/check-openspec-stale.py"],
        sabotage_path="openspec/changes/detekt-rule-has-positive-control",
        # A directory, not a file, and the mutation is its mtime: the gate reads
        # staleness from `stat().st_mtime`, so no file content would move it.
        sabotage=_age_directory(30),
        why="an active change nobody has returned to in three weeks is a decision deferred past the point of usefulness",
        target_is_dir=True,
    ),
    ScriptGate(
        name="suppression-intent",
        cmd=[sys.executable, "scripts/check-suppression-intent.py"],
        sabotage_path="shared/src/jvmMain/kotlin/com/singularity/todo/feature/pomodoro/JvmPomodoroTimer.kt",
        # Strip the whole comment block from a file that has one under the
        # annotation. `JvmPomodoroTimer.kt` carries `@file:Suppress("NoRealDelayInTest")`
        # on line 1 with a 10-line comment justification (lines 2-11); the gate reads a
        # 4-line window, so stripping the first two comment lines is sufficient to leave
        # the suppression unjustified.
        sabotage=_strip_suppression_reason,
        why="a file-level suppression with no recorded reason switches a rule off for every future call in that file",
    ),
    # Drop the selector from one Gradle invocation that follows the test run.
    # The tree stays perfectly valid — every command still works, the suite is
    # still green — and the only symptom is that this invocation measures Gradle's
    # default (excludeTags("slow")) instead of the population the job selected.
    # Measured on 2026-10-16: run 38933517902 failed its traceability step with
    # "declared target produced zero testcases" for a target that had just been
    # measured, because the advisory kover table step re-ran the desktop tests
    # without the selector and the slow scenario classes were skipped.
    # Insert one Gradle invocation between the test run and the steps that read its
    # output. Every reader is already in place and still reads
    # `build/test-results` — what changes is that a step above it can now re-run the
    # test task. That is the shape both real instances had: readers written after
    # Gradle steps that already existed.
    #
    # That paragraph describes what the mutation does; where it comes from is the
    # registry (`_prefer_registry_controls`). It used to be written out here, and
    # this file's copy and the registry's copy diverged: the registry's appended a
    # bare Gradle invocation to the end of `ci.yml`, landing it in `nightly-alert`
    # — a job with no reader and one test-capable invocation, which the rule
    # ignores by design. It passed every sabotage run and proved nothing, while
    # this copy worked, so nothing reported that the copy nobody executed was the
    # one being edited. Run 38961950540 is that report.
    ScriptGate(
        name="build-output-readers",
        cmd=[sys.executable, "scripts/check-build-output-readers.py"],
        sabotage_path=".github/workflows/ci.yml",
        sabotage=None,  # filled from _registry.GATE_CONTROLS by _prefer_registry_controls
        why="a step that reads build output can be ordered after an invocation that rewrites it, and the numbers it reports are then from a run nobody looked at",
    ),
    ScriptGate(
        name="gradle-test-population",
        cmd=[sys.executable, "scripts/check-gradle-test-population.py"],
        sabotage_path=".github/workflows/ci.yml",
        sabotage=_replace(
            "run: ./gradlew :koverVerify -Ptest.tags=fast,slow",
            "run: ./gradlew :koverVerify",
            count=1,
        ),
        why="two Gradle invocations in one job can disagree about which tests \"the tests\" are, and the gate that reads build/test-results/ afterwards is then measuring a population nobody ran",
    ),
    # The waiver-premise control. It renames the CI step the one waived
    # control's `tracked_by` points at, so the anchor names a step that no
    # longer exists — the exact shape of the failure this gate exists for: the
    # waiver keeps waiving while the compensating pass it was granted for has
    # been renamed away.
    ScriptGate(
        name="waiver-references",
        cmd=[sys.executable, "scripts/check-waiver-references.py"],
        sabotage_path=".github/workflows/ci.yml",
        sabotage=None,  # filled from _registry.GATE_CONTROLS by _prefer_registry_controls
        why="a waiver whose reference no longer resolves waives nothing: the compensating "
            "pass it was granted for can be gone and the waiver keeps waiving, which is "
            "how a permanently-red gate becomes noise nobody reads",
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
        sabotage=_replace(
            '    inputs.dir(layout.projectDirectory.dir("../Maestro"))\n'
            '        .withPropertyName("maestroFlows")\n'
            '        .withPathSensitivity(PathSensitivity.RELATIVE)\n',
            '',
            count=1,
        ),
        why="a test task reading a tree outside its module reports a stale verdict when that tree is not an input",
    ),
    # The one control in this file that runs Gradle, and the reason it must:
    # the floor is enforced *by Gradle*. Part C and its tests prove the bound is
    # declared and readable; nothing Python does can prove the task honours it.
    # That is the half a misconfigured Kover — instrumenting nothing, reporting
    # 0% — would quietly get right on paper and wrong in the run, which is
    # precisely the "passes a zero floor" defect the proposal names.
    #
    # Cost is bounded and worth stating, because this file runs early in check.sh
    # (step 7) and after Gradle in ci.yml's tests job, and every Gradle invocation
    # it adds is paid twice per run — once by the harness, once by the gate step.
    # So the clean-run guard is waived (`needs_clean_run=False`): both surfaces
    # execute the very same task on the very same tree a step later — check.sh
    # step 8b (koverLog + koverVerify) and ci.yml's "Kover verify (detekt-rules)"
    # — which is the pass that proves the task is green on real data. Here only
    # the *sabotaged* pass matters, so the control buys one configuration pass,
    # not two. `-q` because only the exit code is read.
    ScriptGate(
        name="detekt-rule-coverage-floor",
        cmd=["./gw", "-q", ":detekt-rules:koverVerify"],
        sabotage_path="detekt-rules/build.gradle.kts",
        # Raise the bound above anything the suite can reach. Rewritten by
        # pattern, never by the value it expects: the moment the floor moves,
        # a literal `str.replace` finds nothing and the control reports a pass
        # it did not earn. `_regex_sub` asserts the match.
        sabotage=_regex_sub(r'^(val DETEKT_RULE_BRANCH_FLOOR = )\d+$', r'\g<1>100', flags=re.M),
        why="branch coverage below the declared floor — the one thing the floor exists to catch, and the thing an unbound or zero bound lets through",
        needs_clean_run=False,
    ),
]


#: A gate that hangs is not a gate that failed — the harness would wait
#: forever and the host would look frozen. Script gates here parse files and
#: finish in seconds; 120s is already generous. Gradle-backed controls get
#: 600s because a cold daemon boot plus configuration is legitimately slow.
GATE_TIMEOUT_S = 120
GRADLE_GATE_TIMEOUT_S = 600

#: `timeout(1)`'s convention. A deadline is not a verdict: callers translate
#: 124 into "no verdict, it hung" rather than "it failed".
TIMEOUT_RC = 124

_GRADLE_COMMANDS = frozenset({"./gw", "./gradlew", "gradle"})

#: Gradle-backed controls are skipped unless this is set to `1`. `check.sh`
#: sets it (its daemon is already warm by step 8); a casual local run must
#: not cold-boot a daemon and hold the shared cache lock for minutes — that
#: is how a "quick check" freezes the host, once, for real.
GRADLE_ENV = "GATE_WIRING_GRADLE"


def _is_gradle_gate(cmd: list[str]) -> bool:
    return cmd[0] in _GRADLE_COMMANDS


def _gradle_controls_enabled() -> bool:
    return os.environ.get(GRADLE_ENV) == "1"


def run_gate(cmd: list[str], timeout_s: int | None = None) -> int:
    """Run one gate to completion, or kill it at the deadline.

    The deadline belongs here and not in the caller: every way into a gate
    goes through this function, so a gate cannot hang the harness by
    accident. `subprocess.run` kills the child on expiry, so no orphan
    survives the deadline either.
    """
    if timeout_s is None:
        timeout_s = GRADLE_GATE_TIMEOUT_S if _is_gradle_gate(cmd) else GATE_TIMEOUT_S
    try:
        proc = subprocess.run(
            cmd, cwd=ROOT, capture_output=True, text=True, check=False, timeout=timeout_s
        )
    except subprocess.TimeoutExpired:
        return TIMEOUT_RC
    return proc.returncode


def _sabotaged_run(gate: ScriptGate, errors: list[str]) -> int | None:
    """Apply the gate's control, run the gate, put the target back.

    Returns the gate's exit code under sabotage, or None when no verdict could
    be produced — a control that raised, a target that would not restore, a
    missing executable. Every such case is *recorded in `errors`*, never raised:
    a registry entry is data, and data must not be able to abort the parts
    behind it. That is not hypothetical — as source text one entry carried a
    SyntaxError and killed Part B from its middle, and only on a day when its
    baseline happened to be green.
    """
    target = ROOT / gate.sabotage_path

    if gate.target_is_dir:
        # Only the mtime is disturbed, so only the mtime has to come back.
        mtime = target.stat().st_mtime
        try:
            gate.sabotage(target)
            return run_gate(gate.cmd)
        except Exception as exc:  # noqa: BLE001 — a broken control must not abort the part
            errors.append(
                f"gate '{gate.name}': the sabotaged run raised {exc!r} — no verdict. "
                f"Fix the control in the registry."
            )
            return None
        finally:
            os.utime(target, (mtime, mtime))

    original = target.read_text(encoding="utf-8")
    try:
        gate.sabotage(target)
        rc = run_gate(gate.cmd)
    except Exception as exc:  # noqa: BLE001 — a broken control must not abort the part
        errors.append(
            f"gate '{gate.name}': the sabotaged run raised {exc!r} — no verdict. "
            f"Fix the control in the registry."
        )
        return None
    finally:
        target.write_text(original, encoding="utf-8")
        if gate.sabotage_restore is not None:
            try:
                gate.sabotage_restore(target)
            except Exception as exc:  # noqa: BLE001 — report, never crash the part
                errors.append(
                    f"gate '{gate.name}': sabotage_restore raised {exc!r} — "
                    f"{gate.sabotage_path} may be left modified; restore it by hand."
                )

    if target.read_text(encoding="utf-8") != original:
        errors.append(
            f"gate '{gate.name}': sabotage did not restore {gate.sabotage_path}. "
            f"Restoring from the recorded copy and failing."
        )
        target.write_text(original, encoding="utf-8")
        return None
    return rc


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

        if _is_gradle_gate(gate.cmd) and not _gradle_controls_enabled():
            print(f"  skip  {gate.name}: gradle-backed control — set {GRADLE_ENV}=1 "
                  f"to include it (check.sh does; see step 8 there)")
            continue

        # Verify the gate passes on the real tree first. A gate that is already red
        # proves nothing about the sabotage, and would mask the result.
        if gate.sabotage_requires_clean_tree and _tree_is_dirty():
            print(f"  skip  {gate.name}: tree has partial test results on disk — "
                  f"sabotage control requires a clean full-suite run to verify")
            continue
        if gate.needs_clean_run:
            baseline_rc = run_gate(gate.cmd)
            if baseline_rc == TIMEOUT_RC:
                errors.append(
                    f"gate '{gate.name}' timed out on a clean run — no verdict. "
                    f"A hang is not a failure; find what it waits on first."
                )
                continue
            if baseline_rc != 0:
                errors.append(
                    f"gate '{gate.name}' already fails on a clean tree (exit {baseline_rc}). "
                    f"Fix the underlying failure before trusting its sabotage control."
                )
                continue
        else:
            # Stated rather than skipped, so a reader of the output can see
            # which controls had no clean-run guard and why: waived in the
            # registry, not missing — the gate step right after this file runs
            # the real pass on the real tree.
            print(f"  --  {gate.name}: no clean-run guard (waived in the registry); "
                  f"sabotage control only")

        rc = _sabotaged_run(gate, errors)
        if rc is None:
            continue
        if rc == TIMEOUT_RC:
            errors.append(
                f"gate '{gate.name}': the sabotaged run timed out — a hang is "
                f"not a verdict, and not evidence the gate can fail."
            )
            continue

        if rc == 0:
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
        if _is_gradle_gate(gate.cmd) and not _gradle_controls_enabled():
            print(f"  skip  {gate.name}: gradle-backed control — set {GRADLE_ENV}=1 "
                  f"to include it (check.sh does)")
            continue
        if gate.needs_clean_run:
            baseline_rc = run_gate(gate.cmd)
            if baseline_rc == TIMEOUT_RC:
                errors.append(
                    f"gate '{gate.name}' timed out on a clean run — no verdict. "
                    f"A hang is not a failure; find what it waits on first."
                )
                continue
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
                gate.setup(root)
            except Exception as exc:  # noqa: BLE001 — report, never crash the gate
                errors.append(f"gate '{gate.name}': fixture setup raised {exc!r}")
                continue
            cmd = [part.replace("{tmp}", tmp) for part in gate.cmd]
            sabotaged_rc = run_gate(cmd)

        if sabotaged_rc == TIMEOUT_RC:
            errors.append(
                f"gate '{gate.name}': the sabotaged run timed out — a hang is "
                f"not a verdict, and not evidence the gate can fail."
            )
        elif sabotaged_rc == 0:
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
#
# The same reasoning covers a kover `verify` rule: a bound that is absent, zero
# or far below anything the suite can miss makes `koverVerify` incapable of
# failing, and a task that cannot fail reports a verdict nobody derived. This
# replaced the FIXTURE control for `scripts/check-detekt-rule-coverage.py`,
# which was deleted when the floor moved into detekt-rules/build.gradle.kts —
# that control proved a *Python parser* applied a floor; this proves the Gradle
# declaration the gate actually reads is a floor at all.
#
# What a static read cannot prove is that a real run below the bound fails the
# task — so that half lives in the SABOTAGE control
# `detekt-rule-coverage-floor`, which raises the constant and re-runs
# `:detekt-rules:koverVerify`. It is the only control in this file that runs
# Gradle, and it is affordable for a reason worth stating: the module's tests
# run before it in both surfaces that call this script (ci.yml's Gradle step,
# and check.sh step 9, whose run the baseline merely moves earlier), so it buys
# a configuration pass and a verify pass rather than a test suite.

_DETEKT_BLOCK = re.compile(r"detekt\s*\{(.*?)\n\s*\}", re.DOTALL)

#: The lowest kover verify floor this repository accepts, in percent.
#:
#: detekt-rules measured 65.1% branch coverage on 2026-10-10 and the floor was
#: set to 60 in the same sweep (detekt-rules/build.gradle.kts, DETEKT_RULE_BRANCH_FLOOR).
#: The bound is only asked to be sane, not current: raising a floor needs no edit
#: here, while zeroing one — or halving it — is the "a floor that cannot fail"
#: defect and has to be a deliberate change to this constant too.
#: Sourced from the registry (_registry._KOVER_MIN_FLOOR) by _populate_derived_tables().
KOVER_MIN_FLOOR: int


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


def _kover_blocks(text: str) -> list[str]:
    """The contents of each `kover { … }` block in a Kotlin build script.

    Brace matching, not a regex: a regex cannot balance braces, and the block is
    where the rules live. Without reading only this far, a `verify { }` block
    belonging to some other plugin — or a `minValue` anywhere else in the file —
    would qualify a module that declares no kover floor at all, and the check
    would be reporting on text it never established was kover's.

    Full-line `//` comments are dropped first, because the comment above the real
    block quotes the API by name (`reports { total { verify { rule { bound {`),
    and prose that names a construct must not count as declaring it. A comment
    with unbalanced braces would otherwise derail the matcher entirely.
    """
    without_comments = re.sub(r"^\s*//.*$", "", text, flags=re.M)
    blocks: list[str] = []
    for match in re.finditer(r"\bkover\s*\{", without_comments):
        depth = 0
        for i in range(match.end() - 1, len(without_comments)):
            if without_comments[i] == "{":
                depth += 1
            elif without_comments[i] == "}":
                depth -= 1
                if depth == 0:
                    blocks.append(without_comments[match.end():i])
                    break
    return blocks


def kover_verify_rules_without_usable_floor() -> list[str]:
    """Modules whose `kover { … verify { … bound { minValue } } }` has no usable floor.

    A module qualifies when its `kover` block contains a `verify` block — the
    two are read in that nesting, so an unrelated `verify {` does not count. It
    then needs at least one `minValue` bound, declared *inside* the kover block,
    of at least `KOVER_MIN_FLOOR` — the *strongest* bound, because one rule that
    can fail is enough to make the task fail (Kover fails the task when any rule
    fails).

    A bound may name a constant instead of a literal (`minValue = THE_FLOOR`,
    with `val THE_FLOOR = 60` in the same file) — that is how the real floor is
    written, so the constant is resolved, and declarations are read from the
    whole file because they sit above the block. A bound whose value cannot be
    resolved is reported like a missing one: a floor this gate cannot read is a
    floor it cannot vouch for. The floor minimum is sourced from the registry
    (_KOVER_MIN_FLOOR) by _populate_derived_tables().
    """
    out: list[str] = []
    for build in sorted(ROOT.glob("*/build.gradle.kts")):
        module = build.parent.name
        text = build.read_text(encoding="utf-8")
        # `verify {` as a block *inside* `kover {`: `koverVerify` in prose, the
        # word `verification` in a task group, and another plugin's verify block
        # must not qualify a module that declares no kover rule at all.
        body = "\n".join(_kover_blocks(text))
        if not re.search(r"\bverify\s*\{", body):
            continue
        constants = dict(re.findall(r"\bval\s+(\w+)\s*=\s*(\d+)", text))
        bounds: list[int] = []
        unresolvable = 0
        for raw in re.findall(r"minValue\s*=\s*([A-Za-z_]\w*|\d+)", body):
            if raw.isdigit():
                bounds.append(int(raw))
            elif raw in constants:
                bounds.append(int(constants[raw]))
            else:
                unresolvable += 1
        if not bounds:
            out.append(
                f":{module}:kover verify has no minValue bound"
                + (
                    " (the one it names could not be resolved to a number)"
                    if unresolvable
                    else ""
                )
            )
        elif max(bounds) < KOVER_MIN_FLOOR:
            out.append(
                f":{module}:kover verify floor {max(bounds)} is below {KOVER_MIN_FLOOR}"
            )
    return out


def check_gradle_can_fail() -> list[str]:
    bad = non_enforcing_gradle_tasks()
    if not bad:
        print("  ok  every configured detekt task is enforcing (ignoreFailures != true)")
    else:
        print(f"  !!  {len(bad)} detekt task(s) set ignoreFailures = true")
    floors = kover_verify_rules_without_usable_floor()
    if not floors:
        print(f"  ok  every kover verify rule declares a floor >= {KOVER_MIN_FLOOR}%")
    messages = [
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
    messages += [
        "\n".join(
            [
                f"  {finding}, so `koverVerify` passes whatever the tests covered.",
                "  A floor that cannot fail is the same defect as no floor: the gate",
                "  reports a verdict nobody derived. Declare `bound { minValue = ... }`",
                f"  at {_KOVER_MIN_FLOOR} or above (the floor is set below the measured",
                "  value — see DETEKT_RULE_BRANCH_FLOOR in the module's build file), and",
                "  record why if it ever has to go down.",
            ]
        )
        for finding in floors
    ]
    return messages


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
        try:
            data = yaml.safe_load(wf.read_text(encoding="utf-8"))
        except yaml.YAMLError:
            continue
        if not isinstance(data, dict):
            continue
        for job_name, job in data.get("jobs", {}).items():
            if not isinstance(job, dict):
                continue
            for step in job.get("steps", []):
                if not isinstance(step, dict):
                    continue
                name = step.get("name", "")
                # continue-on-error is on the step itself
                continue_on_error = step.get("continue-on-error", False)
                # run commands are in the 'run' field (string or multiline)
                run_text = step.get("run", "")
                if isinstance(run_text, list):
                    run_text = "\n".join(run_text)
                mechanism = ""
                if continue_on_error:
                    mechanism = "continue-on-error: true"
                elif _softens_the_step(run_text):
                    mechanism = "`|| true` on the last command"
                if mechanism:
                    declared = bool(_ADVISORY_MARKER.search(str(name)))
                    out.append((wf.name, str(name), mechanism, declared))
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
#
# GATE_PARITY (script path -> (scope, reason)) is derived from the registry at
# module load time: only gates whose scope != "both" are stored.
# GATE_ARG_PARITY is still local (argument-level asymmetries, computed from surfaces).
#: (gate, leading argument), value is where it is expected.
#:
#: `--since` and `--skill-symbols` change *what* is measured rather than
#: *whether* a gate runs, so a name-only comparison would call them both and
#: miss the difference that matters.
GATE_ARG_PARITY: dict[tuple[str, str], str] = {
    ("scripts/check-doc-dead-refs.py", "--skill-symbols"): (
        "both"
    ),
    ("scripts/check-test-runs.py", "--require shared:testAndroidHostTest"): "ci",
    ("scripts/check-test-runs.py", "--require mcp-server:test"): "ci",
}

_ADVISORY_NOTE = {
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


def _gate_parity_skip_set() -> set[str]:
    """Script paths that should not be subject to parity checking.

    FixtureGate entries synthesize their own input rather than mutating the repo,
    so they cannot provide a clean-tree sabotage proof — parity checking is
    irrelevant for them. Exempt gates have no control at all, so the same applies.
    """
    skip: set[str] = set()
    for sg in [*SCRIPT_GATES, *FIXTURE_GATES]:
        if isinstance(sg, FixtureGate):
            # FixtureGate.cmd is e.g. [sys.executable, "scripts/check-flaky-tests.py", ...]
            if len(sg.cmd) >= 2:
                skip.add(sg.cmd[1])
    skip.update(GATE_EXEMPTIONS.keys())
    return skip


def check_gate_parity() -> list[str]:
    ci = _gate_invocations(_gate_texts("ci"))
    local = _gate_invocations(_gate_texts("local"))
    skip = _gate_parity_skip_set()
    errors: list[str] = []

    for gate in sorted(set(ci) | set(local)):
        if gate in skip:
            continue
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

#: `sgate <command>` — a gate delegated to the sgate CLI. The command name is the
#: stem of a module under scripts/src/sgate/commands/, and that module names the
#: underlying scripts/check-* it runs. Without this the sgate migration made
#: every delegated gate invisible to Parts F and H.
#: `sgate <command>` in a surface. The command name may contain a dash — `sgate
#: run-all` is the driver every surface invokes — and a character class without
#: one matched the `run` in `run-all`, which resolved to nothing and, under the old
#: blanket fallback, marked all 32 registry scripts reachable.
_SGATE_INVOCATION = re.compile(
    r"(?:^|[\s'\"(=|])sgate\s+([a-z][a-z0-9_-]*)", re.MULTILINE
)


def _sgate_backing_scripts() -> dict[str, list[str]]:
    """sgate command name -> the scripts/check-* paths its module runs.

    Derived from the command modules themselves, so a new shim is picked up
    without a second registration. A command that runs no check-* script
    (run-all, info, render-registry) is absent from the map, which is correct:
    it is not a gate in the Part F/H sense.
    """
    out: dict[str, list[str]] = {}
    cmd_dir = ROOT / "scripts" / "src" / "sgate" / "commands"
    if not cmd_dir.is_dir():
        return out
    # Keyed by the *registered command name*, not the module filename. Five modules
    # rename themselves — `check_benchmarks.py` registers `check-benchmarks`,
    # `manifest_verify.py` registers `manifest-verify`, and `run_all.py` registers
    # `run-all` — so a surface writing `sgate manifest-verify` matched no module and
    # fell through to the catch-all, which marked every registry script reachable
    # because one unresolvable name existed anywhere. The name a surface writes is
    # the identity that has to resolve.
    # Both spellings appear in the shims:
    #   repo_root / "scripts/check-x.py"          (one literal)
    #   repo_root / "scripts" / "check-x.py"      (two literals)
    one = re.compile(r'["\'](scripts/check[-_][\w.-]+\.(?:py|sh))["\']')
    two = re.compile(r'["\']scripts["\']\s*/\s*["\'](check[-_][\w.-]+\.(?:py|sh))["\']')
    for mod in sorted(cmd_dir.glob("*.py")):
        try:
            text = mod.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        found = {m.group(1) for m in one.finditer(text)}
        found |= {f"scripts/{m.group(1)}" for m in two.finditer(text)}
        # Keep only paths that exist: a shim may mention a renamed predecessor
        # as a fallback (`check-speckit-stale.py` when the real script is
        # `check-openspec-stale.py`), and counting a fiction would demand a
        # control for a gate that does not exist.
        found = {s for s in found if (ROOT / s).is_file()}
        if found:
            name = _registered_command_name(text, mod.stem)
            out[name] = sorted(set(out.get(name, [])) | found)
    for name, scripts in _registry_backing_scripts().items():
        out[name] = sorted(set(out.get(name, [])) | set(scripts))
    return out


def _registered_command_name(module_text: str, fallback: str) -> str:
    """The name `@click.command("…")` registers, or the module stem when unnamed.

    The surface writes the command name; the module is only where it is written.
    """
    match = re.search(r'@click\.command\(\s*"([\w-]+)"\s*\)', module_text)
    return match.group(1) if match else fallback


def _registry_backing_scripts() -> dict[str, list[str]]:
    """Registry-derived gate command → its script.

    Most gates are not shim modules at all: `sgate version_catalog_has_no_literals`
    is generated from a `GateSpec` in `_registry.py`. The old fallback reached them
    only because the `_registry` module's own text contains every script path as a
    literal — so *any* unresolvable name marked all of them reached. This derives
    the mapping instead, using the registry's own slug rule, so a name a surface
    writes resolves to the one script it runs.
    """
    reg = _load_registry()
    out: dict[str, list[str]] = {}
    for spec in reg.GATES:
        if not spec.script:
            continue
        script = str(spec.script)
        if not script.endswith((".py", ".sh")):
            continue
        if not (ROOT / script).is_file():
            continue
        command = reg._module_name(spec.name)
        out[command] = sorted(set(out.get(command, [])) | {script})
    return out


def _module_backed_gate_commands() -> set[str]:
    """Commands of registered gates implemented as `python3 -m <module>`.

    The registry records a module name in `GateSpec.script` for these, so the file
    is not under `scripts/check-*.py` and nothing in the backing map can match.
    They are exempt from the reachability corpus for the same reason Part F exempts
    them from needing a control: the proving is their own unit tests.
    """
    reg = _load_registry()
    return {
        reg._module_name(spec.name)
        for spec in reg.GATES
        if spec.script and not str(spec.script).endswith((".py", ".sh"))
    }


#: A gate line in `GATE_REGISTRY`: `gate <blocking|advisory> "name" <command>`.
#: Kept identical to `run_all._GATE_LINE` — the two files parse the same registry,
#: and a divergence between their parsers would put the reachability corpus out of
#: step with what CI runs.
_REGISTRY_GATE_LINE = re.compile(r'^gate\s+(blocking|advisory)\s+"([^"]+)"\s+(.+)$')


def _registered_gate_scripts() -> list[str]:
    """Scripts of every gate in the registry — what `sgate run-all` actually runs.

    Read from `GATE_REGISTRY` (`scripts/ci/static-gates.sh`), because that is the
    file `run_all.py` loads (`_load_static_gates`) — not from `_registry.GATES`.

    The two disagreed. `backlog-issue-refs` was deleted from the shell registry in
    #657, but its `GateSpec` survived in `_registry.py`, so reading the Python
    mirror answered "what does run-all run" with a set containing a gate run-all
    has not run since #657. That stale entry is what kept
    `check-backlog-issue-refs.py` in the reachable set, and Part H — whose whole
    subject is reachability — asserted against it. The mirror is 39 entries wide
    against the registry's 29, 13 of them with no gate line at all.

    Narrow on purpose. The old fallback marked reachable *every* script any command
    module names, which is a superset of the registry: a gate retired in #657 kept
    its command module, and `sgate run-all` — the one invocation whose name resolved
    to no module — carried that whole superset into the reachable set. Part H then
    reported 33 of 33 candidates reachable and could not report the orphan it exists
    to find.

    Routing mirrors `run_all._run_gate`: `sgate <name>` resolves through the backing
    map, `python3 <script>` and `./<script>` name their path, and a bare bash
    function name runs no `scripts/check-*` file.
    """
    backing = sgate_backing_scripts()
    out: set[str] = set()
    for _mode, _name, command in _registry_gate_commands():
        try:
            tokens = shlex.split(command)
        except ValueError:
            continue
        if not tokens:
            continue
        head, *args = tokens
        if head == "sgate" and args:
            out.update(backing.get(args[0], ()))
        elif head == "python3" and args:
            out.update(s for s in (args[0],) if s.endswith((".py", ".sh")))
        elif head.startswith("./"):
            candidate = head[2:]
            out.update((candidate,) if candidate.endswith((".py", ".sh")) else ())
    return sorted(out)


def _registry_gate_commands() -> list[tuple[str, str, str]]:
    """`(mode, display_name, command)` for every gate line in `GATE_REGISTRY`.

    The same parse `run_all._load_static_gates` performs, kept in step with it so
    "what the registry registers" means one thing in both places.
    """
    if not GATE_REGISTRY.is_file():
        return []
    out: list[tuple[str, str, str]] = []
    for line in GATE_REGISTRY.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "gate()" in stripped:
            continue
        match = _REGISTRY_GATE_LINE.match(line)
        if not match:
            continue
        mode, name, cmd = match.group(1), match.group(2), match.group(3).rstrip()
        # Inline comment, the same cut `run_all` applies.
        if " # " in cmd:
            cmd = cmd.split(" # ", 1)[0].rstrip()
        out.append((mode, name, cmd))
    return out


#: `sgate` commands that run no check script and therefore back nothing. Listed so
#: the "this invocation resolves to nothing" report has a known-good baseline; a
#: name outside this set that resolves to nothing is a typo or a retired gate.
_NON_GATE_SGATE_COMMANDS = frozenset({"info", "render-registry"})

#: `sgate` invocations that resolved to no command module, filled by
#: `reachable_gate_scripts()` and reported by `check_gate_reachability()`. A list
#: rather than a return value because every other caller wants only the set.
_UNRESOLVED_SGATE_INVOCATIONS: list[str] = []

_SGATE_BACKING: dict[str, list[str]] | None = None


def sgate_backing_scripts() -> dict[str, list[str]]:
    """The sgate command → scripts/check-* map for the current ROOT.

    Recomputed each call (the map is a few dozen small file reads) so tests
    that rebind ROOT see the right corpus; a module-level cache would pin the
    first ROOT forever and report the real repository's gates in a fixture.
    """
    return _sgate_backing_scripts()


def registered_gate_scripts() -> dict[str, list[str]]:
    """Gate script path -> the files that invoke it.

    `just` recipes are included, not just `check.sh` and CI: three gates
    (`check-adr-references`, `check-gate-honesty`, `check-kiwi-gaps`)
    are reachable only through a recipe, and a registry that
    looked only at `check.sh` would not see them at all.

    `sgate <name>` invocations count too, resolved through the command module
    to the underlying scripts/check-*. The sgate migration moved most registry
    entries from `python3 scripts/x.py` to `sgate x`; a scanner that only knew
    the old spelling reported 20+ real gates as uncontrolled.
    """
    surfaces = list(GATE_FILES) + sorted(ROOT.glob(f".just/{_JUST_FILES}"))
    backing = sgate_backing_scripts()
    out: dict[str, list[str]] = {}
    for surface in surfaces:
        try:
            text = surface.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        site = str(surface.relative_to(ROOT))
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
            r"(?:^|[\s'\"(=|])(?:python3\s+)?(?:\./)?(scripts/check-[\w.-]+\.(?:py|sh))",
            text,
            re.MULTILINE,
        ):
            out.setdefault(match.group(1), []).append(site)
        for match in _SGATE_INVOCATION.finditer(text):
            for script in backing.get(match.group(1), []):
                out.setdefault(script, []).append(site)
    return out


def controlled_gate_scripts() -> set[str]:
    """Every gate script that some control in the registry covers.

    Derived from _registry.GATE_CONTROLS[].proves — the single join key.
    Previously built by iterating SCRIPT_GATES+SABOTAGE_ONLY_GATES+FIXTURE_GATES
    and extracting the script path from cmd. The registry is the source of truth
    for what has a positive control; the old per-list iteration is replaced.
    """
    return CONTROLLED_GATE_SCRIPTS


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
    # Two legitimate spellings, both of which *read* the registry:
    #   bash scripts/ci/static-gates.sh   — direct execution
    #   sgate run-all                     — the CLI dispatches from the same file
    # The path must appear as an ARGUMENT to bash/sh (or run-all as the sgate
    # subcommand), not merely anywhere in the file. A plain substring search is
    # satisfied by `see scripts/ci/static-gates.sh` in an error message, so
    # deleting the real call while leaving the prose would pass — the same "a
    # check that greps the wrong thing and reports success" defect this file
    # exists to catch, one function over.
    # The `||` (or `;&`, etc.) lookahead is required to avoid false-positives from
    # comment lines and echo strings that merely mention "sgate run-all" without
    # actually invoking it.  After _strip_comments(), the only surviving mention
    # is the echo line inside the error handler:  `see sgate run-all output above`.
    # Without the lookahead that line would incorrectly satisfy the pattern,
    # masking a sabotage of the actual call (e.g. `sgate run-x ||`).
    invocation = re.compile(
        r"(?:^|\s)(?:bash|sh)\s+[\w./-]*scripts/ci/static-gates\.sh"
        r"|(?:^|\s)sgate\s+run-all\b(?=\s*[||;&])"
    )

    for name, path in sorted(callers.items()):
        if not path.is_file():
            errors.append(f"{name} does not exist, so it cannot call the registry")
            continue
        text = _strip_comments(path.read_text(encoding="utf-8"))
        if not invocation.search(text):
            errors.append(
                f"{name} does not invoke scripts/ci/static-gates.sh "
                f"(neither `bash scripts/ci/static-gates.sh` nor `sgate run-all`). "
                f"The registry is the single list of gates; a caller that bypasses "
                f"it reports green for gates it never ran."
            )
    return errors


# ── Part H: SCRIPT_GATES vs _registry.GATES cross-check ─────────────────────
#
# SCRIPT_GATES (the sabotage controls in this file) is the authoritative record
# of which gates have a positive control. The registry (_registry.GATES) is the
# authoritative record of which gates exist. Both must agree.
#
# Two warnings, because the failure modes differ:
# - A SCRIPT_GATES entry whose script is absent from the registry: the control
#   is testing a gate that no longer exists, so it proves nothing.
# - A registry gate (python3/shell) with no SCRIPT_GATES entry: the gate has
#   no positive control.


def _load_registry():
    """Import the gate registry from **this** checkout, or fail loudly.

    `sgate` is installed as an editable package, and a sibling worktree can be
    what is installed: on this host `import sgate` resolved into
    `…/sprat/scripts/src/sgate` while the gate under test was `…/noddy`. A bare
    import would then cross-check a different checkout's registry and report a
    clean bill of health about the wrong repository — the one thing a wiring gate
    must never do.

    So the local `scripts/src` is put first on the path, the resolved package
    location is checked against this repo, and a mismatch is an error rather than
    a warning. There is no fallback: a gate that cannot find its own registry has
    no verdict, and "skipped" would be indistinguishable from "checked and green".
    """
    src_root = ROOT / "scripts" / "src"
    if str(src_root) not in sys.path:
        sys.path.insert(0, str(src_root))
    import sgate  # noqa: PLC0415 — the path must be set first

    resolved = Path(sgate.__file__).resolve()
    if not resolved.is_relative_to(ROOT):
        raise RuntimeError(
            f"imported sgate from {resolved}, which is outside this checkout ({ROOT}). "
            f"This gate is checking a registry that is not this repository's. "
            f"Reinstall it from this tree: `uv pip install --system --editable scripts/src/`"
        )
    from sgate.commands import _registry  # noqa: PLC0415 — path already resolved

    return _registry


def _control_identity(cmd: list[str]) -> str | None:
    """The registry identity a control command names, or None if it names none.

    Three shapes exist and `cmd[1]` only ever answered one of them:

    * `[python3, "scripts/check-x.py", …]` — `cmd[1]` is the script path
    * `[python3, "-m", "traceability", …]` — `cmd[1]` is the literal `"-m"`, which
      matches no registry entry. The two `traceability` gates therefore had no
      control as far as this join could tell, and were covered only because #669
      added a `GATE_EXEMPTIONS` entry. The next `-m` gate gets the same exemption
      by accident, and this join reports the wrong reason.
    * `["./scripts/check-x.sh"]` — a one-element shell command. `len(cmd) >= 2` is
      false, so the entry left the join entirely.

    Resolved by shape, not by index: a module command keys on its module, anything
    else on its first non-flag argument. A command with no such argument names no
    registry gate and is skipped rather than joined on a wrong key.
    """
    if not cmd:
        return None
    if len(cmd) >= 3 and cmd[1] == "-m":
        return cmd[2]
    if len(cmd) == 1:
        return cmd[0][2:] if cmd[0].startswith("./") else cmd[0]
    for token in cmd[1:]:
        if token.startswith("-"):
            continue
        return token[2:] if token.startswith("./") else token
    return None


def check_script_gates_vs_registry() -> list[str]:
    errors: list[str] = []
    # A failure here is a failure of the gate, not a skip: an unimportable or
    # foreign registry means Parts F and J have nothing to check, and reporting
    # that as "skipped" would convert "not checked" into "checked and green".
    try:
        _registry = _load_registry()
    except Exception as exc:  # noqa: BLE001 — reported, never swallowed
        return [f"Part J could not load this checkout's gate registry: {exc}"]

    gates = _registry.GATES

    # Map: script path (str) -> list of gate display names that use it
    script_to_names: dict[str, list[str]] = {}
    for spec in gates:
        if spec.script:
            script_to_names.setdefault(str(spec.script), []).append(spec.name)

    # Two maps, because they answer different questions.
    #
    # `control_script_to_name` is every control's registry identity — used to decide
    # which registered gates have a control. `sg_script_to_name` is only the controls
    # that claim to be *registry* controls, and it is the one checked for naming a
    # script the registry does not have. A SABOTAGE_ONLY or FIXTURE control for a
    # local-only gate legitimately names a script that was never registered; treating
    # it as a registry claim turns a documented local gate into a Part J failure.
    control_script_to_name: dict[str, str] = {}
    for sg in (*SCRIPT_GATES, *SABOTAGE_ONLY_GATES, *FIXTURE_GATES):
        key = _control_identity(sg.cmd)
        if key is not None:
            control_script_to_name[key] = sg.name
    sg_script_to_name: dict[str, str] = {}
    for sg in SCRIPT_GATES:
        key = _control_identity(sg.cmd)
        if key is not None:
            sg_script_to_name[key] = sg.name

    # 1. SCRIPT_GATES entries whose script is absent from the registry.
    #    GATE_EXEMPTIONS is consulted: gates intentionally absent from the registry
    #    (e.g. local-only gates) may still carry sabotage controls for documentation.
    for script_path, sg_name in sg_script_to_name.items():
        if script_path not in script_to_names:
            if script_path in GATE_EXEMPTIONS:
                continue  # documented: intentionally not in the shared registry
            errors.append(
                f"Part J: SCRIPT_GATES entry '{sg_name}' "
                f"references script '{script_path}' which is not in _registry.GATES. "
                f"Add it to the registry or remove the control."
            )

    # 1b. A twin and the registry entry it stands for must agree.
    #
    # The two copies of a control are the same control until one of them is
    # edited: the registry declares *what* changes and *which command* proves
    # it, and the twin in this file does the same thing in code. Nothing
    # compared them, and the copy that rotted was the one that ran:
    # `build-output-readers` carried a hand-written twin that sabotaged a
    # different file than the registry named, passed every sabotage run, and
    # proved nothing. A twin that points somewhere else is a control that
    # certifies a different thing than the registry claims it certifies.
    #
    # The comparison is on the two things that make a control a control: the
    # file it changes, and the command it runs afterwards.
    for sg in SCRIPT_GATES:
        control = _registry.GATE_CONTROLS.get(sg.name)
        if control is None or control.kind != "sabotage":
            continue
        if any("{tmp}" in arg for arg in control.argv):
            # A fixture path is chosen at run time; it is not part of the
            # contract the twin and the registry share.
            continue
        registry_path = control.path
        if sg.sabotage_path != registry_path:
            errors.append(
                f"Part J: the control for '{sg.name}' and the registry entry "
                f"disagree about the file they change: the twin changes "
                f"'{sg.sabotage_path}', the registry names '{registry_path}'. "
                f"Two controls that change different files are two controls, "
                f"and only one of them runs."
            )
        registry_argv = [
            sys.executable if arg == "{python}" else arg for arg in control.argv
        ]
        if list(sg.cmd) != registry_argv:
            errors.append(
                f"Part J: the control for '{sg.name}' and the registry entry "
                f"disagree about the command they run: the twin runs "
                f"{sg.cmd}, the registry names {registry_argv}. A control that "
                f"runs a different command proves a different gate."
            )

    # 2. Registry gates with a script that have no positive control.
    #    Controls: SCRIPT_GATES, SABOTAGE_ONLY_GATES, FIXTURE_GATES.
    #    GATE_EXEMPTIONS scripts are also considered covered (they have a reason).
    covered_scripts = set(control_script_to_name) | set(_registry.GATE_EXEMPTIONS.keys())
    cmd_types_with_scripts = (
        _registry.CmdType.PYTHON3,
        _registry.CmdType.PYTHON3_M,
        _registry.CmdType.SHELL,
    )
    for spec in gates:
        if spec.cmd_type not in cmd_types_with_scripts:
            continue
        if not spec.script:
            continue
        if str(spec.script) in covered_scripts:
            continue
        errors.append(
            f"Part J: registry gate '{spec.name}' "
            f"(script={spec.script!r}) has no positive control. "
            f"Add it to SCRIPT_GATES or document the exemption in GATE_EXEMPTIONS."
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
# 1. The name has two spellings here. `check-detekt-registrations.sh` uses a
#    hyphen; `check_adr_status.py` uses an underscore. A pattern that accepts
#    only `check-` sees most of the gate scripts and calls that the complete set
#    — the failure mode this file's own comments warn about twice ("a
#    derivation that quietly sees a third of its input is worse than no
#    derivation, because it reports a short list and calls it complete").
#    `check-skill-frontmatter.sh` used to be the hyphen example and was deleted
#    in #657, when its gate was retired; keeping it as the illustration would
#    have left a comment pointing at a file that no longer exists, which is the
#    same shape of drift as the dead command that caused this issue.
#
# 2. A shim is an invocation. `refresh-decisions-digest.sh` is a few lines and
#    ends in `exec python3 "$SCRIPT_DIR/refresh-decisions-digest.py" "$@"`. The
#    Python file is therefore reachable, and a scanner that reads only the
#    surface text calls it an orphan. So reachability follows delegation, which
#    is the opposite of what a reader expects: the *target* of an `exec` counts
#    as reached, not the shim that reached it.
#
#    All three live shims spell the base directory the same way (`$SCRIPT_DIR/`),
#    but _SHIM_DELEGATION accepts any shell variable, a bare `scripts/`, and an
#    optional `./`. That is deliberate: this pattern was first written against
#    `check-skill-frontmatter.sh`, which used `"$ROOT/scripts/…"`, and a
#    matcher built for that one spelling would silently miss every other. The
#    first version of this comment then named a file that no longer existed —
#    the same drift it exists to prevent.

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
#: Any mention of `scripts/check-*.py|.sh` inside a gate script's own text. Broader
#: than `_SHIM_DELEGATION` on purpose: gate-to-gate invocation is how one gate ends
#: up running another, and it is invisible to a scanner that only reads surfaces.
#: The path must be in *command position* — preceded by an interpreter, a shell, or
#: `./`. Bare prose in a docstring does not count, and that distinction is the whole
#: reason this is narrower than "any mention": `check-gate-liveness.py` writes
#: "check-backlog-issue-refs.py went with the removed backlog-issue-refs gate" in its
#: exemption text, and matching that would keep a retired gate reachable by accident
#: while appearing to check.
_GATE_TO_GATE_CALL = re.compile(
    r"(?:python3\s+|bash\s+|sh\s+|\./)(scripts/check[-_][\w.-]+\.(?:py|sh))"
)

_SHIM_DELEGATION = re.compile(
    r"exec\s+(?:python3|bash|sh)\s+[\"']?"
    r"(?:\$\{?[A-Za-z_][A-Za-z0-9_]*\}?/)?(?:scripts/)?"
    r"(check[-_][\w.-]+\.(?:py|sh))"
)


def gate_candidate_scripts() -> list[str]:
    """Every `scripts/check*` script that exists on disk."""
    out: list[str] = []
    for pattern in ("scripts/check*.*",):
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
    global _UNRESOLVED_SGATE_INVOCATIONS
    _UNRESOLVED_SGATE_INVOCATIONS = []
    pattern = re.compile(
        r"(?:^|[\s'\"(=|])(?:python3\s+)?(?:\./)?"
        r"(scripts/check[-_][\w.-]+\.(?:py|sh))"
    )
    backing = sgate_backing_scripts()
    for surface in _REACH_SURFACES:
        try:
            text = surface.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        # Comments are prose, and prose mentions commands. `.github/actions/
        # setup-repo-tools/action.yml` says "…sgate and its parsers…" in a comment,
        # which the invocation regex reads as a command called `and`.
        text = "\n".join(
            "" if line.lstrip().startswith("#") else line for line in text.splitlines()
        )
        for match in pattern.finditer(text):
            invoked.add(match.group(1))
        # `sgate <name>` is an invocation of whatever script that command runs.
        # Without this the sgate migration made 20+ real gates look orphaned.
        for match in _SGATE_INVOCATION.finditer(text):
            cmd = match.group(1)
            scripts = backing.get(cmd)
            if scripts is None and cmd in _module_backed_gate_commands():
                # `python3 -m traceability validate` and friends: a registered gate
                # whose implementation is a module rather than a script. It runs
                # something, backs no `scripts/check-*` file, and is not a typo — so
                # it is resolved, and saying otherwise sent every surface that runs
                # it to the "names no sgate command" report.
                continue
            if scripts is None and cmd == "run-all":
                # The driver: it runs every registered gate, and only those.
                scripts = _registered_gate_scripts()
            if scripts:
                for script in scripts:
                    invoked.add(script)
            elif cmd not in _NON_GATE_SGATE_COMMANDS:
                # Loud rather than broad. A name that resolves to nothing is either a
                # command that runs no check script (info, render-registry) or a typo,
                # and the difference matters: the old behaviour answered both by
                # marking all 32 registry scripts reachable.
                _UNRESOLVED_SGATE_INVOCATIONS.append(
                    f"{surface.relative_to(ROOT)}: sgate {cmd}"
                )

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
                for candidate in (f"scripts/{match.group(1)}",):
                    if (ROOT / candidate).is_file() and candidate not in invoked:
                        found.add(candidate)
            # A gate that runs another gate reaches it. `check-detekt-registrations.sh`
            # invokes `check-doc-dead-refs.py` by path, and that is the only thing
            # running it — so scanning surfaces alone reported it as an orphan while
            # it executed on every run. The loop is bounded by the same frontier, so
            # a gate-to-gate cycle still terminates.
            for match in _GATE_TO_GATE_CALL.finditer(body):
                candidate = match.group(1)
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
#: Sourced from the registry (GRADLE_TASK_PARITY) by _populate_derived_tables().
GRADLE_TASK_PARITY: dict[str, tuple[str, str]]

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


#: Gate-shaped scripts Part H does not report, each with the reason and the issue
#: that will remove the entry. The alternative — a gate that cannot be reached and
#: cannot be deleted without deciding something else first — is a permanent,
#: invisible hole; an entry here is a decision that is visible in review.
#:
#: `check_gate_reachability` asserts every entry still exists on disk, so this list
#: cannot accumulate scripts that were later deleted: a stale entry is an error.
#: All four are the same shape: a positive control is declared in `GATE_CONTROLS`, no
#: `GateSpec` registers them, and no surface invokes them. The control survived the
#: registration, which is why nobody noticed the gate had stopped running — Part J
#: counts controls, Part H counts reachability, and each was reading the other half
#: of the same fact.
#:
#: Whether each is deleted or re-registered is a decision, not a cleanup, so it is
#: filed (https://github.com/gazon1/sing/issues/697) rather than taken here. What is
#: taken here is that Part H now says so out loud instead of counting them reached
#: because of one unresolvable command name.
_WIRED_BY_NOBODY = (
    "has a positive control in GATE_CONTROLS, no GateSpec, and no surface that invokes "
    "it — it is not run, and never has been since its registration went. Whether to "
    "delete it or register it is tracked in "
    "https://github.com/gazon1/sing/issues/697"
)

REACHABILITY_EXCLUSIONS: dict[str, str] = {
    "scripts/check-adr-references.py": (
        "Its own unit tests pin the behaviour (GATE_EXEMPTIONS says so), and the "
        "control exists, but nothing in check.sh, the registry or any recipe runs it. "
        + _WIRED_BY_NOBODY
    ),
    "scripts/check-doc-dead-refs.py": (
        "Reachable-looking: `check-detekt-registrations.sh` names it — in a comment, "
        "explaining what the dead-reference check is for. A prose mention is not an "
        "invocation, which is why the gate-to-gate scan requires command position. "
        + _WIRED_BY_NOBODY
    ),
}


def check_reachability_exclusions_are_live() -> list[str]:
    """Every exclusion must still describe a file that exists.

    An exemption whose subject was deleted is a hole wearing a reason: Part H can
    never report that script again, and nothing is left to look at. Checked
    separately from `check_gate_reachability` so a fixture that rebinds ROOT is
    not judged against this repository's files.
    """
    errors: list[str] = []
    for excluded in REACHABILITY_EXCLUSIONS:
        if not (ROOT / excluded).is_file():
            errors.append(
                f"{excluded} is listed in REACHABILITY_EXCLUSIONS but no longer exists. "
                f"Remove the entry: the exemption list is a record of a decision still "
                f"open, not a place where deletions go to hide."
            )
    return errors


def check_gate_reachability() -> list[str]:
    errors: list[str] = []
    reachable = reachable_gate_scripts()

    for unresolved in _UNRESOLVED_SGATE_INVOCATIONS:
        errors.append(
            f"{unresolved} names no sgate command that runs a check script, so nothing "
            f"was marked reachable because of it. Previously this invocation silently "
            f"marked every script any command module names — 32 of the 33 candidates — "
            f"reachable, which is how a gate retired in #657 stayed on disk with a "
            f"shim still pointing at it. Rename the command, or delete the invocation."
        )
    for script in gate_candidate_scripts():
        if script in reachable or script in REACHABILITY_EXCLUSIONS:
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


@click.command(epilog=__doc__)
@click.option("--wiring", is_flag=True, help="Part A only")
@click.option("--can-fail", is_flag=True, help="Part B only")
@click.option("--gradle-can-fail", "gradle_can_fail", is_flag=True, help="Part C only")
@click.option("--ci-steps-blocking", "ci_steps_blocking", is_flag=True, help="Part D only")
@click.option("--parity", is_flag=True, help="Part E only")
@click.option("--registry", is_flag=True, help="Part F only")
@click.option("--registry-wiring", "registry_wiring", is_flag=True, help="Part G only")
@click.option("--reachability", is_flag=True, help="Part H only")
@click.option("--gradle-parity", "gradle_parity", is_flag=True, help="Part I only")
@click.option("--registry-cross", "registry_cross", is_flag=True, help="Part J only")
@click.pass_context
def main(ctx: click.Context,
    wiring: bool,
    can_fail: bool,
    gradle_can_fail: bool,
    ci_steps_blocking: bool,
    parity: bool,
    registry: bool,
    registry_wiring: bool,
    reachability: bool,
    gradle_parity: bool,
    registry_cross: bool,
) -> None:
    """check-gate-wiring — prove the gates exist AND that they can fail.

    Run with no options to execute every part. Use one or more --option flags
    to select specific parts. Every gate runs under a deadline
    (GATE_TIMEOUT_S / GRADLE_GATE_TIMEOUT_S); a hang reports "no verdict",
    never "failed".
    """
    only = (
        wiring
        or can_fail
        or gradle_can_fail
        or ci_steps_blocking
        or parity
        or registry
        or registry_wiring
        or reachability
        or gradle_parity
        or registry_cross
    )
    run_a = wiring or not only
    run_b = can_fail or not only
    run_c = gradle_can_fail or not only
    run_d = ci_steps_blocking or not only
    run_e = parity or not only
    run_f = registry or not only
    run_g = registry_wiring or not only
    run_h = reachability or not only
    run_i = gradle_parity or not only
    run_j = registry_cross or not only

    errors: list[str] = []

    if run_a:
        print("Part A — Gradle check tasks are wired into a gate")
        wiring_errs = check_wiring()
        tasks = CONFIGURED_DETEKT_TASKS
        for task in sorted(tasks):
            print(f"  configured: {task}  ({tasks[task]})")
        errors += wiring_errs
        if not wiring_errs:
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
        errors += check_reachability_exclusions_are_live()
        if not orphans:
            print("  ok  no gate-shaped script is left unreachable")

    if run_i:
        print("\nPart I — every Gradle task a surface names is named by both")
        errors += check_gradle_task_parity()
        if not check_gradle_task_parity():
            print("  ok  check.sh and ci.yml name the same Gradle tasks")

    if run_j:
        print("\nPart J — SCRIPT_GATES sabotage controls are consistent with _registry.GATES")
        cross_errors = check_script_gates_vs_registry()
        for err in cross_errors:
            print(f"  {err}")
        errors += cross_errors
        if not cross_errors:
            print("  ok  all SCRIPT_GATES entries are in the registry, "
                  "all registry gates with scripts have controls")

    if errors:
        click.echo("")
        for err in errors:
            click.echo(f"ERROR: {err}")
        click.echo("")
        click.echo("A gate that cannot fail, or a check task nobody invokes, is worse than")
        click.echo("no gate: it converts 'not checked' into 'checked and green'.")
        raise ctx.exit(1)

    click.echo("\ncheck-gate-wiring: OK")


# Applied at the bottom: `_load_registry` is defined further down, and the
# builders above it must exist before a control can be built from one.
SCRIPT_GATES = _prefer_registry_controls(SCRIPT_GATES)
SABOTAGE_ONLY_GATES = _prefer_registry_controls(SABOTAGE_ONLY_GATES)


if __name__ == "__main__":
    raise SystemExit(run(main))
