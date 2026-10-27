#!/usr/bin/env python3
"""Unit tests for the computed-value rule in scripts/ci/validate-maestro-selectors.py.

A flow can compute a value — a date cell, a section id — and hand it to a
helper. The value crosses the boundary through `runFlow … env:`, because
Maestro substitutes the uppercase name before the helper runs.

What it cannot do is hold that value in a selector. `${output.stamp}` exists
only inside the flow that computed it, so an id that carries it waits for an
element that does not exist, and the flow fails on a timeout instead of on a
mistake. The validator used to report that as "unknown id" — true, and useless:
it named a symptom and not the rule.

These tests pin the rule's two halves, because a rule that only rejects the
false positives stops catching the real event it exists for.
"""
from __future__ import annotations

import importlib.util
import io
import contextlib
import sys
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


def _validate(flow_yaml: str) -> tuple[int, str]:
    """Run the validator over one flow and return its exit code and output."""
    mod = _load_validator()
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "01-computed.yaml").write_text(flow_yaml, encoding="utf-8")
        saved_argv = sys.argv
        sys.argv = [str(_VALIDATOR), str(root)]
        try:
            buffer = io.StringIO()
            with contextlib.redirect_stdout(buffer):
                code = mod.main()
        finally:
            sys.argv = saved_argv
        return code, buffer.getvalue()


class ComputedValueRuleTest(unittest.TestCase):
    """A computed value cannot be an id; it reaches a flow through `env:`."""

    def test_a_computed_value_as_an_id_is_reported_with_the_rule(self):
        """The finding names the rule, not just the symptom.

        "unknown id" is what the validator said before, and it sent the reader
        looking for a missing test tag instead of at the boundary the value
        has to cross.
        """
        code, output = _validate(
            """
- runScript:
    script: |
      ${output.stamp} = "abc"
- assertVisible:
    id: task_item_${output.stamp}
"""
        )
        self.assertEqual(code, 1)
        self.assertIn("a computed value cannot be an id", output)
        self.assertIn("runFlow", output)

    def test_an_env_name_as_an_id_is_not_a_finding(self):
        """The uppercase name a helper receives through `env:` stays legal.

        That is the shape the rule points at: the helper waits for `${NAME}`,
        and the value that fills it arrived as an env var.
        """
        code, output = _validate(
            """
- runFlow:
    file: helper.yaml
    env:
      PLANS_SECTION: ${output.PLANS_TODAY_SECTION}
- assertVisible:
    id: ${PLANS_SECTION}
"""
        )
        self.assertEqual(code, 0, output)
        self.assertNotIn("computed value", output)


if __name__ == "__main__":
    unittest.main()
