#!/usr/bin/env python3
"""Unit tests for the `inputText` rule in scripts/ci/validate-maestro-selectors.py.

Maestro's `inputText` takes the text and types it into the *focused* field. A
selector on the command is dropped without a word: the command log of Maestro
2.10 shows `inputTextCommand {text}` where the flow said `{id, text}`. The text
lands in whatever had the focus — usually the field above — and the flow fails
on the app's behaviour instead of on its own mistake.

On 2026-10-25 that is what three auth flows did. The password was typed into
the email field, the form stayed invalid, the sign-in button never fired, and
the assertion that the error text was on the screen failed for a reason that
had nothing to do with the app. The id was a real id, so the selector gate had
nothing to say — the command was well-formed and wrong.

So the rule under test is the *shape* of the command: an `inputText` that
carries a selector is a flow that cannot work, whatever the id is.
"""
from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent.parent
_VALIDATOR = _ROOT / "scripts" / "ci" / "validate-maestro-selectors.py"


def _load_validator():
    """Import the real validator by path.

    The filename has a dash, so it cannot be imported by module name, and copying
    the rule into the test would test the copy — which is how a gate and its
    tests agree on something nobody runs.
    """
    spec = importlib.util.spec_from_file_location("validate_maestro_selectors", _VALIDATOR)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class InputTextSelectorRuleTest(unittest.TestCase):
    """`collect_input_text_selectors` — the commands the rule rejects."""

    @classmethod
    def setUpClass(cls):
        cls.mod = _load_validator()

    def _collect(self, yaml_text: str):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "flow.yaml"
            path.write_text(yaml_text, encoding="utf-8")
            return self.mod.collect_input_text_selectors(path)

    def test_a_selector_on_input_text_is_reported(self):
        """The form the flows used: `- inputText: {id: …, text: …}`."""
        found = self._collect(
            """
appId: com.singularity.todo
---
- inputText:
    id: auth_password_input
    text: "secret"
"""
        )
        self.assertEqual(1, len(found))
        self.assertIn("id", found[0][0])

    def test_every_selector_key_counts(self):
        """The rule is about the shape, not about one key.

        `idRegex`, `text`, `textRegex` and `label` are dropped by Maestro the
        same way `id` is, so a rule that only knew about `id` would let the
        other four through.
        """
        found = self._collect(
            """
appId: com.singularity.todo
---
- inputText:
    idRegex: auth_.*_input
    text: "secret"
"""
        )
        self.assertEqual(1, len(found))
        self.assertIn("idRegex", found[0][0])

    def test_a_plain_input_text_is_accepted(self):
        """The pattern the rest of the tree uses: tap the field, then type."""
        found = self._collect(
            """
appId: com.singularity.todo
---
- tapOn:
    id: auth_password_input
- inputText: "secret"
"""
        )
        self.assertEqual([], found)

    def test_the_rule_reaches_a_nested_command(self):
        """The command may sit inside a `runFlow` or any other structure."""
        found = self._collect(
            """
appId: com.singularity.todo
---
- runFlow:
    commands:
      - inputText:
          id: auth_password_input
          text: "secret"
"""
        )
        self.assertEqual(1, len(found))

    def test_no_flow_in_the_tree_carries_a_selector(self):
        """The repository's own flows are clean.

        A gate that only its own tree can pass is a gate nobody trusts; this is
        the half of the rule that says the tree agrees with the gate.
        """
        flows_root = _ROOT / "Maestro" / "flows"
        offenders = []
        for flow_path in sorted(flows_root.rglob("*.yaml")):
            if "helpers" in flow_path.parts:
                continue
            for selectors, location in self.mod.collect_input_text_selectors(flow_path):
                offenders.append(f"{location}: {selectors}")
        self.assertEqual([], offenders)


if __name__ == "__main__":
    unittest.main()
