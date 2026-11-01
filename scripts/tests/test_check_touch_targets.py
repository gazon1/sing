#!/usr/bin/env python3
"""The positive control for `scripts/check-touch-targets.py`.

## Why this file exists

A gate that has only ever reported zero is indistinguishable from a gate that
cannot fail. The touch-targets gate was registered, blocking, and green — and
it was blind: it could not see a chain spread over several lines, it could not
see the lambda form of `.clickable`, and it skipped every file that mentioned
the `touchTarget()` helper, so one correct control hid every violation in
that file. All three shapes are the shapes this codebase writes.

The only thing that could have said so was a control: a test that plants a
violation and watches the gate go red. Without it, the blindness was
invisible — the gate was registered, blocking, and green, and nobody knew it
was looking at nothing.

So this file plants one violation per shape the gate claims to catch, and
asserts the gate goes red on each of them. It also asserts the gate is green
on a tree that holds only correct controls: a control that fails for the wrong
reason is not a control, and a gate that is red on a clean tree proves
nothing about a sabotage.

## What "the gate went red" means here

The exit code is checked, not just "non-zero": a gate that crashed, or that
rejected the `--root` argument, also exits non-zero, and a control that
accepts that has stopped proving what it claims to prove. The planted file is
named in the output as well — a gate that reports a violation somewhere else
has not found the violation that was planted.

The tree is temporary: the gate is pointed at it with `--root`, so the
working tree is never modified and an interrupted run leaves nothing behind.
"""
from __future__ import annotations

import pathlib
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
GATE = ROOT / "scripts" / "check-touch-targets.py"

#: The exit code the gate uses for a finding. Anything else — 0 (the gate saw
#: nothing), 2 (the gate could not read the tree), 3 (the gate rejected its
#: own arguments) — is a different verdict, and the control has to say which.
FOUND = 1

#: The source of a chain that is spread over several lines and uses the
#: lambda form of the call. `re` does not match a newline with `.` unless it
#: is told to, and the first version of the gate's pattern ended in
#: `clickable(` — so neither the line break nor the lambda was visible.
MULTILINE_LAMBDA = """\
package com.singularity.todo.probe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
private fun ProbeMultiLineLambda(onClick: () -> Unit) {
    Icon(
        modifier = Modifier
            .size(40.dp)
            .clickable { onClick() },
    )
}
"""

#: The same chain with the parenthesised form of the call. This one the first
#: version of the gate could see — as long as the whole chain was on one
#: line, which a chain written this way never is.
MULTILINE_PARENTHESISED = """\
package com.singularity.todo.probe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
private fun ProbeMultiLineParenthesised(onClick: () -> Unit) {
    Icon(
        modifier = Modifier
            .size(40.dp)
            .clickable(onClick = { onClick() }),
    )
}
"""

#: One line, one chain. The shape a regex over the whole file can see, and
#: the shape the codebase writes least often.
ONE_LINE = """\
package com.singularity.todo.probe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
private fun ProbeOneLine(onClick: () -> Unit) {
    Icon(modifier = Modifier.size(40.dp).clickable { onClick() })
}
"""

#: A correct control next to a violating one. The first version of the gate
#: skipped every file that mentioned `touchTarget()`, so the correct chain in
#: this file hid the violating one — the shape that made the gate useless in
#: exactly the files that use the helper.
CORRECT_BESIDE_VIOLATING = """\
package com.singularity.todo.probe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.touchTarget

@Composable
private fun ProbeCorrectBesideViolating(onClick: () -> Unit) {
    Icon(modifier = Modifier.touchTarget().size(40.dp).clickable { onClick() })
    Icon(modifier = Modifier.size(40.dp).clickable { onClick() })
}
"""

#: Only correct controls. The gate has to be green on this: a gate that is red
#: on a clean tree proves nothing about a planted violation.
ONLY_CORRECT = """\
package com.singularity.todo.probe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.touchTarget

@Composable
private fun ProbeOnlyCorrect(onClick: () -> Unit) {
    Icon(modifier = Modifier.touchTarget().size(40.dp).clickable { onClick() })
    Icon(modifier = Modifier.touchTarget().size(48.dp).clickable { onClick() })
    Icon(modifier = Modifier.size(40.dp))
}
"""

#: Where the planted sources go in the temporary tree. A directory called
#: `test` would be skipped by the gate, so the probe lives in the shape the
#: production sources live in.
PROBE_PATH = "shared/src/commonMain/kotlin/com/singularity/todo/probe/Probe.kt"


class TouchTargetsPositiveControlTest(unittest.TestCase):
    """One test per shape the gate claims to catch."""

    def _run_gate(self, source: str) -> subprocess.CompletedProcess[str]:
        """Point the gate at a temporary tree holding `source`, and run it."""
        with tempfile.TemporaryDirectory() as tmp:
            tree = pathlib.Path(tmp)
            probe = tree / PROBE_PATH
            probe.parent.mkdir(parents=True, exist_ok=True)
            probe.write_text(source, encoding="utf-8")
            return subprocess.run(
                [sys.executable, str(GATE), "--root", str(tree)],
                cwd=ROOT,
                capture_output=True,
                text=True,
                check=False,
                timeout=120,
            )

    def _assert_reported(self, source: str) -> None:
        """The gate must report the planted violation, and name the file."""
        result = self._run_gate(source)
        self.assertEqual(
            result.returncode,
            FOUND,
            f"the gate must exit {FOUND} on a planted violation; it exited "
            f"{result.returncode}.\nstdout:\n{result.stdout}\nstderr:\n{result.stderr}",
        )
        self.assertIn(
            "Probe.kt",
            result.stdout,
            "the report must name the file the violation was planted in — a gate "
            "that found something else has not found the planted violation",
        )

    def test_a_clean_tree_is_green(self):
        """The baseline: a tree with only correct controls passes.

        Without this, a control that failed for an unrelated reason — a broken
        gate, a tree the gate cannot read — would look like a control that
        works.
        """
        result = self._run_gate(ONLY_CORRECT)
        self.assertEqual(
            result.returncode,
            0,
            f"the gate must be green on a tree of correct controls; it exited "
            f"{result.returncode}.\nstdout:\n{result.stdout}\nstderr:\n{result.stderr}",
        )

    def test_a_multiline_chain_with_the_lambda_form_is_reported(self):
        self._assert_reported(MULTILINE_LAMBDA)

    def test_a_multiline_chain_with_the_parenthesised_form_is_reported(self):
        self._assert_reported(MULTILINE_PARENTHESISED)

    def test_a_one_line_chain_is_reported(self):
        self._assert_reported(ONE_LINE)

    def test_a_correct_control_does_not_hide_a_violation(self):
        """The shape that made the first version of the gate useless.

        The file holds a correct chain and a violating one. A gate that skips
        a file because it mentions the helper reports this file as clean.
        """
        self._assert_reported(CORRECT_BESIDE_VIOLATING)

    def test_the_correct_chain_next_to_the_violation_is_not_reported(self):
        """Only the violating chain is a finding.

        The correct chain expands the target before the size constrains it, so
        its hit area is the minimum. A gate that reported it would push the
        codebase into removing the expansion, which is the opposite of what
        the rule asks for.
        """
        result = self._run_gate(CORRECT_BESIDE_VIOLATING)
        self.assertEqual(result.returncode, FOUND)
        # One file, one violation: the correct chain is not among them.
        self.assertEqual(
            result.stdout.count("Probe.kt"),
            1,
            f"exactly one chain in the file is a violation:\n{result.stdout}",
        )


if __name__ == "__main__":
    unittest.main()
