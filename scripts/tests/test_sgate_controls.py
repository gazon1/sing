#!/usr/bin/env python3
"""Unit tests for sgate.controls — the declarative positive-control data.

The regression these tests exist for is an *import-time* crash, so the first two
tests import the registry itself. `Edit.__post_init__` refused `repl=''` because it
tested `not self.repl`, which cannot tell "the declaration forgot a replacement"
apart from "delete the match". A control that declared `repl=''` (a deletion) made
importing `_registry.py` raise `ValueError` and took down every gate: `sgate run-all`
died before dispatching anything, and CI's `static` job went red on the import. See
ADR 2026-10-19-sgate-regex-repl-empty-is-a-deletion.md.

Importing the registry is the control. A unit test that only built an `Edit` in
isolation would have stayed green while the module that assembles all of them
could not be loaded at all — which is the shape of defect the file's own docstring
warns about: a gate that dies instead of reporting is indistinguishable from a
gate that passes.
"""
from __future__ import annotations

import pathlib
import re
import sys
import unittest


# This script must resolve `sgate` against *this* checkout: an editable
# install of a sibling worktree is a different tree under the same name.
_SCRIPTS_SRC = str(pathlib.Path(__file__).resolve().parent.parent / "src")
if _SCRIPTS_SRC not in sys.path:
    sys.path.insert(0, _SCRIPTS_SRC)

from sgate.commands._registry import GATE_CONTROLS, GATES
from sgate.controls import Control, Edit

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent


def _step_containing(text: str, needle: str) -> str | None:
    """The name of the step whose text holds the FIRST occurrence of *needle*.

    The first occurrence is the one that matters: the registry declares
    `count=1`, and `_replace` replaces the first match — so a control lands
    where its literal appears FIRST, not where its author meant it to.

    The needle often IS the step's header (`- name: …` plus its `run:` line),
    so the step is the one whose header starts at or before the occurrence —
    not the one before it.
    """
    index = text.find(needle)
    if index < 0:
        return None
    headers = [
        (match.start(), match.group(1))
        for match in re.finditer(r"^\s*- name: (.+?)\s*$", text, re.MULTILINE)
        if match.start() <= index
    ]
    return headers[-1][1] if headers else None


def _job_blocks(text: str) -> dict[str, tuple[int, int]]:
    """The job blocks of a workflow: job id -> (first line, last line).

    The jobs are the blocks at indent 2 under `jobs:`. A job-level attribute
    — a `timeout-minutes:` above the steps — belongs to a job and to no step,
    and this is what says which job that is.
    """
    lines = text.splitlines()
    blocks: dict[str, tuple[int, int]] = {}
    current: str | None = None
    start = 0
    inside = False
    for number, line in enumerate(lines, start=1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        indent = len(line) - len(line.lstrip())
        if not inside:
            if indent == 0 and stripped == "jobs:":
                inside = True
            continue
        if indent == 2:
            match = re.match(r"^([A-Za-z0-9_.\-]+):", stripped)
            if match:
                if current is not None:
                    blocks[current] = (start, number - 1)
                current = match.group(1)
                start = number
    if current is not None:
        blocks[current] = (start, len(lines))
    return blocks


def _steps_line(lines: list[str], start: int, end: int) -> int | None:
    """The line of a job block's `steps:` key."""
    for number in range(start, min(end, len(lines)) + 1):
        if lines[number - 1].strip() == "steps:":
            return number
    return None


def _step_names(text: str, job_id: str) -> list[str]:
    """The names of the steps of a job, in the order the workflow declares them."""
    lines = text.splitlines()
    block = _job_blocks(text).get(job_id)
    if block is None:
        return []
    start, end = block
    return [
        match.group(1)
        for number in range(start, min(end, len(lines)) + 1)
        if (match := re.match(r"^      - name: (.+?)\s*$", lines[number - 1]))
    ]


class TestRegistryImports(unittest.TestCase):
    """Assembling every control must succeed at import time."""

    def test_control_registry_imports(self):
        """`_registry.py` is importable and the control dict is populated.

        This is the assertion the failing CI run could not make: `import sgate`
        raised before a single gate was dispatched.
        """
        self.assertIsInstance(GATE_CONTROLS, dict)
        self.assertGreater(
            len(GATE_CONTROLS), 0, "Registry assembled zero controls"
        )

    def test_gate_registry_imports(self):
        """The gate list is a non-empty tuple of specs."""
        self.assertIsInstance(GATES, tuple)
        self.assertGreater(len(GATES), 20, "Expected 20+ gates in the registry")

    def test_every_control_declares_its_input_change(self):
        """Each control declares the input change its kind is supposed to declare.

        A `sabotage` with no edit would be applied as a no-op, which is precisely
        the "matched nothing and reported a pass" failure the design exists to
        prevent. `fixture` controls seed a corpus instead of editing one, and
        `exempt` is the one kind that deliberately carries neither — so the
        assertion is per kind rather than "every control has an edit".
        """
        for name, control in GATE_CONTROLS.items():
            self.assertIsInstance(control, Control, f"{name} is not a Control")
            if control.kind in ("sabotage", "gradle"):
                self.assertIsInstance(
                    control.edit,
                    Edit,
                    f"{control.kind} control {name!r} declares no Edit",
                )
            elif control.kind == "fixture":
                self.assertTrue(
                    control.fixture, f"fixture control {name!r} names no fixture"
                )
            else:  # exempt — documented as carrying no input change
                self.assertIsNone(
                    control.edit,
                    f"exempt control {name!r} should carry no edit to apply",
                )


class TestTheControlThatMustBeAbleToFail(unittest.TestCase):
    """The declaration form that once took `import sgate` down, and what replaced it.

    The `log-interpolation` control used to *delete* an allowlist line — a regex
    edit with an empty replacement — and that declaration was the one that made
    `import sgate` raise: `Edit` validated the replacement with truthiness, so
    `repl=''` was refused as a missing value when it is a deletion. The fix
    distinguished unset from empty (ADR
    `2026-10-19-sgate-regex-repl-empty-is-a-deletion.md`).

    The gate itself was removed on 2026-10-20, but the fix it forced is not
    optional: `Edit` still distinguishes "the declaration forgot" from "replace
    with nothing", and the deletion capability is still pinned below. What
    remains general is the property that was missing: **a control has to be able
    to fail the gate it claims to prove.**
    """

    def test_a_registry_control_still_uses_a_regex_edit(self):
        """A regex edit with a *deletion* is a legal declaration.

        The named control no longer needs to be the one that exercises it, but
        the declaration form must stay legal in the registry — that is the whole
        fix from the import crash.
        """
        regex_controls = [
            (name, control)
            for name, control in GATE_CONTROLS.items()
            if control.edit is not None and control.edit.kind == "regex"
        ]
        self.assertTrue(
            regex_controls,
            "no registry control uses a regex edit any more, so the regex path "
            "is no longer exercised by a live control",
        )
        for name, control in regex_controls:
            self.assertIsNotNone(
                control.edit.repl,
                f"control {name!r} declares a regex edit with no replacement",
            )

    def test_every_control_still_hits_the_line_it_claims_to_prove(self):
        """Every control's sabotage lands in the step its anchor names.

        A control has two halves: the literal text it replaces, and the
        `tracked_by` anchor that says which step the control is about. The
        text resolves by the FIRST occurrence — the registry declares
        `count=1` — and the anchor resolves by step name.

        When the workflow's job graph moves, the two halves can come to point
        at different steps: the control then lands in one step while claiming
        another, and it passes while proving nothing. Green and vacuous is the
        worst state a control can be in.
        """
        for name, control in GATE_CONTROLS.items():
            anchor = control.tracked_by
            if not anchor or "#" not in anchor:
                continue
            path, _, step_name = anchor.partition("#")
            target = ROOT / path
            if target.suffix not in (".yml", ".yaml"):
                continue

            text = target.read_text(encoding="utf-8")
            self.assertIn(
                f"- name: {step_name}",
                text,
                f"the control {name!r} anchors at a step that is not in {path}",
            )

            edit = control.edit
            if edit is None or edit.kind != "replace":
                continue
            if control.path != path:
                # The control edits another file; its anchor says which STEP
                # proves the gate, and the step has to exist — nothing more to
                # compare.
                continue

            landed = _step_containing(text, edit.old)
            self.assertIsNotNone(
                landed,
                f"the control {name!r} replaces text that is no longer in {path}",
            )
            if landed == step_name:
                continue

            # The landed step is not the anchored one. Either the sabotage
            # landed in ANOTHER step — the defect this test exists for — or it
            # landed in a job-level attribute, which belongs to a job and to no
            # step: the coverage job's `timeout-minutes:` sits above its
            # steps. In the second case the anchor names a step OF THAT JOB,
            # and the job is what the control is about.
            index = text.find(edit.old)
            line = text.count("\n", 0, index) + 1
            lines = text.splitlines()
            blocks = _job_blocks(text)
            job_id = next(
                (
                    job
                    for job, (start, end) in blocks.items()
                    if start <= line <= end
                ),
                None,
            )
            self.assertIsNotNone(
                job_id,
                f"the control {name!r} edits a line that is in no job of {path}",
            )
            steps_line = _steps_line(lines, *blocks[job_id])
            if steps_line is not None and line < steps_line:
                self.assertIn(
                    step_name,
                    _step_names(text, job_id),
                    f"the control {name!r} lands in the job {job_id!r} and claims "
                    f"to prove {step_name!r}, which is a step of another job",
                )
                continue
            self.assertEqual(
                step_name,
                landed,
                f"the control {name!r} lands in the step {landed!r} and claims "
                f"to prove {step_name!r}",
            )


class TestRegexRepl(unittest.TestCase):
    """`repl` distinguishes unset (`None`) from a deliberate deletion (`""`)."""

    def test_empty_replacement_is_a_deletion_and_is_legal(self):
        """`repl=''` with a pattern constructs: that edit removes the match."""
        edit = Edit(
            kind="regex", pattern=r"^targets: \[android\]$", repl="", flags="MULTILINE"
        )
        self.assertEqual(edit.repl, "")

    def test_omitted_replacement_is_rejected(self):
        """A regex edit with no `repl` at all is the loudness the design wants.

        This is the case the old `not self.repl` check was written for, and it must
        keep failing: nothing here says which file to read.
        """
        with self.assertRaises(ValueError) as ctx:
            Edit(kind="regex", pattern="anything")
        self.assertIn("repl", str(ctx.exception))

    def test_pattern_is_still_required(self):
        """An empty `pattern` is the "finds nothing" case and stays refused."""
        with self.assertRaises(ValueError) as ctx:
            Edit(kind="regex", repl="")
        self.assertIn("pattern", str(ctx.exception))

    def test_empty_replacement_actually_deletes(self):
        """`re.subn` with `repl=''` removes the match and reports the hit.

        `$` matches before the trailing newline, so the line's text goes and its
        newline stays — the assertion is on the text being gone, not on the file
        becoming empty. The applier's own `assert n >= 1` is what makes the edit
        loud when the pattern has moved on.
        """
        new, n = re.subn(
            r"^targets: \[android\]$", "", "targets: [android]\n", count=1, flags=re.M
        )
        self.assertEqual(n, 1, "deletion matched nothing — it would prove nothing")
        self.assertEqual(new.strip(), "", "the line's text survived the deletion")


class TestUnchangedValidation(unittest.TestCase):
    """The neighbouring checks must keep behaving as before."""

    def test_replace_requires_old(self):
        with self.assertRaises(ValueError):
            Edit(kind="replace", new="x")

    def test_age_dir_requires_positive_days(self):
        with self.assertRaises(ValueError):
            Edit(kind="age_dir", days=0)

    def test_unknown_flag_is_rejected_at_declaration_time(self):
        with self.assertRaises(ValueError):
            Edit(kind="regex", pattern="x", repl="y", flags="NOT_A_FLAG")

    def test_non_regex_edits_default_repl_to_none_without_raising(self):
        """`repl` is only meaningful for `regex`; other kinds must not trip it.

        This is the second half of the contract: the check is kind-scoped, so a
        `replace` edit never needs to spell out a replacement.
        """
        edit = Edit(kind="replace", old="a", new="b")
        self.assertIsNone(edit.repl)


if __name__ == "__main__":
    unittest.main()