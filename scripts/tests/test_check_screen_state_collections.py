"""Tests for check-screen-state-collections.py — a screen state holds no raw collection.

The defect this gate exists to catch: the sibling detekt rule reads the
*parameter* of a composable and cannot see inside the state. A screen that
receives `ArchiveUiState` passes that rule while the state itself carries
`List<Task>` — the promise the state makes about its own contents is then
unenforceable, and the value is built on the wrong side of the boundary.

So the assertions here plant, on a temporary tree, states that hold raw
collections and states that hold the persistent kind, and require the gate to
call the first ones out and to leave the second ones alone. They also check
the two directions of the register: a raw collection the register does not name
is a new debt, and a registered entry the code no longer has is a debt that was
paid and whose record did not move with it.

The tree is temporary: the gate reads `ROOT` and `REGISTER` as module globals,
so both are pointed at a `tempfile.TemporaryDirectory` and nothing is written
into the repository.
"""

import contextlib
import importlib.util
import io
import pathlib
import sys
import tempfile
import unittest
import unittest.mock

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS / "src"))

_spec = importlib.util.spec_from_file_location(
    "check_screen_state_collections", SCRIPTS / "check-screen-state-collections.py"
)
states = importlib.util.module_from_spec(_spec)
sys.modules["check_screen_state_collections"] = states
_spec.loader.exec_module(states)

STATE_SOURCE = """
package com.singularity.todo.feature.archive

import kotlinx.collections.immutable.ImmutableList

sealed interface ArchiveUiState {
    data object Loading : ArchiveUiState
    data class Content(
        val tasks: {TASKS_TYPE},
        val refreshing: Boolean = false,
    ) : ArchiveUiState
    data class Error(val message: String) : ArchiveUiState
}
"""


class ScreenStateTree(unittest.TestCase):
    """A temporary tree the gate can be pointed at."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)
        self.register = self.root / "config" / "compose" / "screen_state_collection_debt.conf"
        self.register.parent.mkdir(parents=True, exist_ok=True)

    def write_state(self, text, relative=None):
        relative = relative or (
            "shared/src/commonMain/kotlin/com/singularity/todo/feature/archive/"
            "ArchiveViewModel.kt"
        )
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def write_register(self, entries=(), recorded=None):
        lines = ["properties = %d" % (len(entries) if recorded is None else recorded)]
        lines.extend(entries)
        self.register.write_text("\n".join(lines) + "\n", encoding="utf-8")

    def run_gate(self, *args):
        buffer = io.StringIO()
        with (
            unittest.mock.patch.object(states, "ROOT", self.root),
            unittest.mock.patch.object(states, "REGISTER", self.register),
        ):
            with contextlib.redirect_stdout(buffer), contextlib.redirect_stderr(buffer):
                code = states.run(states.cli.main, list(args))
        return code, buffer.getvalue()


class PersistentStateTest(ScreenStateTree):
    """A state that holds the persistent kind is not reported."""

    def test_a_state_that_holds_the_persistent_kind_passes(self):
        self.write_state(STATE_SOURCE.replace("{TASKS_TYPE}", "ImmutableList<Task>"))
        self.write_register()
        code, output = self.run_gate()
        self.assertEqual(0, code, output)
        self.assertNotIn("[screen-state-collection]", output)


class RawCollectionTest(ScreenStateTree):
    """A state that holds a raw collection is reported."""

    def test_a_raw_collection_in_a_screen_state_is_reported(self):
        self.write_state(STATE_SOURCE.replace("{TASKS_TYPE}", "List<Task>"))
        self.write_register()
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn(
            "[screen-state-collection] com.singularity.todo.feature.archive."
            "ArchiveUiState.Content.tasks : List<Task>",
            output,
        )

    def test_a_registered_debt_is_not_reported(self):
        self.write_state(STATE_SOURCE.replace("{TASKS_TYPE}", "List<Task>"))
        self.write_register(
            entries=[
                "com.singularity.todo.feature.archive.ArchiveUiState.Content.tasks : List<Task>"
            ]
        )
        code, output = self.run_gate()
        self.assertEqual(0, code, output)
        self.assertNotIn("[screen-state-collection]", output)

    def test_the_quiet_mode_still_prints_the_findings(self):
        """`--quiet` means no header — not no findings."""
        self.write_state(STATE_SOURCE.replace("{TASKS_TYPE}", "List<Task>"))
        self.write_register()
        code, output = self.run_gate("--quiet")
        self.assertEqual(1, code, output)
        self.assertIn("[screen-state-collection]", output)
        self.assertNotIn("raw collection(s) held by", output)


class RegisterTest(ScreenStateTree):
    """The register is read from both sides."""

    def test_a_registered_debt_the_code_no_longer_has_is_reported(self):
        """The debt was paid and the record did not move with it."""
        self.write_state(STATE_SOURCE.replace("{TASKS_TYPE}", "ImmutableList<Task>"))
        self.write_register(
            entries=[
                "com.singularity.todo.feature.archive.ArchiveUiState.Content.tasks : List<Task>"
            ]
        )
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("the register names a debt the code no longer has", output)

    def test_a_registered_debt_whose_type_changed_is_reported(self):
        """The property is still there, but it holds something else now."""
        self.write_state(STATE_SOURCE.replace("{TASKS_TYPE}", "Set<Task>"))
        self.write_register(
            entries=[
                "com.singularity.todo.feature.archive.ArchiveUiState.Content.tasks : List<Task>"
            ]
        )
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("the declared type and the record disagree", output)

    def test_the_recorded_size_must_match_the_register(self):
        """The size is the ratchet: the number and the register must agree."""
        self.write_state(STATE_SOURCE.replace("{TASKS_TYPE}", "ImmutableList<Task>"))
        self.write_register(entries=["com.example.Whatever.gone : List<Nothing>"], recorded=7)
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("the recorded size is the ratchet", output)

    def test_a_register_without_a_size_is_reported(self):
        self.write_state(STATE_SOURCE.replace("{TASKS_TYPE}", "ImmutableList<Task>"))
        self.register.write_text("# nothing here\n", encoding="utf-8")
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("records no size", output)


class ScopeTest(ScreenStateTree):
    """What counts as a screen state — and what does not."""

    def test_a_state_of_the_core_is_out_of_scope(self):
        """`SyncState` is the state of a repository, not of a screen."""
        self.write_state(
            """
package com.singularity.todo.core.sync

sealed interface SyncState {
    data class Idle(val triggers: Set<SyncTrigger>) : SyncState
}
""",
            relative=(
                "shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncState.kt"
            ),
        )
        self.write_register()
        code, output = self.run_gate()
        self.assertEqual(0, code, output)
        self.assertNotIn("[screen-state-collection]", output)

    def test_a_domain_model_is_not_a_screen_state(self):
        """`Task` is a leaf, and its collections are the contract's business."""
        self.write_state(
            """
package com.singularity.todo.feature.tasks.domain.model

data class Task(
    val id: String,
    val tags: List<TagId>,
)
""",
            relative=(
                "shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/"
                "domain/model/Task.kt"
            ),
        )
        self.write_state(STATE_SOURCE.replace("{TASKS_TYPE}", "ImmutableList<Task>"))
        self.write_register()
        code, output = self.run_gate()
        self.assertEqual(0, code, output)
        self.assertNotIn("[screen-state-collection]", output)
        self.assertNotIn("Task.tags", output)

    def test_a_property_of_a_nested_declaration_belongs_to_that_declaration(self):
        """The parameters of `Content` are not the interface's properties.

        A scan that read them as the interface's would report the same
        collection twice, under two names.
        """
        self.write_state(STATE_SOURCE.replace("{TASKS_TYPE}", "List<Task>"))
        self.write_register(
            entries=[
                "com.singularity.todo.feature.archive.ArchiveUiState.Content.tasks : List<Task>"
            ]
        )
        code, output = self.run_gate()
        self.assertEqual(0, code, output)
        self.assertNotIn("ArchiveUiState.tasks", output)


class EmptyTreeTest(ScreenStateTree):
    """A scan that finds nothing must not look like a scan that finds nothing."""

    def test_a_tree_without_sources_fails_loudly(self):
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("no Kotlin sources found", output)

    def test_a_tree_without_states_fails_loudly(self):
        """Sources are there, but no state among them: the scan read nothing."""
        self.write_state(
            """
package com.singularity.todo.feature.tasks.domain.model

data class Task(val id: String)
""",
            relative=(
                "shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/"
                "domain/model/Task.kt"
            ),
        )
        self.write_register()
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("found zero screen states", output)


if __name__ == "__main__":
    unittest.main()
