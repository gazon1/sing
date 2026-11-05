#!/usr/bin/env python3
"""The positive control for `scripts/check-touch-target-token.py`.

## Why this file exists

The minimum touch target has one home — `TouchTarget.MinSize` in the core UI
package — and the task list carries an alias of it in its own theme package.
The *value* is pinned by a test, so the two numbers cannot drift apart. The
*usage* was pinned by nothing: the chain gate reads the shape of a modifier
chain and not the token it names, so a file outside the task list could
expand a control with a literal `48.dp`, with the task list's alias, or by
importing the task list's theme package for the number — and every gate
stayed green.

A gate that has only ever reported zero is indistinguishable from a gate that
cannot fail, so this file plants one violation per shape the gate claims to
catch and asserts that the gate goes red on each of them. It also asserts
that the gate is green on a tree that holds only correct controls: a control
that fails for the wrong reason is not a control.

## What "the gate went red" means here

The exit code is checked, not just "non-zero": a gate that crashed, or that
rejected the `--root` argument, also exits non-zero. The planted file is
named in the output, with the line, as well — a gate that reports a violation
somewhere else has not found the violation that was planted.

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
GATE = ROOT / "scripts" / "check-touch-target-token.py"

#: The exit code the gate uses for a finding. Anything else — 0 (the gate saw
#: nothing), 2 (the gate could not read the tree), 3 (the gate rejected its
#: own arguments) — is a different verdict, and the control has to say which.
FOUND = 1

#: A file outside the task list — the notes feature.
OUTSIDE = "shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/Probe.kt"

#: A file inside the task list — the feature the alias belongs to.
INSIDE = (
    "shared-ui/src/commonMain/kotlin/com/singularity/todo/feature/tasks/"
    "presentation/components/Probe.kt"
)


def _source(body: str, imports: str = "") -> str:
    """A Kotlin file that carries `body`, with the given imports."""
    return f"""\
package com.singularity.todo.probe

import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
{imports}
{body}
"""


#: The helper's default argument: the shared token itself, by another name.
#: The gate has to accept it — that is what the codebase writes.
DEFAULT_EXPANSION = _source(
    """\
@Composable
private fun ProbeDefault() {
    Icon(modifier = Modifier.touchTarget())
}
"""
)

#: The shared token, written out. The one value a call site may name.
SHARED_TOKEN_EXPANSION = _source(
    """\
@Composable
private fun ProbeSharedToken() {
    Icon(modifier = Modifier.touchTarget(TouchTarget.MinSize))
}
""",
    imports="import com.singularity.todo.core.ui.TouchTarget\n",
)

#: A literal. The same number written down a second time.
LITERAL_EXPANSION = _source(
    """\
@Composable
private fun ProbeLiteral() {
    Icon(modifier = Modifier.touchTarget(48.dp))
}
"""
)

#: An argument written over several lines — the call the codebase formats
#: across lines. A report that repeated the argument as it is written would
#: break into several lines itself.
MULTILINE_ARGUMENT = _source(
    """\
@Composable
private fun ProbeMultilineArgument() {
    Icon(
        modifier = Modifier.touchTarget(
            48.dp,
        ),
    )
}
"""
)

#: The task list's alias, used outside the task list.
ALIAS_EXPANSION = _source(
    """\
@Composable
private fun ProbeAlias() {
    Icon(modifier = Modifier.touchTarget(TaskListSizes.MinTouchTarget))
}
""",
    imports=(
        "import com.singularity.todo.feature.tasks.presentation.theme.TaskListSizes\n"
    ),
)

#: The alias, used inside the task list — where it belongs.
ALIAS_INSIDE_THE_TASK_LIST = _source(
    """\
@Composable
private fun ProbeAliasInside() {
    Icon(modifier = Modifier.touchTarget(TaskListSizes.MinTouchTarget))
}
""",
    imports=(
        "import com.singularity.todo.feature.tasks.presentation.theme.TaskListSizes\n"
    ),
)

#: The task list's theme package imported to size a control. The reach into
#: another feature's package is visible at the import.
THEME_PACKAGE_TO_SIZE = _source(
    """\
@Composable
private fun ProbeThemePackage() {
    Icon(modifier = Modifier.size(TaskListSizes.Checkbox))
}
""",
    imports=(
        "import com.singularity.todo.feature.tasks.presentation.theme.TaskListSizes\n"
    ),
)

#: The same import, used for something that is not a size. The colours of the
#: task list live in that package too, and moving them out of it is not this
#: gate's business.
THEME_PACKAGE_NOT_SIZING = _source(
    """\
private val ProbeColour = TaskSemanticColors.accent
""",
    imports=(
        "import com.singularity.todo.feature.tasks.presentation.theme."
        "TaskSemanticColors\n"
    ),
)

#: The helper's own KDoc, written the way the repository writes it. A gate
#: that read comments would report the file that defines the rule.
COMMENT_MENTIONING_THE_HELPER = _source(
    """\
/**
 * `Modifier.touchTarget(minSize)` lays out a 48 dp node.
 */
@Composable
private fun ProbeComment() {
    Icon(modifier = Modifier.touchTarget())
}
"""
)

#: The declaration of the helper. It names the parameter, and a parameter is
#: not a size a call site chose.
HELPER_DECLARATION = _source(
    """\
fun Modifier.touchTarget(minSize: Dp = TouchTarget.MinSize): Modifier = this
"""
)


class TestTheTokenTheGateHolds(unittest.TestCase):
    """The shapes the gate claims to catch, and the shapes it must accept."""

    def _run_gate(self, files: dict[str, str]) -> subprocess.CompletedProcess[str]:
        """Point the gate at a temporary tree holding `files`, and run it."""
        with tempfile.TemporaryDirectory() as tmp:
            tree = pathlib.Path(tmp)
            for relative, source in files.items():
                probe = tree / relative
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

    def _assert_reported(self, source: str, relative: str = OUTSIDE) -> None:
        """The gate must go red on `source`, naming the file and the line."""
        result = self._run_gate({relative: source})
        self.assertEqual(
            result.returncode,
            FOUND,
            f"the gate must report the planted violation; it exited "
            f"{result.returncode}.\nstdout:\n{result.stdout}\nstderr:\n{result.stderr}",
        )
        self.assertIn(
            "Probe.kt",
            result.stdout,
            f"the report must name the file the violation was planted in:\n{result.stdout}",
        )

    def _assert_not_reported(self, source: str, relative: str = OUTSIDE) -> None:
        """The gate must stay green on `source`."""
        result = self._run_gate({relative: source})
        self.assertEqual(
            result.returncode,
            0,
            f"the gate must be green on a correct control; it exited "
            f"{result.returncode}.\nstdout:\n{result.stdout}\nstderr:\n{result.stderr}",
        )

    def test_a_literal_expansion_is_reported(self):
        """`48.dp` is the shared minimum written down a second time.

        The number can drift from the token, and nothing else in the
        repository would notice.
        """
        self._assert_reported(LITERAL_EXPANSION)

    def test_an_argument_written_over_several_lines_is_reported(self):
        """The call the codebase formats across lines is the call it writes.

        A report that repeated the argument as it is written would break into
        several lines, and half the finding would be lost in the break.
        """
        result = self._run_gate({OUTSIDE: MULTILINE_ARGUMENT})
        self.assertEqual(
            result.returncode,
            FOUND,
            f"the gate must report the planted violation; it exited "
            f"{result.returncode}.\nstdout:\n{result.stdout}\nstderr:\n{result.stderr}",
        )
        self.assertIn(
            "`48.dp,`",
            result.stdout,
            f"the report must name the argument as one line:\n{result.stdout}",
        )

    def test_the_alias_outside_the_task_list_is_reported(self):
        """The alias belongs to the task list.

        The same 48 dp under another feature's name is still a reach into
        that feature's theme package.
        """
        self._assert_reported(ALIAS_EXPANSION)

    def test_the_alias_inside_the_task_list_is_not_reported(self):
        """The task list's own controls keep their call sites."""
        self._assert_not_reported(ALIAS_INSIDE_THE_TASK_LIST, relative=INSIDE)

    def test_the_helper_default_is_not_reported(self):
        """The default argument *is* the shared token."""
        self._assert_not_reported(DEFAULT_EXPANSION)

    def test_the_shared_token_is_not_reported(self):
        """The one value a call site may name."""
        self._assert_not_reported(SHARED_TOKEN_EXPANSION)

    def test_the_theme_package_imported_to_size_is_reported(self):
        """The reach is visible at the import, and the import is reported."""
        result = self._run_gate({OUTSIDE: THEME_PACKAGE_TO_SIZE})
        self.assertEqual(
            result.returncode,
            FOUND,
            f"the gate must report the import of the task list's theme package; "
            f"it exited {result.returncode}.\nstdout:\n{result.stdout}"
            f"\nstderr:\n{result.stderr}",
        )
        self.assertIn("Probe.kt", result.stdout)
        self.assertIn(
            "TaskListSizes",
            result.stdout,
            f"the report must name the symbol that was imported:\n{result.stdout}",
        )

    def test_the_theme_package_imported_for_something_else_is_not_reported(self):
        """The colours of the task list live in that package too.

        A gate that reported every import of it would be reporting a
        problem the rule does not name.
        """
        self._assert_not_reported(THEME_PACKAGE_NOT_SIZING)

    def test_a_comment_mentioning_the_helper_is_not_reported(self):
        """Comments are not code.

        The helper's own documentation writes `Modifier.touchTarget(minSize)`
        as an example, and the file that defines the rule is not a violation.
        """
        self._assert_not_reported(COMMENT_MENTIONING_THE_HELPER)

    def test_the_declaration_of_the_helper_is_not_reported(self):
        """The declaration names the parameter; a call site names a value."""
        self._assert_not_reported(HELPER_DECLARATION)

    def test_the_report_names_the_line(self):
        """The finding carries the line, not only the file.

        A report that names a file alone sends the reader to grep the whole
        file; the line is the finding.
        """
        result = self._run_gate({OUTSIDE: LITERAL_EXPANSION})
        self.assertEqual(result.returncode, FOUND, result.stdout)
        line = LITERAL_EXPANSION.splitlines().index(
            "    Icon(modifier = Modifier.touchTarget(48.dp))"
        ) + 1
        self.assertIn(
            f"Probe.kt:{line}",
            result.stdout,
            f"the report must name the line the violation is on:\n{result.stdout}",
        )

    def test_a_clean_tree_is_green(self):
        """The baseline: a tree with only correct controls passes.

        Without this, a control that failed for an unrelated reason — a
        broken gate, a tree the gate cannot read — would look like a control
        that works.
        """
        result = self._run_gate(
            {
                OUTSIDE: DEFAULT_EXPANSION,
                INSIDE: ALIAS_INSIDE_THE_TASK_LIST,
            }
        )
        self.assertEqual(
            result.returncode,
            0,
            f"the gate must be green on a tree of correct controls; it exited "
            f"{result.returncode}.\nstdout:\n{result.stdout}\nstderr:\n{result.stderr}",
        )


if __name__ == "__main__":
    unittest.main()
