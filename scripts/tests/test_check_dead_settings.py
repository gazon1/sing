"""Tests for check-dead-settings.py — a persisted setting that nothing reads.

The defect this gate exists to catch: a setting can be fully wired and still do
nothing. `reminderDefault` was the live instance — a radio group, a preference
key, a `SettingsStore`, a DI binding and a `Contributor`, and no file under
`feature/tasks/` ever asked for it. The user picked a default, watched it
survive a restart, and no task was ever given it.

Three earlier revisions of this check were green on that exact field, each for a
different reason: matching preference-key names (every declaration is "read" by
the repository that wrote it — a tautology, not a consumption), following the
store-to-contributor chain while excluding the DI layer (which is exactly where
the contributor is resolved), and counting DI injections while stopping there
(perfection for *wiring*, blindness to *use*).

So the property under test is the narrow one the gate settled on: **a persisted
field is dead when no file outside the settings plumbing names it**, and the
plumbing is identified by what a file is (`SettingsStore.kt`,
`SettingsContributor.kt`, `core/settings/`, `feature/settings/`) rather than by
which folder it happens to sit in.

The assertions therefore plant, on a temporary tree, one field that only the
plumbing names and one field a real feature names — and require the gate to
call the first dead and the second alive. A revision that widened the plumbing
set, or narrowed it to a directory prefix, fails one of those two assertions.

The tree is temporary: the gate reads `ROOT` and `BASELINE` as module globals,
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
    "check_dead_settings", SCRIPTS / "check-dead-settings.py"
)
dead = importlib.util.module_from_spec(_spec)
sys.modules["check_dead_settings"] = dead
_spec.loader.exec_module(dead)

BUNDLE = "shared/src/commonMain/kotlin/com/singularity/todo/core/settings/SettingsBundle.kt"

CONSUMES_BOTH = "val sound = notifications.sound\nval vibration = notifications.vibration"

BUNDLE_TEXT = """
package com.singularity.todo.core.settings

data class Notifications(
    val sound: String = "ding",
    val vibration: Boolean = true,
) : SettingsSection

data class Ai(
    val isExporting: Boolean = false,
) : EphemeralState
"""


class DeadSettingsTree(unittest.TestCase):
    """A temporary tree the gate can be pointed at."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)
        self.bundle = self.root / BUNDLE
        self.bundle.parent.mkdir(parents=True, exist_ok=True)
        self.bundle.write_text(BUNDLE_TEXT, encoding="utf-8")
        self.baseline = self.root / "baseline.txt"

    def write(self, relative, text):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def feature_file(self, name="TaskCreateViewModel.kt", body="val x = 1"):
        return self.write(
            f"shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/{name}",
            body,
        )

    def run_gate(self, *args):
        buffer = io.StringIO()
        with (
            unittest.mock.patch.object(dead, "ROOT", self.root),
            unittest.mock.patch.object(dead, "BASELINE", self.baseline),
        ):
            with contextlib.redirect_stdout(buffer), contextlib.redirect_stderr(buffer):
                code = dead.run(dead.cli.main, list(args))
        return code, buffer.getvalue()


class CleanTreeTest(DeadSettingsTree):
    """Every persisted field is read by a feature: the gate passes."""

    def test_a_field_a_feature_reads_is_not_reported(self):
        self.feature_file(body="val sound = settings.notifications.sound")
        self.feature_file(
            name="TaskEditViewModel.kt",
            body="val vibration = settings.notifications.vibration",
        )
        code, output = self.run_gate()
        self.assertEqual(0, code, output)
        self.assertNotIn("[dead-setting]", output)

    def test_a_field_read_in_a_non_common_source_set_counts(self):
        """androidMain is a real consumer, not a copy of the declaration."""
        self.write(
            "shared/src/androidMain/kotlin/com/singularity/todo/feature/tasks/"
            "AndroidNotifier.kt",
            "val sound = notifications.sound",
        )
        self.feature_file(body="val vibration = notifications.vibration")
        code, output = self.run_gate()
        self.assertEqual(0, code, output)


class DeadFieldTest(DeadSettingsTree):
    """A field nothing outside the plumbing names is reported."""

    def test_a_field_no_feature_reads_is_reported(self):
        """The live defect: wired, rendered, and never read by a feature."""
        self.feature_file(body="val sound = settings.notifications.sound")
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("[dead-setting] Notifications.vibration", output)
        self.assertNotIn("[dead-setting] Notifications.sound", output)

    def test_a_field_only_the_plumbing_names_is_still_dead(self):
        """The whole point of the rewrite: plumbing is not consumption.

        `core/settings/` declares the keys and builds the flows. A field named
        there is read by the code that wrote it, which is a tautology, not a
        consumer.
        """
        self.write(
            "shared/src/commonMain/kotlin/com/singularity/todo/core/settings/"
            "NotificationsSettingsStore.kt",
            "val sound = prefs.sound\nval vibration = prefs.vibration",
        )
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("[dead-setting] Notifications.sound", output)
        self.assertIn("[dead-setting] Notifications.vibration", output)

    def test_a_field_read_only_by_a_settings_screen_is_still_dead(self):
        """`feature/settings/` renders every section unconditionally.

        This is why `reminderDefault` looked alive on screen: it was rendered,
        and rendering is not use.
        """
        self.write(
            "shared/src/commonMain/kotlin/com/singularity/todo/feature/settings/"
            "NotificationSettingsScreen.kt",
            "val sound = state.sound\nval vibration = state.vibration",
        )
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("[dead-setting] Notifications.sound", output)

    def test_the_quiet_mode_still_prints_the_findings(self):
        """`--quiet` means no header — not no findings.

        The gate is parsed by `sgate settings_read_by_a_feature`, which counts
        the `[dead-setting]` lines. Printing them only in verbose mode made a
        real finding render as "0 dead setting(s) found".
        """
        code, output = self.run_gate("--quiet")
        self.assertEqual(1, code, output)
        self.assertIn("[dead-setting] Notifications.vibration", output)
        self.assertNotIn("persisted sections", output)


class EphemeralTest(DeadSettingsTree):
    """`EphemeralState` is not persisted, so it is not a dead setting."""

    def test_an_ephemeral_field_is_not_reported(self):
        self.feature_file(body=CONSUMES_BOTH)
        code, output = self.run_gate()
        self.assertEqual(0, code, output)
        self.assertNotIn("Ai.isExporting", output)

    def test_the_ephemeral_sections_are_named_in_the_report(self):
        self.feature_file(body=CONSUMES_BOTH)
        code, output = self.run_gate()
        self.assertEqual(0, code, output)
        self.assertIn("not scanned, not persisted: Ai", output)


class BaselineTest(DeadSettingsTree):
    """An exemption is a claim a human made, and it must actually exempt."""

    def test_a_baselined_field_is_not_reported(self):
        self.feature_file(body="val sound = notifications.sound")
        self.baseline.write_text("Notifications.vibration # not used yet\n", encoding="utf-8")
        code, output = self.run_gate()
        self.assertEqual(0, code, output)

    def test_a_baselined_section_exempts_every_field_in_it(self):
        self.baseline.write_text("Notifications # whole section\n", encoding="utf-8")
        code, output = self.run_gate()
        self.assertEqual(0, code, output)

    def test_a_baseline_entry_does_not_exempt_a_field_that_is_alive(self):
        """The exemption list is not a place to hide a live field either."""
        self.feature_file(body=CONSUMES_BOTH)
        self.baseline.write_text("Notifications.sound\n", encoding="utf-8")
        code, output = self.run_gate()
        self.assertEqual(0, code, output)


class RefusalTest(DeadSettingsTree):
    """A gate that cannot read the inventory must not report a pass."""

    def test_a_missing_bundle_refuses_to_pass(self):
        self.bundle.unlink()
        code, output = self.run_gate()
        self.assertEqual(1, code)
        self.assertIn("refusing to pass silently", output)

    def test_no_kotlin_sources_refuses_to_pass(self):
        self.bundle.unlink()
        for path in (self.root / "shared").rglob("*.kt"):
            path.unlink()
        code, output = self.run_gate()
        self.assertEqual(1, code)
        self.assertIn("no Kotlin sources found", output)

    def test_zero_persisted_sections_refuses_to_pass(self):
        """An inventory that parsed to nothing is not an inventory."""
        self.bundle.write_text(
            "package com.singularity.todo.core.settings\n\nval nothing = 1\n",
            encoding="utf-8",
        )
        code, output = self.run_gate()
        self.assertEqual(1, code)
        self.assertIn("zero persisted sections", output)

    def test_the_summary_counts_the_scanned_fields(self):
        self.feature_file(body="val sound = notifications.sound")
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("2 scanned", output)


if __name__ == "__main__":
    unittest.main()
