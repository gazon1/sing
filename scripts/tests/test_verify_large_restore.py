"""Tests for verify-large-restore.py — the parts that decide what "correct" means.

The command itself is a device scenario: it needs an attached phone, an APK and
a ten-minute import. What is worth testing here is the arithmetic that decides
whether the run passes or fails, because those are the pieces that silently
turn a broken restore into a green report:

* `slug` / `task_item_id` reproduce the app's own test tag
  (`TestTags.slug`) — a slug that differs by one character makes the flow
  open nothing and the run reports a false failure;
* `compare_counts` treats a table that is missing as a difference, not as an
  empty table — otherwise a restore that wrote nothing at all passes;
* `relationship_problems` counts the links that point at nothing, on a
  database built to contain exactly one broken link of each kind;
* `over_budget_problems` tells a refusal for the archive's size apart from a
  refusal for anything else — the dialog is the same in both cases;
* `interrupted_import_problems` holds an import that was cut short to
  leaving the profile whole or empty, over every table and not only tasks;
* `related_task_title` picks the task the app actually shows relations for;
* `verdict_problems` is what decides the run's exit code, so a check whose
  problem is missing from it does not fail the run;
* `padded_archive` builds an archive the app has to refuse for the budget's
  reason.

The file name has a dash in it, so the script is loaded by path — the way the
repository's own tests load it.
"""

import hashlib
import importlib.util
import sqlite3
import tempfile
import unittest
import unittest.mock
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent


def _load_driver():
    path = ROOT / "scripts" / "verify-large-restore.py"
    spec = importlib.util.spec_from_file_location("verify_large_restore", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


driver = _load_driver()

#: The budget the app enforces on the unpacked archive — the number the
#: over-budget archive has to cross.
APP_MAX_TOTAL_BYTES = 64 * 1024 * 1024


SCHEMA = """
CREATE TABLE tasks (id TEXT PRIMARY KEY, title TEXT, project_id TEXT, parent_task_id TEXT);
CREATE TABLE projects (id TEXT PRIMARY KEY, name TEXT);
CREATE TABLE tags (id TEXT PRIMARY KEY, name TEXT);
CREATE TABLE notes (id TEXT PRIMARY KEY, title TEXT);
CREATE TABLE checklist_items (id TEXT PRIMARY KEY, task_id TEXT);
CREATE TABLE task_tags (task_id TEXT, tag_id TEXT);
CREATE TABLE task_dependencies (task_id TEXT, depends_on_task_id TEXT);
"""


def _database(tmp: Path, statements: list[str]) -> Path:
    path = tmp / "todo.db"
    connection = sqlite3.connect(path)
    try:
        connection.executescript(SCHEMA)
        for statement in statements:
            connection.execute(statement)
        connection.commit()
    finally:
        connection.close()
    return path


class SlugTest(unittest.TestCase):
    """The tag the app puts on a task row must be the tag the flow taps."""

    def test_generated_title(self):
        self.assertEqual(driver.slug("Task 1"), "task_1")
        self.assertEqual(driver.slug("Task 10000"), "task_10000")

    def test_runs_of_non_alphanumerics_collapse_into_one_separator(self):
        self.assertEqual(driver.slug("Hello,  World!"), "hello_world")

    def test_separators_are_trimmed_from_both_ends(self):
        self.assertEqual(driver.slug("  spaced  out  "), "spaced_out")

    def test_non_latin_titles_stay_addressable(self):
        # The app's own rule is Unicode-aware: a Cyrillic title must not
        # collapse into an empty tag.
        self.assertEqual(driver.slug("Задача 1"), "задача_1")

    def test_task_item_id_carries_the_tag_prefix(self):
        self.assertEqual(driver.task_item_id("Task 42"), "task_item_task_42")


class NumberOfTest(unittest.TestCase):
    def test_the_number_in_the_title(self):
        self.assertEqual(driver.number_of("Task 10000"), 10000)

    def test_a_title_without_a_number(self):
        self.assertEqual(driver.number_of("Task"), -1)
        self.assertEqual(driver.number_of(""), -1)


class CompareCountsTest(unittest.TestCase):
    def test_equal_counts_produce_no_problems(self):
        self.assertEqual(driver.compare_counts({"tasks": 10}, {"tasks": 10}), [])

    def test_a_different_count_is_a_problem_that_names_both_numbers(self):
        problems = driver.compare_counts({"tasks": 10}, {"tasks": 9})
        self.assertEqual(len(problems), 1)
        self.assertIn("10", problems[0])
        self.assertIn("9", problems[0])

    def test_a_table_that_could_not_be_read_is_not_an_empty_table(self):
        problems = driver.compare_counts({"tasks": 10}, {})
        self.assertEqual(len(problems), 1)
        self.assertIn("no such table", problems[0])


class CountRowsTest(unittest.TestCase):
    def test_the_counts_of_the_manifests_tables(self):
        with tempfile.TemporaryDirectory() as raw:
            path = _database(
                Path(raw),
                [
                    "INSERT INTO tasks (id, title) VALUES ('t1', 'Task 1')",
                    "INSERT INTO tasks (id, title) VALUES ('t2', 'Task 2')",
                    "INSERT INTO tags (id, name) VALUES ('g1', 'tag')",
                ],
            )
            counts = driver.count_rows(path)
        self.assertEqual(counts["tasks"], 2)
        self.assertEqual(counts["tags"], 1)
        self.assertEqual(counts["projects"], 0)
        self.assertEqual(counts["taskTags"], 0)


class RelationshipProblemsTest(unittest.TestCase):
    """A restore that writes rows but not links answers "how many" correctly.

    These are the links that have to be there: a task's tag, a task's
    dependency, a checklist item's task, a task's project, a subtask's parent.
    """

    def test_a_healthy_database_has_no_problems(self):
        with tempfile.TemporaryDirectory() as raw:
            path = _database(
                Path(raw),
                [
                    "INSERT INTO projects (id, name) VALUES ('p1', 'p')",
                    "INSERT INTO tasks (id, title, project_id) VALUES ('t1', 'Task 1', 'p1')",
                    "INSERT INTO tasks (id, title, parent_task_id) VALUES ('t2', 'Task 2', 't1')",
                    "INSERT INTO tags (id, name) VALUES ('g1', 'g')",
                    "INSERT INTO task_tags (task_id, tag_id) VALUES ('t1', 'g1')",
                    "INSERT INTO task_dependencies (task_id, depends_on_task_id) VALUES ('t2', 't1')",
                    "INSERT INTO checklist_items (id, task_id) VALUES ('c1', 't1')",
                ],
            )
            self.assertEqual(driver.relationship_problems(path), [])

    def test_a_tag_link_without_a_task_is_reported(self):
        with tempfile.TemporaryDirectory() as raw:
            path = _database(
                Path(raw),
                [
                    "INSERT INTO tags (id, name) VALUES ('g1', 'g')",
                    "INSERT INTO task_tags (task_id, tag_id) VALUES ('missing', 'g1')",
                ],
            )
            problems = driver.relationship_problems(path)
        self.assertIn("task_tags without a task: 1", problems)

    def test_a_dependency_without_the_depended_on_task_is_reported(self):
        with tempfile.TemporaryDirectory() as raw:
            path = _database(
                Path(raw),
                [
                    "INSERT INTO tasks (id, title) VALUES ('t1', 'Task 1')",
                    "INSERT INTO task_dependencies (task_id, depends_on_task_id) "
                    "VALUES ('t1', 'missing')",
                ],
            )
            problems = driver.relationship_problems(path)
        self.assertIn("task_dependencies without the depended-on task: 1", problems)

    def test_a_subtask_without_its_parent_is_reported(self):
        with tempfile.TemporaryDirectory() as raw:
            path = _database(
                Path(raw),
                [
                    "INSERT INTO tasks (id, title, parent_task_id) "
                    "VALUES ('t2', 'Task 2', 'missing')",
                ],
            )
            problems = driver.relationship_problems(path)
        self.assertIn("subtasks without a parent task: 1", problems)

    def test_a_tag_link_without_a_tag_is_reported(self):
        with tempfile.TemporaryDirectory() as raw:
            path = _database(
                Path(raw),
                [
                    "INSERT INTO tasks (id, title) VALUES ('t1', 'Task 1')",
                    "INSERT INTO task_tags (task_id, tag_id) VALUES ('t1', 'missing')",
                ],
            )
            problems = driver.relationship_problems(path)
        self.assertIn("task_tags without a tag: 1", problems)

    def test_a_dependency_without_a_task_is_reported(self):
        with tempfile.TemporaryDirectory() as raw:
            path = _database(
                Path(raw),
                [
                    "INSERT INTO tasks (id, title) VALUES ('t1', 'Task 1')",
                    "INSERT INTO task_dependencies (task_id, depends_on_task_id) "
                    "VALUES ('missing', 't1')",
                ],
            )
            problems = driver.relationship_problems(path)
        self.assertIn("task_dependencies without a task: 1", problems)

    def test_a_checklist_item_without_a_task_is_reported(self):
        with tempfile.TemporaryDirectory() as raw:
            path = _database(
                Path(raw),
                [
                    "INSERT INTO checklist_items (id, task_id) VALUES ('c1', 'missing')",
                ],
            )
            problems = driver.relationship_problems(path)
        self.assertIn("checklist_items without a task: 1", problems)

    def test_a_task_without_a_project_is_reported(self):
        with tempfile.TemporaryDirectory() as raw:
            path = _database(
                Path(raw),
                [
                    "INSERT INTO tasks (id, title, project_id) "
                    "VALUES ('t1', 'Task 1', 'missing')",
                ],
            )
            problems = driver.relationship_problems(path)
        self.assertIn("tasks without a project: 1", problems)


class RelatedTaskTitleTest(unittest.TestCase):
    """The related-tasks picker is only reachable through a task that has a
    relation — the section is folded away until the task has one.

    The app reads a task's relations from the edges it *holds* — the ones it
    blocks (`task_dependencies.task_id`) — and not from the edges that point at
    it, so a task that is only blocked by another one keeps the section hidden.
    """

    def test_the_blocking_side_of_an_edge_is_chosen(self):
        with tempfile.TemporaryDirectory() as raw:
            path = _database(
                Path(raw),
                [
                    "INSERT INTO tasks (id, title) VALUES ('t1', 'Task 1')",
                    "INSERT INTO tasks (id, title) VALUES ('t2', 'Task 2')",
                    "INSERT INTO task_dependencies (task_id, depends_on_task_id) "
                    "VALUES ('t1', 't2')",
                ],
            )
            self.assertEqual(driver.related_task_title(path), "Task 1")

    def test_the_blocked_side_of_an_edge_is_not_chosen(self):
        with tempfile.TemporaryDirectory() as raw:
            path = _database(
                Path(raw),
                [
                    "INSERT INTO tasks (id, title) VALUES ('t1', 'Task 1')",
                    "INSERT INTO tasks (id, title) VALUES ('t2', 'Task 2')",
                    "INSERT INTO task_dependencies (task_id, depends_on_task_id) "
                    "VALUES ('t2', 't1')",
                ],
            )
            # 'Task 1' is only blocked by 'Task 2', so the app hides the
            # section for it and a run that opened it would find nothing.
            self.assertEqual(driver.related_task_title(path), "Task 2")

    def test_a_task_without_a_relation_is_not_chosen(self):
        with tempfile.TemporaryDirectory() as raw:
            path = _database(
                Path(raw),
                [
                    "INSERT INTO tasks (id, title) VALUES ('t1', 'Task 1')",
                    "INSERT INTO tasks (id, title) VALUES ('t2', 'Task 2')",
                    "INSERT INTO tasks (id, title) VALUES ('t3', 'Task 3')",
                    "INSERT INTO task_dependencies (task_id, depends_on_task_id) "
                    "VALUES ('t1', 't2')",
                ],
            )
            self.assertNotEqual(driver.related_task_title(path), "Task 3")

    def test_the_task_with_the_highest_number_is_chosen(self):
        with tempfile.TemporaryDirectory() as raw:
            path = _database(
                Path(raw),
                [
                    "INSERT INTO tasks (id, title) VALUES ('t1', 'Task 7')",
                    "INSERT INTO tasks (id, title) VALUES ('t2', 'Task 42')",
                    "INSERT INTO task_dependencies (task_id, depends_on_task_id) "
                    "VALUES ('t1', 't3')",
                    "INSERT INTO task_dependencies (task_id, depends_on_task_id) "
                    "VALUES ('t2', 't4')",
                ],
            )
            # A search for the title has to find one row and not a neighbour:
            # a search for 'Task 7' finds 'Task 42' as well.
            self.assertEqual(driver.related_task_title(path), "Task 42")

    def test_a_title_that_several_tasks_carry_is_not_chosen(self):
        with tempfile.TemporaryDirectory() as raw:
            path = _database(
                Path(raw),
                [
                    "INSERT INTO tasks (id, title) VALUES ('t1', 'Task 1')",
                    "INSERT INTO tasks (id, title) VALUES ('t2', 'Task 1')",
                    "INSERT INTO task_dependencies (task_id, depends_on_task_id) "
                    "VALUES ('t2', 't1')",
                ],
            )
            self.assertIsNone(driver.related_task_title(path))

    def test_a_database_without_relations_has_no_title(self):
        with tempfile.TemporaryDirectory() as raw:
            path = _database(
                Path(raw),
                ["INSERT INTO tasks (id, title) VALUES ('t1', 'Task 1')"],
            )
            self.assertIsNone(driver.related_task_title(path))


class PaddedArchiveTest(unittest.TestCase):
    """The archive the app has to refuse has to be over the budget for the
    budget's reason — so it keeps the real entries and adds the padding."""

    def test_the_padded_archive_keeps_the_entries_and_crosses_the_budget(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            source = tmp / "synthetic-1k.zip"
            with zipfile.ZipFile(source, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("backup.json", '{"tasks": []}')
            padded = driver.padded_archive(source, tmp)

            self.assertTrue(padded.exists())
            with zipfile.ZipFile(padded) as archive:
                names = archive.namelist()
                unpacked = sum(info.file_size for info in archive.infolist())
            self.assertIn("backup.json", names)
            self.assertIn("padding.bin", names)
            self.assertGreater(unpacked, APP_MAX_TOTAL_BYTES)


class ApkIdentityTest(unittest.TestCase):
    """The run has to notice that somebody else's build landed on the device.

    A foreign build opens the profile's database with a different schema
    version, and the app then shows a migration error instead of its own
    screens — a failure that reads like the restore's fault and is not.
    """

    def _apk(self, tmp: Path, content: bytes) -> Path:
        path = tmp / "app-debug.apk"
        path.write_bytes(content)
        return path

    def _with_digest(self, digest: str):
        return unittest.mock.patch.object(driver, "installed_apk_digest", lambda serial: digest)

    def test_the_same_apk_passes_the_check(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            apk = self._apk(tmp, b"the build this run installed")
            digest = hashlib.md5(apk.read_bytes()).hexdigest()
            with self._with_digest(digest):
                driver.verify_apk_unchanged("device", apk)

    def test_a_foreign_build_is_refused(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            apk = self._apk(tmp, b"the build this run installed")
            with self._with_digest("0" * 32):
                with self.assertRaises(driver.RunError) as caught:
                    driver.verify_apk_unchanged("device", apk)
            self.assertIn("Another session installed another build", str(caught.exception))

    def test_a_device_that_reports_no_checksum_is_not_refused(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            apk = self._apk(tmp, b"the build this run installed")
            with self._with_digest(""):
                driver.verify_apk_unchanged("device", apk)


class ImportDurationTest(unittest.TestCase):
    """The import's length is measured between two moments on the screen.

    The import starts when the confirmation is tapped and ends when the app
    reports it — a snackbar for an import that worked, the shared failure
    dialog for one that did not. Measuring from the wrong end, or reporting
    no length at all, is what turns a broken restore into a green report.
    """

    def test_a_snackbar_is_measured_from_the_tap(self):
        duration, measured = driver.import_duration(
            started=0.0, tapped=10.0, snackbar=42.0, error_at=None
        )
        self.assertEqual(measured, "confirm-to-snackbar")
        self.assertEqual(duration, 32.0)

    def test_a_failure_dialog_is_measured_from_the_tap_too(self):
        duration, measured = driver.import_duration(
            started=0.0, tapped=10.0, snackbar=None, error_at=25.0
        )
        self.assertEqual(measured, "confirm-to-error-dialog")
        self.assertEqual(duration, 15.0)

    def test_an_import_that_reported_nothing_has_no_length(self):
        duration, measured = driver.import_duration(
            started=0.0, tapped=10.0, snackbar=None, error_at=None
        )
        self.assertEqual(measured, "none")
        self.assertIsNone(duration)


class TaskRowReadingTest(unittest.TestCase):
    """A screen dump is XML with something else glued onto the end.

    The device writes the tree and then whatever the shell said, and the
    reader has to take the tree and leave the rest alone. A reader that
    insists on the whole output finds no rows at all and calls an imported
    dataset that is really there a failure.
    """

    DUMP = (
        "Killed\n<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>"
        '<hierarchy rotation="0"><node index="0" resource-id="task_item_task_2"'
        ' class="android.view.View" text="Task 2" /></hierarchy>\nKilled'
    )

    #: A dump that the device cut off in the middle of the tree.
    CUT_OFF = (
        "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>"
        '<hierarchy rotation="0"><node index="0" resource-id="task_item_task_2"'
        ' class="android.view.View" text="Task 2"><node index="1" resource-id="tasks_fab"'
    )

    def _with_dump(self, dump: str):
        return unittest.mock.patch.object(driver, "ui_dump", lambda serial: dump)

    def test_a_row_is_found_in_a_dump_with_junk_around_it(self):
        with self._with_dump(self.DUMP):
            self.assertEqual(driver.first_task_row("device"), "task_item_task_2")

    def test_a_row_is_found_in_a_dump_that_was_cut_off(self):
        with self._with_dump(self.CUT_OFF):
            self.assertEqual(driver.first_task_row("device"), "task_item_task_2")

    def test_a_dump_without_a_tree_has_no_row(self):
        with self._with_dump("Killed"):
            self.assertIsNone(driver.first_task_row("device"))


class TitleForTagTest(unittest.TestCase):
    """The row's title comes from the database, not from the screen.

    The row on the screen carries the title among several other texts, and
    the wrong one would send the flow looking for a title that is not there.
    """

    def _with_titles(self, titles):
        return unittest.mock.patch.object(driver, "task_titles", lambda db: titles)

    def test_the_title_whose_slug_is_the_tag(self):
        with self._with_titles(["Task 2", "Buy milk"]):
            self.assertEqual(driver.title_for_tag(Path("todo.db"), "task_item_buy_milk"), "Buy milk")

    def test_a_tag_that_belongs_to_no_task_has_no_title(self):
        with self._with_titles(["Task 2"]):
            self.assertIsNone(driver.title_for_tag(Path("todo.db"), "task_item_task_2x"))


class WaitForTaskRowTest(unittest.TestCase):
    """The task list of a big dataset takes a moment to come up.

    Reading the screen once and giving up would call an imported dataset
    that is really there a failure, so the row is waited for.
    """

    def test_the_row_is_returned_once_the_list_shows_one(self):
        rows = [None, None, "task_item_buy_milk"]
        with unittest.mock.patch.object(driver, "first_task_row", side_effect=rows):
            with unittest.mock.patch.object(driver.time, "sleep"):
                self.assertEqual(
                    driver.wait_for_task_row("device", timeout=60),
                    "task_item_buy_milk",
                )

    def test_a_list_that_never_shows_a_row_gives_up(self):
        with unittest.mock.patch.object(driver, "first_task_row", return_value=None):
            with unittest.mock.patch.object(driver.time, "sleep"):
                with unittest.mock.patch.object(driver.time, "monotonic", side_effect=[0.0, 0.0, 61.0]):
                    self.assertIsNone(driver.wait_for_task_row("device", timeout=60))


class PullDatabaseTest(unittest.TestCase):
    """A copy of the database is one moment of it, not three.

    A write-ahead log that is left over from an earlier copy is replayed over
    the new database, and the rows the app has deleted come back with it —
    so a run reads a profile that was wiped as one that kept everything.
    """

    def test_a_log_left_over_from_an_earlier_copy_is_removed(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            target = tmp / "device-db"
            target.mkdir()
            stale = target / "todo.db-wal"
            stale.write_bytes(b"a log of an earlier copy")

            def fake_bytes(*args, serial="", timeout=0):
                name = args[-1]
                if name.endswith("todo.db"):
                    return 0, b"the database"
                return 1, "No such file"

            with unittest.mock.patch.object(driver, "adb_bytes", fake_bytes):
                with unittest.mock.patch.object(driver, "stop_app", lambda serial: True):
                    pulled = driver.pull_database("device", tmp)

            self.assertTrue(pulled.exists())
            self.assertFalse(stale.exists())

    def test_an_app_that_will_not_stop_is_not_copied(self):
        """A copy of a database that is still being written is a torn one:
        the main file and the write-ahead log are read at different moments.
        """
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)

            def fake_bytes(*args, serial="", timeout=0):
                return 0, b"the database"

            with unittest.mock.patch.object(driver, "adb_bytes", fake_bytes):
                with unittest.mock.patch.object(driver, "stop_app", lambda serial: False):
                    with self.assertRaises(driver.RunError) as caught:
                        driver.pull_database("device", tmp)

            self.assertIn("torn", str(caught.exception))
            self.assertFalse((tmp / "device-db" / "todo.db").exists())

    def test_a_copy_that_could_not_be_made_leaves_the_counts_unknown(self):
        """A check that asked for the counts has to say it could not get
        them — every table comes back as "could not be read".
        """
        with tempfile.TemporaryDirectory() as raw:
            with unittest.mock.patch.object(driver, "pull_database", side_effect=driver.RunError("no")):
                counts, problem = driver.pull_counts("device", Path(raw))

            self.assertTrue(problem)
            self.assertTrue(all(value == -1 for value in counts.values()))
            self.assertIn("tasks", counts)


class WipeWaitingTest(unittest.TestCase):
    """The wipe is waited for with the app's own marker, not with a guess.

    A wipe of a large profile takes minutes. A reader that stops the app to
    look at the database before the wipe has committed kills the transaction,
    the transaction is rolled back, and the profile still holds every row the
    wipe had already deleted — which reads as "the wipe does not work".
    """

    def test_the_marker_ends_the_wait(self):
        calls = {"logcat": 0}

        def fake_adb(*args, serial="", timeout=0):
            if args[:2] == ("logcat", "-d"):
                calls["logcat"] += 1
                if calls["logcat"] < 3:
                    return 0, "nothing yet"
                return 0, f"I/DebugSeedActivity: {driver.RESET_MARKER} 25 tables"
            return 0, ""

        with unittest.mock.patch.object(driver, "adb", fake_adb):
            with unittest.mock.patch.object(driver.time, "sleep", lambda seconds: None):
                elapsed = driver.wait_for_reset_marker("device", timeout=60)

        self.assertGreaterEqual(elapsed, 0)
        self.assertEqual(calls["logcat"], 3)

    def test_a_wipe_that_never_finishes_is_refused(self):
        def fake_adb(*args, serial="", timeout=0):
            return 0, "nothing here"

        with unittest.mock.patch.object(driver, "adb", fake_adb):
            with unittest.mock.patch.object(driver.time, "sleep", lambda seconds: None):
                with self.assertRaises(driver.RunError):
                    driver.wait_for_reset_marker("device", timeout=0)

    def test_the_log_is_cleared_before_the_link_is_sent(self):
        """The marker of an earlier wipe would be read as this one's."""
        seen = []

        def fake_adb(*args, serial="", timeout=0):
            seen.append(args)
            if args[:2] == ("logcat", "-d"):
                return 0, f"I/DebugSeedActivity: {driver.RESET_MARKER} 25 tables"
            return 0, "Starting: Intent"

        def fake_pull(serial, out_dir, stop=True):
            path = out_dir / "todo.db"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"")
            return path

        with unittest.mock.patch.object(driver, "adb", fake_adb):
            with unittest.mock.patch.object(driver, "count_rows", lambda db: {"tasks": 0}):
                with unittest.mock.patch.object(driver, "pull_database", fake_pull):
                    counts, elapsed = driver.reset_content("device", Path("/tmp/wipe-test"))

        self.assertEqual(counts, {"tasks": 0})
        self.assertEqual(seen[0][:2], ("logcat", "-c"))
        self.assertIn("todo-debug://seed?reset=content", " ".join(seen[1]))

    def test_a_wipe_that_cannot_be_confirmed_is_refused(self):
        """A table that could not be read is an unknown table, not an empty
        one: without it the wipe cannot be said to have happened.
        """

        def fake_adb(*args, serial="", timeout=0):
            if args[:2] == ("logcat", "-d"):
                return 0, f"I/DebugSeedActivity: {driver.RESET_MARKER} 25 tables"
            return 0, "Starting: Intent"

        def fake_pull(serial, out_dir, stop=True):
            path = out_dir / "todo.db"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"")
            return path

        with unittest.mock.patch.object(driver, "adb", fake_adb):
            with unittest.mock.patch.object(driver, "count_rows", lambda db: {"tasks": -1}):
                with unittest.mock.patch.object(driver, "pull_database", fake_pull):
                    with self.assertRaises(driver.RunError) as caught:
                        driver.reset_content("device", Path("/tmp/wipe-test"))

        self.assertIn("could not be read", str(caught.exception))


class OverBudgetProblemsTest(unittest.TestCase):
    """The app has to refuse an over-budget archive *for its size*.

    A refusal for any other reason wears the same dialog, so the dialog
    alone proves nothing: the message in it is what says that the budget
    was the reason.
    """

    #: The message the app's own budget check fails an archive with.
    BUDGET_MESSAGE = "backup archive entry exceeds remaining byte budget"

    def test_a_refusal_that_names_the_budget_is_accepted(self):
        outcome = {
            "errorDialog": True,
            "reportedBySnackbar": False,
            "errorDialogText": f"Error {self.BUDGET_MESSAGE}",
        }
        self.assertEqual(driver.over_budget_problems(outcome, {"tasks": 0}), [])

    def test_a_refusal_for_another_reason_is_a_problem(self):
        outcome = {
            "errorDialog": True,
            "reportedBySnackbar": False,
            "errorDialogText": "Error That file is not a Singularity backup",
        }
        problems = driver.over_budget_problems(outcome, {"tasks": 0})
        self.assertEqual(len(problems), 1)
        self.assertIn("not for its size", problems[0])

    def test_an_import_the_app_called_done_is_a_problem(self):
        outcome = {
            "errorDialog": False,
            "reportedBySnackbar": True,
            "errorDialogText": "",
        }
        problems = driver.over_budget_problems(outcome, {"tasks": 0})
        self.assertEqual(len(problems), 1)
        self.assertIn("instead of refusing", problems[0])

    def test_an_import_that_failed_without_saying_so_is_a_problem(self):
        outcome = {
            "errorDialog": False,
            "reportedBySnackbar": False,
            "errorDialogText": "",
        }
        problems = driver.over_budget_problems(outcome, {"tasks": 0})
        self.assertEqual(len(problems), 1)
        self.assertIn("without saying so", problems[0])

    def test_rows_left_behind_are_a_problem(self):
        outcome = {
            "errorDialog": True,
            "reportedBySnackbar": False,
            "errorDialogText": self.BUDGET_MESSAGE,
        }
        problems = driver.over_budget_problems(outcome, {"tasks": 3, "projects": 0})
        self.assertEqual(len(problems), 1)
        self.assertIn("left rows behind", problems[0])

    def test_a_table_that_could_not_be_read_is_not_an_empty_table(self):
        """Without the counts of every table the check cannot say that the
        refused archive wrote nothing: an unreadable table is an unknown one.
        """
        outcome = {
            "errorDialog": True,
            "reportedBySnackbar": False,
            "errorDialogText": self.BUDGET_MESSAGE,
        }
        problems = driver.over_budget_problems(outcome, {"tasks": 0, "projects": -1})
        self.assertEqual(len(problems), 1)
        self.assertIn("could not be read", problems[0])


class InterruptedImportProblemsTest(unittest.TestCase):
    """An import that was cut short has to leave the profile whole or empty.

    "Part of the dataset" is not only about tasks: an import that wrote the
    projects and the tags but no task yet is part of the dataset too.
    """

    def test_an_empty_profile_is_what_the_check_looks_for(self):
        counts = {
            "tasks": 0,
            "projects": 0,
            "tags": 0,
            "notes": 0,
            "checklistItems": 0,
            "taskDependencies": 0,
            "taskTags": 0,
        }
        self.assertEqual(driver.interrupted_import_problems(True, counts, 100), [])

    def test_a_profile_with_entities_but_no_task_is_part_of_the_dataset(self):
        counts = {
            "tasks": 0,
            "projects": 12,
            "tags": 3,
            "notes": 0,
            "checklistItems": 0,
            "taskDependencies": 0,
            "taskTags": 0,
        }
        problems = driver.interrupted_import_problems(True, counts, 100)
        self.assertEqual(len(problems), 1)
        self.assertIn("part of the dataset", problems[0])

    def test_an_import_that_was_never_cut_short_is_not_tested(self):
        problems = driver.interrupted_import_problems(False, {"tasks": 0}, 100)
        self.assertEqual(len(problems), 1)
        self.assertIn("not cut short", problems[0])

    def test_an_import_that_had_already_committed_is_a_problem(self):
        problems = driver.interrupted_import_problems(True, {"tasks": 100}, 100)
        self.assertEqual(len(problems), 1)
        self.assertIn("already committed", problems[0])

    def test_a_table_that_could_not_be_read_is_not_an_empty_table(self):
        problems = driver.interrupted_import_problems(True, {"tasks": -1}, 100)
        self.assertEqual(len(problems), 1)
        self.assertIn("could not be read", problems[0])


class ApkVariantTest(unittest.TestCase):
    """The report must not claim a build variant it does not know.

    An APK handed in with `--apk` says nothing about its build type, and the
    identity check establishes the package and the version, not the variant.
    """

    def test_a_build_of_this_checkout_is_a_debug_one(self):
        self.assertEqual(driver.apk_variant(True), "debug")

    def test_an_apk_from_the_outside_has_no_known_variant(self):
        self.assertEqual(driver.apk_variant(False), "unknown")


class VerdictProblemsTest(unittest.TestCase):
    """The exit code of the run is this list, so it is built in one place.

    A check whose problem never reaches this list does not fail the run — that
    is the failure mode this function exists to make impossible.
    """

    def test_a_clean_run_has_no_problems(self):
        problems = driver.verdict_problems([], [], [], True, True)
        self.assertEqual(problems, [])

    def test_a_failed_browse_flow_is_a_problem(self):
        problems = driver.verdict_problems([], [], [], False, True)
        self.assertEqual(len(problems), 1)
        self.assertIn("did not open in the app", problems[0])

    def test_a_restore_that_never_reported_itself_is_a_problem(self):
        problems = driver.verdict_problems([], [], [], True, False)
        self.assertEqual(len(problems), 1)
        self.assertIn("never reported itself", problems[0])

    def test_the_problems_of_every_check_end_up_in_one_list(self):
        problems = driver.verdict_problems(
            ["the counts differ"],
            ["a link points at nothing"],
            ["over-budget: the archive was not refused"],
            False,
            False,
        )
        self.assertEqual(len(problems), 5)


class DialogTextTest(unittest.TestCase):
    """The words in the dialog are what say why the import failed."""

    def test_the_text_of_the_screen_is_read(self):
        dump = (
            '<?xml version="1.0"?><hierarchy><node text="Error" resource-id="result_dialog">'
            '<node text="backup archive exceeds limits: entries=2 bytes=73400320"/></node>'
            "</hierarchy>"
        )
        self.assertIn("exceeds limits", driver.dialog_text(dump))
        self.assertTrue(driver.mentions_budget(driver.dialog_text(dump)))

    def test_a_message_about_anything_else_is_not_the_budget(self):
        self.assertFalse(driver.mentions_budget("That file is not a Singularity backup"))


if __name__ == "__main__":
    unittest.main()
