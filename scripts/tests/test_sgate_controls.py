#!/usr/bin/env python3
"""Unit tests for the controls registry — the sabotage declarations themselves.

`scripts/src/sgate/commands/_registry.py` declares one `Control` per gate that
proves the gate can fail. Each declaration is validated by `__post_init__` at
*declaration* time, which is the right place — a typo should not survive to the
moment someone runs a control against a tree. But it leaves one gap: when the
guard fires, it names the field, not the control.

That gap cost a CI run. The `log-interpolation` control declared
`Edit(kind='regex', pattern=..., repl='')`, and `Edit.__post_init__` rejects a
falsy `repl` for a regex. The `ValueError` was raised while `_registry.py` was
being imported, so `import sgate` failed for the `static` and `tests` jobs alike,
and both died in "Set up gate tooling" before a single test ran.

Note what the guard did and did not do. It *did* make the build red — a
declaration-time guard is not silent. But the red arrived as an import failure in
an unrelated package, on a runner, roughly twenty minutes into a CI run, naming a
file and a field rather than the control. A test in this suite fails on the
author's machine in a second and names the slug, and it checks the three things
`__post_init__` cannot reach at all: that the files a control names exist, that
it says why, and that it is shaped like its kind.
"""
from __future__ import annotations

import dataclasses
import unittest
from pathlib import Path

from sgate.commands._registry import GATE_CONTROLS
from sgate.controls import Control

ROOT = Path(__file__).resolve().parent.parent.parent


class ControlDeclarationTest(unittest.TestCase):
    """Every declared control is a control somebody can actually run."""

    def test_the_registry_is_populated(self):
        """A guard that passes over an empty registry passes over a broken one.

        Not a floor on the count — the registry is meant to shrink as gates are
        retired, and asserting it only grows asserts the opposite of what is
        wanted. This is a sanity bound against a parse or import regression that
        yields an empty dict, which would make every test below vacuously green.
        """
        self.assertGreater(
            len(GATE_CONTROLS), 20,
            f"Expected 20+ declared controls, found {len(GATE_CONTROLS)}",
        )

    def test_every_control_survives_its_own_guards(self):
        """Re-run each declaration's validation in a loop that can name the control.

        A guard that fires while `_registry.py` is imported takes the whole
        package with it, so this test never runs — which is the honest limit of an
        import-time guard: it fails loudly, but it reports a file and a field, not
        the control, and it does so on a runner instead of on the machine that
        wrote the declaration.

        `dataclasses.replace` reconstructs a value and so re-enters
        `__post_init__`, which covers whatever *did* survive import — a guard added
        after this test was written, or a declaration reached by a path that does
        not trigger the import. Collecting every failure into one message matters
        too: a registry is edited one declaration at a time, and a test that
        reports one bad control per run turns a small mistake into several.
        """
        broken: list[str] = []
        for slug, control in sorted(GATE_CONTROLS.items()):
            if control.edit is None:
                continue
            try:
                dataclasses.replace(control.edit)
            except ValueError as exc:
                broken.append(f"  {slug}: {exc}")
        self.assertEqual(
            broken, [],
            "every declared edit must pass its own validation:\n" + "\n".join(broken),
        )

    def test_every_control_is_a_usable_control(self):
        """The fields `__post_init__` cannot check: the files have to exist.

        A control whose `path` names a moved file still validates, still imports,
        and still appears in the registry — it fails at run time, in the one place
        where nobody is reading the output. Same for `proves`, which is the join
        key back to the gate registry: a typo there silently drops the control out
        of the coverage check instead of failing it.
        """
        missing: list[str] = []
        for slug, control in sorted(GATE_CONTROLS.items()):
            for field in ("path", "proves"):
                value = getattr(control, field)
                if value and not (ROOT / value).exists():
                    missing.append(f"  {slug}: {field}={value!r} does not exist")
        self.assertEqual(
            missing, [],
            "every path a control names must exist on disk:\n" + "\n".join(missing),
        )

    def test_every_control_says_why(self):
        """`why` is not decoration — it is what tells the next reader the choice.

        Several of these record a regression that already happened. A control
        without one reads as arbitrary, and the first person to tidy it up will
        delete the entry that was holding a gate honest.
        """
        unreasoned = sorted(
            slug for slug, control in GATE_CONTROLS.items()
            if not control.why.strip()
        )
        self.assertEqual(
            unreasoned, [],
            f"these controls state no reason: {unreasoned}",
        )

    def test_every_control_is_shaped_by_its_kind(self):
        """Each kind carries the fields it means something by.

        `__post_init__` already enforces the required ones. This asserts the
        mirror image — that a kind does not carry a field it has no use for,
        which reads as "this was configured like the others" rather than "this is
        what this kind is": a `fixture` control with an `edit`, a `sabotage`
        control with no edit, an `exempt` with neither a reason nor a waiver.
        """
        wrong: list[str] = []
        for slug, control in sorted(GATE_CONTROLS.items()):
            if control.kind == "exempt":
                if control.edit is not None:
                    wrong.append(f"  {slug}: exempt control also carries an edit")
                if not control.reason.strip():
                    wrong.append(f"  {slug}: exempt control states no reason")
            elif control.kind == "fixture":
                if control.edit is not None:
                    wrong.append(f"  {slug}: fixture control also carries an edit")
                if not control.fixture.strip():
                    wrong.append(f"  {slug}: fixture control names no setup function")
            elif control.edit is None:
                wrong.append(f"  {slug}: {control.kind} control carries no edit")
        self.assertEqual(
            wrong, [],
            "each control must be shaped by its kind:\n" + "\n".join(wrong),
        )

    def test_controls_are_a_dict_of_controls(self):
        """The registry is keyed by slug; a value that is not a `Control` is a typo.

        Cheap, and it pins the type the rest of the tooling joins against.
        """
        wrong = sorted(
            f"{slug}={type(control).__name__}"
            for slug, control in GATE_CONTROLS.items()
            if not isinstance(control, Control)
        )
        self.assertEqual(wrong, [], f"non-Control entries in the registry: {wrong}")


if __name__ == "__main__":
    unittest.main()