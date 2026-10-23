"""sgate.gate_controls — the control machinery: appliers, gate records, tables.

Data and the appliers that execute it, in one place, so a control is declared
once. A control used to be declared twice — as data in `_registry.py` and as a
hand-written twin in `check-gate-wiring.py` — and the two copies drifted: the
registry's `rule-intent` control named a `detekt.yml` pattern that had not
been in the file since the rules were reorganised, and the copy that rotted was
never the one that ran, so nothing said so.

The registry is now the only place a control is declared. This module holds
the machinery that executes a declaration: the appliers for each edit kind,
the records the harness keeps, and the tables the harness runs.

Nothing here imports `sgate`: the module is loaded by `check-gate-wiring.py`,
which owns the path discipline that decides which checkout's registry is read.
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

#: The repository root, derived from this file's own location. This module
#: imports nothing from the project on purpose — it is loaded by
#: `check-gate-wiring.py`, which owns the path discipline that decides which
#: checkout's registry is read — so the root has to come from here.
_REPO_ROOT = Path(__file__).resolve().parents[3]

#: A sabotage: a callable that changes the tree so a gate must fail.
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
    # The file the control changes, and the change itself. Both come from the
    # registry: a control is declared once, in `_registry.py`, and the harness
    # fills them from there. The defaults are empty because the twin does not
    # know them — a twin that carried its own copy is how the two drifted.
    sabotage_path: str = ""
    sabotage: Control | None = None
    why: str = ""
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
        name="text-field-transformations-table",
        cmd=[sys.executable, "scripts/check-text-field-transformations-table.py"],
        why=(
            "a transformation's rule is a promise about what the field accepts, "
            "and a table that is wrong about it sends an agent to write a field "
            "that takes three digits where the code takes two"
        ),
    ),
    ScriptGate(
        name="platform-reference-ports",
        cmd=[sys.executable, "scripts/check-platform-reference-ports.py"],
        why=(
            "the platform reference is the map of the seam, and a map that names "
            "a port the code deleted sends a reader to a type that is not there"
        ),
    ),
    ScriptGate(
        name="profile-reset-inventory",
        cmd=[sys.executable, "scripts/ci/check-profile-reset-inventory.py"],
        why=(
            "a table added to the Room schema is not a row in a document, so the "
            "document that says the profile reset reaches the whole profile stays "
            "true in the prose while the code has stopped reaching it — and the next "
            "E2E flow finds the leftover row and blames the test"
        ),
    ),
    ScriptGate(
        name="detekt-registrations",
        cmd=["./scripts/check-detekt-registrations.sh"],
        why="a RuleSetId with no detekt.yml block never loads, so the rule never runs",
    ),
    ScriptGate(
        name="baseline-ratchet",
        cmd=[sys.executable, "scripts/check-baseline-ratchet.py"],
        why="a baseline that can grow silently stops being a ratchet and becomes a sink",
    ),
    # The app coverage floor, declared where Kover reads it.
    #
    # The floors were declared in the root build script for months, in a
    # `plugins.withId("org.jetbrains.kotlinx.kover")` block that never executed
    # — probed with a `println`, the line never appeared. The root project has
    # no Kover extension of its own to configure, so the floors it declared
    # were evaluated by no task. The CI step that reads them was green no
    # matter what the coverage was.
    #
    # The sabotage is in the registry (`_registry.GATE_CONTROLS`), and this
    # entry is its twin: the harness fills the edit from there, and Part J
    # fails when the two disagree about the file or the command.
    # The coverage report measures every module that is in it.
    #
    # The list of test tasks that feed the aggregated report is written by
    # hand, in the root build script. A module added to the build is not added
    # to that list, so its tests never run in the coverage job.
    #
    # The sabotage is in the registry (`_registry.GATE_CONTROLS`), and this
    # entry is its twin: the harness fills the edit from there, and Part J
    # fails when the two disagree about the file or the command.
    ScriptGate(
        name="coverage-task-list",
        cmd=[sys.executable, "scripts/check-coverage-task-list.py", "--quiet"],
        sabotage_path="build.gradle.kts",
        sabotage=None,  # filled from _registry.GATE_CONTROLS by _prefer_registry_controls
        why=(
            "a module whose classes are in the coverage denominator but whose "
            "tests never run is scored as 0% covered, and the number reads "
            "like a coverage problem rather than a measurement one"
        ),
    ),
    ScriptGate(
        name="coverage-floor-declaration",
        cmd=[sys.executable, "scripts/check-coverage-floor-declaration.py", "--quiet"],
        sabotage_path="build.gradle.kts",
        sabotage=None,  # filled from _registry.GATE_CONTROLS by _prefer_registry_controls
        why=(
            "a floor that is declared where nothing reads it is a floor nobody "
            "enforces, and the step that reports it stays green"
        ),
    ),
    ScriptGate(
        name="issue-registry",
        cmd=[sys.executable, "scripts/check-issue-registry.py", "--quiet"],
        why=(
            "a record that points at another change is a record of something else, and it "
            "looks like coverage: the next session reads the record, believes the change "
            "is known, and never learns what the change actually was. Four changes reached "
            "main with no entry at all, which is the same defect one step further from the "
            "surface"
        ),
    ),
    ScriptGate(
        name="agents-pointers",
        cmd=[sys.executable, "scripts/check-agents-pointers.py", "--quiet"],
        why=(
            "a pointer that does not resolve sends the next session looking for a file that "
            "is not there: the note that was written to remove the stop becomes the stop. "
            "The pointer that started it named the harness file for exemptions that live "
            "in the conventions test"
        ),
    ),
    ScriptGate(
        name="review-verdicts",
        cmd=[sys.executable, "scripts/check-review-verdicts.py", "--quiet"],
        why=(
            "a review that leaves no trace is a promise with nothing behind it: the next "
            "session cannot tell whether the change was reviewed, what was found, or "
            "whether the findings were fixed. The review of the issue-registry gate found "
            "four real defects, and the tree said nothing about it"
        ),
    ),
    ScriptGate(
        name="test-tags-catalogue",
        cmd=[sys.executable, "scripts/check-test-tags-catalog.py", "--check"],
        # The sabotage is declared in the registry (see _registry.GATE_CONTROLS);
        # this entry exists so Part B proves the gate can actually fail.
        #
        # Without this twin the gate had a control in the registry and nothing
        # that ran: Part J asks "does every registered gate have a positive
        # control?", and it looked for the answer among the twins. A control
        # that no twin names is a control nobody executes — the gate is
        # registered, the probe is written, and the two never meet.
        why=(
            "the catalogue is the promise about which ids a flow may address, and a tag "
            "whose value was renamed in the code leaves the promise describing the old "
            "world: the flow keeps using an id that no screen answers to, and the failure "
            "lands on a device rather than in a gate"
        ),
    ),
    ScriptGate(
        name="hooks-path",
        cmd=[sys.executable, "scripts/check-hooks-path.py", "--quiet"],
        # Untrack *every* hook, not one. The gate asks "does the configured hooks
        # directory contain tracked files?", so removing a single hook while three
        # others stay tracked is a state the gate is right to accept — and a control
        # that expects it to fail proves nothing. The first version of this control
        # emptied one file, which the gate correctly ignored.
        # Set core.hooksPath as part of the sabotage: on a CI runner the config
        # is unset, so the gate takes its "unset → advisory, exit 0" branch and
        # passes no matter what — the control would prove nothing (and did, on
        # the first CI run that reached it, 2026-10-11). Setting it to .githooks
        # is what a correctly configured machine already has; the restore leaves
        # it there, which is the value the gate itself recommends.
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
        why="a persisted setting rendered in Settings and read by no feature is a " +
            "control that looks like it works; every baseline line is a claim that " +
            "needs its own justification",
    ),
    ScriptGate(
        name="sync-allowlist-regenerated",
        cmd=[sys.executable, "scripts/check-sync-allowlist-regenerated.py"],
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
        why="a GPL upstream named in production code with no registry row is a licence claim nobody is tracking",
    ),
    ScriptGate(
        name="pro-licence-boundary",
        cmd=[sys.executable, "scripts/check-pro-licence-boundary.py"],
        # The vendor dependency, reintroduced into a free build file. That is the exact
        # regression this gate exists to catch: `ru.ok.tracer` was a direct `implementation`
        # dependency of `:shared` and `:androidApp` until 2026-10-05, so it is a change
        # that has already happened once and can happen again by copy-paste.
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
        why="a scenario claiming a target nothing verifies is the one hole count that grows without anyone reading the diff",
    ),
    ScriptGate(
        name="workflow-exit-status",
        cmd=[sys.executable, "scripts/check-workflow-exit-status.py"],
        # The defect this gate exists for, restored exactly: four CI steps piped their
        # output into `tee`/`tail` and inherited that command's exit status, so a failing
        # `./gradlew` — 109 detekt findings on main — reported success. The sabotage puts
        # the pipe back on the step that runs every test and detekt task.
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
        why=(
            "the `env` context holds the variables a workflow defines, not the "
            "runner's environment, so `${{ env.ANDROID_HOME }}` expands to the empty "
            "string and a path built from it names nothing — silently, with no warning"
        ),
    ),
    ScriptGate(
        name="gate-registry-is-current",
        cmd=[sys.executable, "-m", "sgate", "render-registry", "--check"],
        # The sabotage is declared in the registry (see _registry.GATE_CONTROLS);
        # this entry exists so Part B proves the gate can actually fail.
        #
        # The probe edits the *generated* table, which is the only thing this gate
        # reads. A gate that only compared the file against itself would pass on
        # any file; the sabotage is what makes the comparison mean something.
        why=(
            "a catalogue of the gates that no longer matches the registry is a "
            "catalogue that answers questions about a gate set nobody runs — and the "
            "reader has no way to tell, because the file looks exactly as authoritative "
            "as the registry it drifted from"
        ),
    ),
    ScriptGate(
        name="gate-json-contract",
        cmd=[sys.executable, "scripts/check-gate-json-contract.py"],
        # The sabotage is declared in the registry (see _registry.GATE_CONTROLS);
        # this entry exists so Part B proves the gate can actually fail.
        #
        # The probe puts a line of prose in front of one gate's JSON document.
        # That is the smallest possible version of the failure: the verdict is
        # still there, and a consumer that reads the document gets a parse error
        # instead of a verdict.
        why=(
            "a gate whose stdout carries prose in front of the document is a gate a "
            "consumer cannot read: the verdict is there, and the parser that wanted it "
            "gets a stack trace instead. Nothing caught it, because nothing read the "
            "document"
        ),
    ),
    ScriptGate(
        name="maestro-flow-catalogue",
        cmd=[sys.executable, "scripts/check-maestro-flow-catalogue.py"],
        # The sabotage is declared in the registry (see _registry.GATE_CONTROLS);
        # this entry exists so Part B proves the gate can actually fail.
        why=(
            "a flow removed from the tree without a word leaves the suite smaller "
            "and the run green — the run reports the flows that are left, so the "
            "one that is gone is only visible in a diff nobody reads"
        ),
    ),
    ScriptGate(
        name="maestro-keyboard-commands",
        cmd=[sys.executable, "scripts/check-maestro-keyboard-commands.py"],
        # The sabotage is declared in the registry (see _registry.GATE_CONTROLS);
        # this entry exists so Part B proves the gate can actually fail.
        why=(
            "a `hideKeyboard` with nothing to hide is a BACK keypress: the editors "
            "are dialogs, so BACK dismisses them and the flow fails on an element "
            "the editor was holding — a red run that blames the app for a command "
            "the flow itself got wrong"
        ),
    ),
    ScriptGate(
        name="maestro-screen-geometry",
        cmd=[sys.executable, "scripts/check-maestro-screen-geometry.py"],
        # The sabotage is declared in the registry (see _registry.GATE_CONTROLS);
        # this entry exists so Part B proves the gate can actually fail.
        why=(
            "a flow that names a pixel is a claim about one display - the swipe "
            "only happens on a screen at least that tall, so the flow is green on "
            "the CI emulator and meaningless on a phone"
        ),
    ),
    ScriptGate(
        name="ui-wait-budgets",
        cmd=[sys.executable, "scripts/check-ui-wait-budgets.py"],
        # The sabotage is declared in the registry (see _registry.GATE_CONTROLS);
        # this entry exists so Part B proves the gate can actually fail.
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
        # The value is matched by regex and the match is asserted, never replaced by
        # a literal. This entry had `SCHEMA_VERSION = 37` hardcoded; the schema moved
        # to 38, `replace` silently found nothing, the gate was handed an unmodified
        # file and correctly passed — and the control reported "it cannot detect
        # this", which reads like a broken gate rather than a broken control. A
        # control that quietly stops sabotaging is worse than no control, because it
        # is reported as a passing one. The `assert` makes the no-op loud instead.
        why="an entity change without a version bump passes every test and crashes every existing install on upgrade",
    ),
    ScriptGate(
        name="dependency-usage",
        cmd=[sys.executable, "scripts/check-dependency-usage.py", "--quiet"],
        # The gate runs against the repository, so the control can use the same
        # invocation and the same target every time — no fabricated resolution,
        # no Gradle, and no second input format to keep honest. Dropping one
        # allowlist line turns that dependency into a finding the gate must
        # report, which is exactly the moment the list stops matching reality.
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
        why="a new dead reference must fail until baselined",
    ),
    # The two cluster gates. Their members keep their own entries below, each
    # with its own control — a control proves that a check can fail, and one
    # control for a cluster of six would prove only that something in it can
    # fail. These two entries prove that the aggregate reports a member's
    # failure rather than swallowing it.
    ScriptGate(
        name="ci-workflow-shape",
        cmd=[sys.executable, "scripts/check-ci-workflow-shape.py"],
        why="a cluster that swallowed a member's failure would be one green row over three red checks",
    ),
    ScriptGate(
        name="detekt-governance",
        cmd=[sys.executable, "scripts/check-detekt-governance.py"],
        why="a cluster that swallowed a member's failure would be one green row over six red checks",
    ),
    # gate-registries: the gate that compares the two registries against each
    # other. Its input is the shell registry, so the sabotage adds a gate line
    # the Python registry does not know — the exact shape of drift the gate
    # exists to report. A control that broke the Python side instead would
    # prove the same property, but this one breaks the side the gate reads.
    ScriptGate(
        name="gate-registries",
        cmd=[sys.executable, "scripts/check-gate-registries.py", "--strict"],
        why="a gate line the Python registry does not know is drift the gate must report",
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
        why="a documentation link that points at a file which is not there is a dead reference the reader follows and finds nothing",
    ),
    # ai-write-tools: the registry declared this control on 2026-10-22, and the
    # twin that runs it was never written — so the gate was registered, the
    # control was declared, and nothing ever broke the file the gate reads.
    # The registry holds the edit (it removes the one entry from
    # `config/docs/ai-direct-writes.txt`), so the twin is the name and the
    # command, and the harness fills in the rest.
    ScriptGate(
        name="ai-write-tools",
        cmd=[sys.executable, "scripts/check-ai-write-tools.py"],
        why="a tool that writes a user's data without saying so is a change the user never saw",
    ),
    # backlog-issue-refs: removed — the gate and its script are gone, and the
    # snapshot it policed (94 OPEN issues, none with a Backlog field) was the
    # failure state it detected, so no control could tell "clean run" from
    # "sabotaged run": both exited 1. See the 2026-10-19 ADR on the three
    # removed gates.
    #
    # The two gates below were added on 2026-10-22, and both were written
    # because a commit of that day made something false and nothing said so.
    #
    # di-fixture-sync: a ViewModel that takes a route and a fixture table that
    # hands it a task id is a Koin graph that cannot be built. The probe puts
    # the wrong type back into the table — the drift that was committed — and
    # the gate has to name the mismatch.
    ScriptGate(
        name="di-fixture-sync",
        cmd=[sys.executable, "scripts/check-di-fixture-sync.py", "--check"],
        why="a definition and a fixture that disagree about their type crash the graph on the first screen that uses it, and the only test that says so is an hour away",
    ),
    # navigation-doc-sync: the navigation doc is a pointer — it is what an agent
    # reads to learn what opens what. The probe restores the row that went
    # false on 2026-10-22, so the gate has to catch a claim the code does not
    # support.
    ScriptGate(
        name="navigation-doc-sync",
        cmd=[sys.executable, "scripts/check-navigation-doc-sync.py", "--check"],
        why="a row that names a type the code does not name sends every agent that follows the pointer the wrong way",
    ),
        # a link format that lives in two places is a link format that drifts: the producer writes the old format and the matcher reads the new one
        ScriptGate(
            name="link-grammar",
            cmd=[sys.executable, "scripts/check-link-grammar.py", "--check"],
            why=(
                'a link format that lives in two places is a link format that drifts: the producer writes the old format and the matcher reads the new one'
            ),
        ),
        # a catalogue that lost a tool is a catalogue that sends a reader to a tool that is not there
        ScriptGate(
            name="tools-catalog",
            cmd=[sys.executable, "scripts/check-tools-catalog.py", "--check"],
            why=(
                'a catalogue that lost a tool is a catalogue that sends a reader to a tool that is not there'
            ),
        ),
]

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

def _setup_gate_tooling_origin(root: Path) -> None:
    """A foreign `sgate` package within the gate's reach.

    The gate asks where the importable `sgate` comes from, and a script's own
    directory is the first entry on `sys.path` — so the fixture is a copy of
    the gate standing next to a package named `sgate`. The copy asks the
    question with the foreign package in reach, and the answer has to be
    FOREIGN: a gate run whose CLI serves another checkout measures that
    other tree, and it reports about it as if it were this one.

    The copy is what makes this a fixture rather than a sabotage: the input is
    the interpreter's import path, not a committed file, so there is nothing in
    the tree to mutate.
    """
    foreign = root / "foreign"
    (foreign / "sgate").mkdir(parents=True)
    (foreign / "sgate" / "__init__.py").write_text("", encoding="utf-8")
    shutil.copy2(
        _REPO_ROOT / "scripts" / "check-gate-tooling-origin.py",
        foreign / "check-gate-tooling-origin.py",
    )

def _setup_slow_tests_ran(root: Path) -> None:
    """A `slow` class in the tree, and a report that does not mention it.

    The gate reads the JUnit report of the full run and asks whether every
    `@Tag("slow")` class is in it. This fixture writes the class and leaves it
    out of the report — the state a default local run produces, where the
    slow classes never execute — so the gate has to report it and exit
    non-zero. A gate that only ever sees a report that lists everything proves
    nothing about the case that matters.
    """
    source = root / "shared" / "src" / "commonTest" / "kotlin"
    source.mkdir(parents=True, exist_ok=True)
    (source / "SlowTest.kt").write_text(
        "package com.singularity.todo\n\n"
        "import org.junit.jupiter.api.Tag\n\n"
        '@Tag("slow")\nclass SlowTest {\n    @Test\n    fun it_runs() = Unit\n}\n',
        encoding="utf-8",
    )
    results = root / "shared" / "build" / "test-results" / "jvmTest"
    results.mkdir(parents=True, exist_ok=True)


def _setup_maestro_results(root: Path) -> None:
    """A Maestro run that failed a flow, and a flow that never reported at all.

    The input of this gate does not exist in a clean checkout — the JUnit XML
    is written by a run on an emulator — so the control synthesises it. The
    fixture holds both ways the measurement can lie: a flow that failed while
    the floor allows no failure, and a flow the run produced no report for.

    The registry and the environment come with it, because the gate reads both
    now: a control that tripped the gate for a reason the gate did not mean to
    check would prove nothing about the rule it claims to prove. The cause the
    fixture produces is *registered*, so the fixture fails for the reason it
    says it fails — the floor — and not because the registry was missing.
    """
    flows = [
        "Maestro/flows/smoke/01-launch-today.yaml",
        "Maestro/flows/tasks/create-task.yaml",
    ]
    results = root / "results"
    results.mkdir(parents=True, exist_ok=True)
    (root / "catalogue.txt").write_text("\n".join(flows) + "\n", encoding="utf-8")
    (root / "floor.txt").write_text("full 0\n", encoding="utf-8")
    (root / "debt.yaml").write_text(
        "environment:\n"
        "  kind: emulator\n"
        "  api: 36\n"
        "causes:\n"
        "- key: 'No visible element found: id: attachments_add_file_row'\n"
        "  issue: 818\n"
        "  owner: product\n"
        "  status: accepted\n"
        "  flows: 5\n",
        encoding="utf-8",
    )
    (results / "environment.json").write_text(
        '{"kind": "emulator", "api": 36, "device": "sdk_gphone64_x86_64"}\n',
        encoding="utf-8",
    )
    suite = (
        '<testsuite name="Test Suite" tests="1" failures="{f}">'
        '<testcase classname="{n}" name="{n}" file="{flow}" status="{s}">{b}</testcase>'
        "</testsuite>"
    )
    # One flow failed ...
    (results / "01-launch-today.xml").write_text(
        suite.format(
            n="01-launch-today",
            flow=flows[0],
            f=1,
            s="ERROR",
            b="<failure>No visible element found: id: attachments_add_file_row</failure>",
        ),
        encoding="utf-8",
    )
    # ... and the second flow produced no report at all.

FIXTURE_GATES = [
    FixtureGate(
        name="flaky-tests",
        cmd=[sys.executable, "scripts/check-flaky-tests.py", "--current", "{tmp}/cur", "--previous", "{tmp}/prev"],
        setup=_setup_flaky_tests,
        why="a test that passed yesterday and fails today must be reported until it is acknowledged",
        needs_clean_run=False,
    ),
    FixtureGate(
        name="measure-flake-rate",        cmd=[
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
    FixtureGate(
        name="slow-tests-ran",
        cmd=[sys.executable, "scripts/check-slow-tests-ran.py", "--root", "{tmp}"],
        setup=_setup_slow_tests_ran,
        why=(
            "a test the local loop never runs is a test whose breakage nobody local ever "
            "sees: McpServerEndToEndTest was tagged slow, sat broken on main since the read "
            "surface changed the shape of its answer, and was found by CI rather than by "
            "anyone holding the tree"
        ),
        needs_clean_run=False,
    ),
    FixtureGate(
        name="maestro-results",
        cmd=[
            sys.executable,
            "scripts/ci/check-maestro-results.py",
            "--scope", "full",
            "--results-dir", "{tmp}/results",
            "--catalogue", "{tmp}/catalogue.txt",
            "--floor", "{tmp}/floor.txt",
        ],
        setup=_setup_maestro_results,
        why=(
            "a Maestro run that failed a flow the floor does not allow, and a flow "
            "the run produced no report for — the two ways a run can quietly stop "
            "being the run the repository promised"
        ),
        needs_clean_run=False,
    ),
    # The gate the origin check needs: it was added in 2026-10-23 (#981) with
    # no positive control, and every checkout that inherited it inherited the
    # failure too — a gate nobody has tried to break is a gate nobody knows can
    # fail, and this one cannot fail on anything in the tree.
    FixtureGate(
        name="gate-tooling-origin",
        cmd=[sys.executable, "{tmp}/foreign/check-gate-tooling-origin.py"],
        setup=_setup_gate_tooling_origin,
        why=(
            "a gate run whose CLI serves another checkout measures that other tree — "
            "it reads that checkout's registry and reports about code this one does "
            "not contain, and the run looks green while saying nothing about the "
            "tree in front of it"
        ),
        needs_clean_run=False,
    ),
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
    if kind == "strip_suppression_reason":
        # The comment block under an `@file:Suppress` is removed, and nothing
        # is substituted: the gate looks for a justification that is no longer
        # there. There is no text to match, so there is nothing to parameterise.
        return _strip_suppression_reason
    if kind == "hooks_path":
        # Not a content edit: it moves git state (`core.hooksPath`, and the
        # hooks leave the index), and the twin carries the restore that puts
        # them back. The registry says what the sabotage is; the twin says how
        # it is undone.
        return _hooks_path_sabotage
    raise ValueError(
        f"registry control edit kind {kind!r} has no builder in this file; the "
        f"control for {getattr(edit, 'path', '?')} would be silently dropped"
    )

#: The tables, in the order the harness runs them: the script gates first, then
#: the fixtures. `check-gate-wiring.py` fills the sabotage from the registry.
