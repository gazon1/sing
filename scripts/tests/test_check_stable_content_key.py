"""Tests for check-stable-content-key.py.

The gate compares two `when` expressions in `StableContentKey.kt`: the one over
the sealed `AppDestination` hierarchy, and the one it delegates the
record-bearing destinations to.

The compiler makes the outer `when` exhaustive, so a new destination does not
compile until it is named there. The hole is the delegation: the group of
destinations that stands for a record is handed to a second `when`, and that
one ends in `else -> error("no content key for $this")`. A destination added
to the group compiles, compiles, and fails at runtime on the first screen
that opens it. The gate is what sees it.

These tests are about the comparison: which delegation the gate reads, and
which hole it reports.
"""

import importlib.util
import pathlib
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS / "src"))

MODULE_PATH = SCRIPTS / "check-stable-content-key.py"
spec = importlib.util.spec_from_file_location("check_stable_content_key", MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
sys.modules["check_stable_content_key"] = mod
spec.loader.exec_module(mod)

DESTINATION = "shared/src/commonMain/kotlin/com/singularity/todo/feature/nav/AppDestination.kt"
KEY = "shared/src/commonMain/kotlin/com/singularity/todo/feature/nav/StableContentKey.kt"

HIERARCHY = """package com.singularity.todo.feature.nav

sealed interface AppDestination : AppNavKey {
    data object Plans : AppDestination {
        override val title = "Plans"
    }

    data class TaskGraph(val taskId: String) : AppDestination {
        override val title = "Task"
    }
}
"""

KEYS = """package com.singularity.todo.feature.nav

internal fun AppDestination.stableContentKey(): String = when (this) {
    is AppDestination.Plans -> "plans"

    is AppDestination.TaskGraph,
    -> recordContentKey()
}

private fun AppDestination.recordContentKey(): String = when (this) {
    is AppDestination.TaskGraph -> "task:" + taskId
}
"""


def write(root: pathlib.Path, relative: str, text: str) -> None:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


class ContentKeyCoverageTest(unittest.TestCase):
    def build(self, keys: str, hierarchy: str = HIERARCHY) -> list[mod.Finding]:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            write(root, DESTINATION, hierarchy)
            write(root, KEY, keys)
            return mod.check(root)

    def test_a_covered_hierarchy_has_no_findings(self):
        self.assertEqual(self.build(KEYS), [])

    def test_a_destination_delegated_without_a_branch_is_reported(self):
        """The hole the compiler cannot see.

        The group is extended with a destination that has no branch in the
        delegated `when`. The code compiles — the outer `when` still names
        every destination — and the destination fails at runtime.
        """
        keys = KEYS.replace(
            "    is AppDestination.TaskGraph,\n    -> recordContentKey()",
            "    is AppDestination.Plans,\n    is AppDestination.TaskGraph,\n    -> recordContentKey()",
        )
        findings = self.build(keys)
        self.assertEqual(len(findings), 1)
        self.assertIn("AppDestination.Plans", findings[0].message)
        self.assertIn("recordContentKey()", findings[0].message)

    def test_a_branch_that_names_a_destination_which_no_longer_exists_is_reported(self):
        keys = KEYS.replace(
            "    is AppDestination.TaskGraph -> \"task:\" + taskId",
            "    is AppDestination.TaskGraph -> \"task:\" + taskId\n\n    is AppDestination.Gone -> \"gone\"",
        )
        findings = self.build(keys)
        self.assertTrue(any("Gone" in finding.message for finding in findings))

    def test_a_destination_without_a_key_at_all_is_reported(self):
        hierarchy = HIERARCHY.replace(
            "    data class TaskGraph(val taskId: String) : AppDestination {\n        override val title = \"Task\"\n    }",
            "    data class TaskGraph(val taskId: String) : AppDestination {\n        override val title = \"Task\"\n    }\n\n"
            "    data object Calendar : AppDestination {\n        override val title = \"Calendar\"\n    }",
        )
        findings = self.build(KEYS, hierarchy)
        self.assertTrue(any("Calendar" in finding.message for finding in findings))


if __name__ == "__main__":
    unittest.main()
