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
  reason;
* `archive_paths` keeps a profile that carries a path away from the
  filesystem, and `reserve_output_dir` keeps two runs out of one output
  directory.

The file name has a dash in it, so the script is loaded by path — the way the
repository's own tests load it.
"""

import contextlib
import hashlib
import importlib.util
import json
import os
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


#: The schema of the app's own tables, reduced to the columns the driver
#: reads. `user_id` carries the scope of the profile the row belongs to —
#: the default value is the scope of the default profile, so a row that is
#: inserted without a scope belongs to it.
SCHEMA = """
CREATE TABLE tasks (
    id TEXT PRIMARY KEY, title TEXT, project_id TEXT, parent_task_id TEXT,
    user_id TEXT DEFAULT 'u1'
);
CREATE TABLE projects (
    id TEXT PRIMARY KEY, name TEXT, parent_id TEXT, user_id TEXT DEFAULT 'u1'
);
CREATE TABLE tags (id TEXT PRIMARY KEY, name TEXT, user_id TEXT DEFAULT 'u1');
CREATE TABLE notes (
    id TEXT PRIMARY KEY, title TEXT, task_id TEXT, project_id TEXT, parent_note_id TEXT,
    user_id TEXT DEFAULT 'u1'
);
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


class ProfileScopeTest(unittest.TestCase):
    """The counts have to be the counts of the profile under test.

    A device can hold more than one profile, and the rows of the other
    profiles are not the restore's rows: counting the whole database reports
    them as a difference between the manifest and a restore that was correct.
    The scope is the `user_id` the app stamps on a profile's rows, and the
    rows that hang off a task are counted through the task they belong to.
    """

    #: Two profiles' worth of rows: the default scope `u1` and the scope of
    #: a second profile, `u2`.
    TWO_PROFILES = [
        "INSERT INTO tasks (id, title, user_id) VALUES ('t1', 'Task 1', 'u1')",
        "INSERT INTO tasks (id, title, user_id) VALUES ('x1', 'Other task', 'u2')",
        "INSERT INTO tags (id, name, user_id) VALUES ('g1', 'tag', 'u1')",
        "INSERT INTO tags (id, name, user_id) VALUES ('gx', 'other', 'u2')",
        "INSERT INTO task_tags (task_id, tag_id) VALUES ('t1', 'g1')",
        "INSERT INTO task_tags (task_id, tag_id) VALUES ('x1', 'gx')",
    ]

    def test_the_count_is_the_scopes_own(self):
        with tempfile.TemporaryDirectory() as raw:
            path = _database(Path(raw), self.TWO_PROFILES)
            counts = driver.count_rows(path, "u1")
        self.assertEqual(counts["tasks"], 1)
        self.assertEqual(counts["tags"], 1)
        self.assertEqual(counts["taskTags"], 1)

    def test_a_count_without_a_scope_is_the_whole_database(self):
        with tempfile.TemporaryDirectory() as raw:
            path = _database(Path(raw), self.TWO_PROFILES)
            counts = driver.count_rows(path)
        self.assertEqual(counts["tasks"], 2)
        self.assertEqual(counts["taskTags"], 2)

    def test_the_scope_of_the_rows_the_reset_removed(self):
        """The reset deletes the active profile's rows and nothing else, so
        the rows that are gone after it name the profile under test.
        """
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            before = _database(tmp, self.TWO_PROFILES)
            after_dir = tmp / "after"
            after_dir.mkdir()
            after = _database(
                after_dir,
                [
                    "INSERT INTO tasks (id, title, user_id) VALUES ('x1', 'Other task', 'u2')",
                    "INSERT INTO tags (id, name, user_id) VALUES ('gx', 'other', 'u2')",
                    "INSERT INTO task_tags (task_id, tag_id) VALUES ('x1', 'gx')",
                ],
            )
            self.assertEqual(driver.removed_scopes(before, after), {"u1"})

    def test_a_reset_that_removed_nothing_names_no_scope(self):
        with tempfile.TemporaryDirectory() as raw:
            path = _database(Path(raw), self.TWO_PROFILES)
            self.assertEqual(driver.removed_scopes(path, path), set())

    def test_the_scope_of_the_restored_dataset(self):
        with tempfile.TemporaryDirectory() as raw:
            path = _database(Path(raw), self.TWO_PROFILES)
            self.assertEqual(driver.dataset_scope(path, ["t1"]), "u1")

    def test_a_dataset_that_is_in_two_scopes_names_none(self):
        """A dataset that is spread over two profiles cannot be attributed
        to one of them, so it names no scope at all.
        """
        with tempfile.TemporaryDirectory() as raw:
            path = _database(Path(raw), self.TWO_PROFILES)
            self.assertIsNone(driver.dataset_scope(path, ["t1", "x1"]))

    def test_a_broken_link_of_another_profile_is_not_ours(self):
        with tempfile.TemporaryDirectory() as raw:
            path = _database(
                Path(raw),
                [
                    "INSERT INTO tasks (id, title, user_id) VALUES ('t1', 'Task 1', 'u1')",
                    "INSERT INTO tags (id, name, user_id) VALUES ('gx', 'other', 'u2')",
                    "INSERT INTO task_tags (task_id, tag_id) VALUES ('missing', 'gx')",
                ],
            )
            self.assertEqual(driver.relationship_problems(path, "u1"), [])
            self.assertIn(
                "task_tags without a task: 1", driver.relationship_problems(path, "u2")
            )

    def test_the_default_profile_carries_the_plain_user_id(self):
        """The app's own mapping: the default profile's rows carry the user
        id, every other profile's rows carry the profile id in front of it.
        """
        self.assertEqual(driver.scoped_user_id("default", "u1"), "u1")
        self.assertEqual(driver.scoped_user_id("work", "u1"), "work/u1")


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


class RelatedTaskTest(unittest.TestCase):
    """The run needs the related task's id as well as its title: the picker
    addresses its candidates by id."""

    def test_the_id_comes_with_the_title(self):
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
            self.assertEqual(driver.related_task(path), ("t2", "Task 2"))

    def test_a_database_without_relations_has_no_task(self):
        with tempfile.TemporaryDirectory() as raw:
            path = _database(
                Path(raw),
                ["INSERT INTO tasks (id, title) VALUES ('t1', 'Task 1')"],
            )
            self.assertIsNone(driver.related_task(path))


class PickerCandidateTest(unittest.TestCase):
    """The picker is a search over the dataset, so the run searches for a task
    the picker can offer and a query that finds that task and not a neighbour.

    The picker refuses the task itself, the tasks it already relates to, and
    every candidate whose addition would close a cycle; the search is a phrase
    whose last word is unfinished, so the number of the candidate must not
    begin the number of another task.
    """

    def _dataset(self, tmp: Path, tasks: int, relations: list[tuple[str, str]]) -> Path:
        statements = [
            f"INSERT INTO tasks (id, title) VALUES ('t{n}', 'Task {n}')"
            for n in range(1, tasks + 1)
        ]
        statements += [
            f"INSERT INTO task_dependencies (task_id, depends_on_task_id) VALUES ('{a}', '{b}')"
            for a, b in relations
        ]
        return _database(tmp, statements)

    def test_the_candidate_is_not_the_task_itself(self):
        with tempfile.TemporaryDirectory() as raw:
            path = self._dataset(Path(raw), 12, [("t12", "t11")])
            candidate = driver.picker_candidate(path, "t12")
            self.assertIsNotNone(candidate)
            self.assertNotEqual(candidate[0], "t12")

    def test_the_candidate_is_not_a_task_the_task_already_relates_to(self):
        with tempfile.TemporaryDirectory() as raw:
            path = self._dataset(Path(raw), 12, [("t12", "t11")])
            candidate = driver.picker_candidate(path, "t12")
            self.assertNotEqual(candidate[0], "t11")

    def test_a_candidate_that_holds_a_relation_of_its_own_is_not_offered(self):
        # Such a candidate could close a cycle, and the picker refuses those.
        with tempfile.TemporaryDirectory() as raw:
            path = self._dataset(Path(raw), 12, [("t12", "t11"), ("t3", "t4")])
            candidate = driver.picker_candidate(path, "t12")
            self.assertNotEqual(candidate[0], "t3")

    def test_a_title_that_several_tasks_carry_is_not_offered(self):
        with tempfile.TemporaryDirectory() as raw:
            path = _database(
                Path(raw),
                [
                    "INSERT INTO tasks (id, title) VALUES ('t1', 'Task 1')",
                    "INSERT INTO tasks (id, title) VALUES ('t2', 'Task 1')",
                    "INSERT INTO tasks (id, title) VALUES ('t3', 'Task 3')",
                    "INSERT INTO tasks (id, title) VALUES ('t4', 'Task 4')",
                    "INSERT INTO task_dependencies (task_id, depends_on_task_id) "
                    "VALUES ('t3', 't2')",
                ],
            )
            candidate = driver.picker_candidate(path, "t3")
            self.assertEqual(candidate[0], "t4")

    def test_the_number_of_the_candidate_begins_no_other_number(self):
        # 'Task 1' would also find 'Task 10', 'Task 100' and 'Task 1000',
        # because the search leaves the last word unfinished.
        with tempfile.TemporaryDirectory() as raw:
            path = self._dataset(Path(raw), 1000, [("t1000", "t999")])
            candidate = driver.picker_candidate(path, "t1000")
            self.assertEqual(candidate, ("t998", "Task 998"))

    def test_the_largest_number_is_chosen(self):
        with tempfile.TemporaryDirectory() as raw:
            path = self._dataset(Path(raw), 12, [("t12", "t11")])
            candidate = driver.picker_candidate(path, "t12")
            self.assertEqual(candidate, ("t10", "Task 10"))

    def test_a_task_that_relates_to_everything_offers_nothing(self):
        with tempfile.TemporaryDirectory() as raw:
            path = self._dataset(
                Path(raw),
                4,
                [("t4", "t1"), ("t4", "t2"), ("t4", "t3")],
            )
            self.assertIsNone(driver.picker_candidate(path, "t4"))

    def test_a_database_without_tasks_offers_nothing(self):
        with tempfile.TemporaryDirectory() as raw:
            path = _database(Path(raw), [])
            self.assertIsNone(driver.picker_candidate(path, "t1"))


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
            digest = "md5:" + hashlib.md5(apk.read_bytes()).hexdigest()
            with self._with_digest(digest):
                driver.verify_apk_unchanged("device", apk)

    def test_a_foreign_build_is_refused(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            apk = self._apk(tmp, b"the build this run installed")
            with self._with_digest("md5:" + "0" * 32):
                with self.assertRaises(driver.RunError) as caught:
                    driver.verify_apk_unchanged("device", apk)
            self.assertIn("Another session installed another build", str(caught.exception))

    def test_a_device_that_reports_no_checksum_is_refused(self):
        """A build that cannot be told apart from another one is not a pass.

        The device said nothing about which build it carries, so the run
        cannot claim to have measured the one it installed.
        """
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            apk = self._apk(tmp, b"the build this run installed")
            with self._with_digest(""):
                with self.assertRaises(driver.RunError) as caught:
                    driver.verify_apk_unchanged("device", apk)
            self.assertIn("checksum", str(caught.exception))

    def test_the_digest_the_device_reports_is_the_one_that_was_verified(self):
        """The report names the exact build the measurements belong to."""
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            apk = self._apk(tmp, b"the build this run installed")
            digest = "sha256:" + hashlib.sha256(apk.read_bytes()).hexdigest()
            with self._with_digest(digest):
                self.assertEqual(driver.verify_installed_apk("device", apk), digest)

    def test_a_device_that_reports_no_checksum_is_not_an_identity(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            apk = self._apk(tmp, b"the build this run installed")
            with self._with_digest(""):
                with self.assertRaises(driver.RunError) as caught:
                    driver.verify_installed_apk("device", apk)
            self.assertIn("does not report a checksum", str(caught.exception))


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
        return unittest.mock.patch.object(driver, "task_titles", lambda db, scope=None: titles)

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
            with unittest.mock.patch.object(driver, "warm_up_app", lambda serial: None):
                with unittest.mock.patch.object(driver, "count_rows", lambda db: {"tasks": 0}):
                    with unittest.mock.patch.object(driver, "pull_database", fake_pull):
                        counts, elapsed, scope = driver.reset_content(
                            "device", Path("/tmp/wipe-test")
                        )

        self.assertEqual(counts, {"tasks": 0})
        self.assertIsNone(scope)
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
            with unittest.mock.patch.object(driver, "warm_up_app", lambda serial: None):
                with unittest.mock.patch.object(driver, "count_rows", lambda db: {"tasks": -1}):
                    with unittest.mock.patch.object(driver, "pull_database", fake_pull):
                        with self.assertRaises(driver.RunError) as caught:
                            driver.reset_content("device", Path("/tmp/wipe-test"))

        self.assertIn("could not be read", str(caught.exception))


class WarmUpTest(unittest.TestCase):
    """A wipe that reaches a cold process deletes nothing.

    The app reads its session from the encrypted store when it starts, and that
    read takes seconds. Until it is done the profile's rows are not the current
    user's rows, and a reset asked for in that window answers "there is nothing
    to wipe" — in a second, with a log line that says every table was reached.
    The run then reads a profile that still holds every row and blames the
    restore for it.
    """

    AGENDA_DUMP = (
        '<?xml version="1.0"?><hierarchy><node resource-id="nav_tab_inbox" /></hierarchy>'
    )
    LOCK_DUMP = '<?xml version="1.0"?><hierarchy><node resource-id="keyguard_root" /></hierarchy>'

    def test_the_agenda_is_the_proof_that_the_identity_is_there(self):
        self.assertTrue(driver.agenda_is_up(self.AGENDA_DUMP))
        self.assertFalse(driver.agenda_is_up(self.LOCK_DUMP))
        self.assertFalse(driver.agenda_is_up(""))

    def test_the_app_is_started_before_the_agenda_is_waited_for(self):
        seen = []

        def fake_adb(*args, serial="", timeout=0):
            seen.append(args)
            return 0, ""

        with unittest.mock.patch.object(driver, "adb", fake_adb):
            with unittest.mock.patch.object(driver, "wake_unlock", lambda serial: None):
                with unittest.mock.patch.object(
                    driver, "ui_dump", lambda serial: self.AGENDA_DUMP
                ):
                    driver.warm_up_app("device")

        self.assertIn(("shell", "am", "start", "-n", f"{driver.APP_ID}/.MainActivity"), seen)

    def test_an_app_that_never_comes_up_is_refused(self):
        """A wipe that cannot be trusted must not be asked for."""

        def fake_adb(*args, serial="", timeout=0):
            return 0, ""

        with unittest.mock.patch.object(driver, "adb", fake_adb):
            with unittest.mock.patch.object(driver, "wake_unlock", lambda serial: None):
                with unittest.mock.patch.object(driver, "ui_dump", lambda serial: self.LOCK_DUMP):
                    with unittest.mock.patch.object(driver.time, "sleep", lambda seconds: None):
                        with self.assertRaises(driver.RunError) as caught:
                            driver.warm_up_app("device", timeout=0)

        self.assertIn("agenda", str(caught.exception))

    def test_the_wipe_asks_for_the_app_to_be_up_first(self):
        """The warm-up comes before the link, not after it."""

        def fake_adb(*args, serial="", timeout=0):
            if args[:2] == ("logcat", "-d"):
                return 0, f"I/DebugSeedActivity: {driver.RESET_MARKER} 25 tables"
            return 0, "Starting: Intent"

        def fake_pull(serial, out_dir, stop=True):
            path = out_dir / "todo.db"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"")
            return path

        order = []

        def fake_warm_up(serial):
            order.append("warm up")

        with unittest.mock.patch.object(driver, "adb", fake_adb):
            with unittest.mock.patch.object(driver, "warm_up_app", fake_warm_up):
                with unittest.mock.patch.object(driver, "count_rows", lambda db: {"tasks": 0}):
                    with unittest.mock.patch.object(driver, "pull_database", fake_pull):
                        driver.reset_content("device", Path("/tmp/wipe-test"))

        self.assertEqual(order, ["warm up"])


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
    projects and the tags but no task yet is part of the dataset too — and so
    is an import that wrote every task and none of the other tables.
    """

    EXPECTED = {
        "tasks": 100,
        "projects": 10,
        "tags": 5,
        "notes": 20,
        "checklistItems": 200,
        "taskDependencies": 30,
        "taskTags": 300,
    }

    def _empty(self):
        return {key: 0 for key in self.EXPECTED}

    def test_an_empty_profile_is_what_the_check_looks_for(self):
        self.assertEqual(driver.interrupted_import_problems(True, True, self._empty(), self.EXPECTED), [])

    def test_a_profile_with_entities_but_no_task_is_part_of_the_dataset(self):
        counts = self._empty()
        counts["projects"] = 12
        counts["tags"] = 3
        problems = driver.interrupted_import_problems(True, True, counts, self.EXPECTED)
        self.assertEqual(len(problems), 1)
        self.assertIn("part of the dataset", problems[0])

    def test_an_import_that_was_never_cut_short_is_not_tested(self):
        problems = driver.interrupted_import_problems(False, True, self._empty(), self.EXPECTED)
        self.assertEqual(len(problems), 1)
        self.assertIn("not cut short", problems[0])

    def test_an_import_that_never_started_is_not_tested(self):
        """The tap is not evidence that the restore began."""
        problems = driver.interrupted_import_problems(True, False, self._empty(), self.EXPECTED)
        self.assertEqual(len(problems), 1)
        self.assertIn("never started", problems[0])

    def test_an_import_that_had_already_committed_is_a_problem(self):
        counts = dict(self.EXPECTED)
        problems = driver.interrupted_import_problems(True, True, counts, self.EXPECTED)
        self.assertEqual(len(problems), 1)
        self.assertIn("already committed", problems[0])

    def test_every_task_with_none_of_the_other_tables_is_part_of_the_dataset(self):
        """The tables are compared one by one, not the task count alone."""
        counts = self._empty()
        counts["tasks"] = self.EXPECTED["tasks"]
        problems = driver.interrupted_import_problems(True, True, counts, self.EXPECTED)
        self.assertEqual(len(problems), 1)
        self.assertIn("part of the dataset", problems[0])
        self.assertIn("projects", problems[0])

    def test_a_table_with_part_of_its_rows_is_part_of_the_dataset(self):
        counts = self._empty()
        counts["taskTags"] = 7
        problems = driver.interrupted_import_problems(True, True, counts, self.EXPECTED)
        self.assertEqual(len(problems), 1)
        self.assertIn("part of the dataset", problems[0])
        self.assertIn("7 of 300", problems[0])

    def test_a_table_that_could_not_be_read_is_not_an_empty_table(self):
        counts = self._empty()
        counts["tasks"] = -1
        problems = driver.interrupted_import_problems(True, True, counts, self.EXPECTED)
        self.assertEqual(len(problems), 1)
        self.assertIn("could not be read", problems[0])


class RestoreStartedTest(unittest.TestCase):
    """The interrupted import is only cut short when the app says it is working.

    The app's own indicator is the evidence that the restore began: the tap on
    the confirmation only says that Maestro dispatched a tap.
    """

    def _dump(self, *texts: str) -> str:
        nodes = "".join(f'<node text="{text}" />' for text in texts)
        return f"<?xml version='1.0' encoding='UTF-8'?><hierarchy>{nodes}</hierarchy>"

    def test_the_working_indicator_means_the_restore_began(self):
        with unittest.mock.patch.object(
            driver, "ui_dump", lambda serial: self._dump("Create backup", "Working…")
        ):
            started, evidence = driver.restore_started("serial", timeout=0)
        self.assertTrue(started)
        self.assertIn("working indicator", evidence)

    def test_the_indicator_is_not_confused_with_the_working_days_section(self):
        with unittest.mock.patch.object(
            driver, "ui_dump", lambda serial: self._dump("Working Days", "Mon", "Tue")
        ):
            started, _ = driver.restore_started("serial", timeout=0)
        self.assertFalse(started)

    def test_a_finished_restore_is_not_an_interrupted_one(self):
        with unittest.mock.patch.object(
            driver, "ui_dump", lambda serial: self._dump("Restore complete")
        ):
            started, evidence = driver.restore_started("serial", timeout=0)
        self.assertFalse(started)
        self.assertIn("finished", evidence)

    def test_a_screen_that_says_nothing_means_the_restore_never_began(self):
        with unittest.mock.patch.object(driver, "ui_dump", lambda serial: ""):
            started, evidence = driver.restore_started("serial", timeout=0)
        self.assertFalse(started)
        self.assertIn("never showed", evidence)


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


def _archive_with(tmp: Path, payload: dict) -> Path:
    """An archive with the given payload, as the generator writes it."""
    path = tmp / "synthetic.zip"
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("payload.json", json.dumps(payload))
    return path


#: A dataset with two tasks, one of them a subtask, one tag and one link.
def _payload() -> dict:
    return {
        "tasks": [
            {"id": "t1", "projectId": "p1", "parentTaskId": None},
            {"id": "t2", "projectId": "p1", "parentTaskId": "t1"},
        ],
        "taskTags": [{"taskId": "t1", "tagId": "g1"}],
        "taskDependencies": [{"taskId": "t2", "dependsOnTaskId": "t1"}],
    }


#: The same dataset, as it comes back from a restore.
def _restored_statements() -> list[str]:
    return [
        "INSERT INTO tasks (id, title, project_id, parent_task_id) "
        "VALUES ('t1', 'Task 1', 'p1', NULL)",
        "INSERT INTO tasks (id, title, project_id, parent_task_id) "
        "VALUES ('t2', 'Task 2', 'p1', 't1')",
        "INSERT INTO projects (id, name) VALUES ('p1', 'Project 1')",
        "INSERT INTO tags (id, name) VALUES ('g1', 'tag-1')",
        "INSERT INTO task_tags (task_id, tag_id) VALUES ('t1', 'g1')",
        "INSERT INTO task_dependencies (task_id, depends_on_task_id) VALUES ('t2', 't1')",
    ]


class LinkIdentityTest(unittest.TestCase):
    """The links have to be the dataset's links, not just as many of them.

    Counts cannot see a restore that shuffles the relations: the rows are the
    same rows, only they point at each other differently. The archive says
    which task belongs to which project, carries which tag and blocks which
    task — and the restored database has to say the same.
    """

    def test_the_links_of_the_archive_are_read(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            links = driver.payload_links(_archive_with(tmp, _payload()))
            self.assertEqual(links["parent"], {("t2", "t1")})
            self.assertEqual(links["project"], {("t1", "p1"), ("t2", "p1")})
            self.assertEqual(links["tags"], {("t1", "g1")})
            self.assertEqual(links["dependencies"], {("t2", "t1")})

    def test_a_restored_dataset_has_the_same_links(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            db = _database(tmp, _restored_statements())
            self.assertEqual(driver.link_identity_problems(
                driver.payload_links(_archive_with(tmp, _payload())),
                driver.restored_links(db),
            ), [])

    def test_a_lost_parent_is_a_lost_relation(self):
        """A subtask whose parent was dropped keeps every count."""
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            # The subtask is still there — it is only its parent that was
            # dropped, so every count of the dataset still holds.
            db = _database(tmp, [
                statement.replace("'p1', 't1')", "'p1', NULL)")
                for statement in _restored_statements()
            ])
            problems = driver.link_identity_problems(
                driver.payload_links(_archive_with(tmp, _payload())),
                driver.restored_links(db),
            )
            self.assertEqual(len(problems), 1)
            self.assertIn("parent link", problems[0])

    def test_a_link_that_is_not_in_the_dataset_is_a_problem(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            db = _database(tmp, _restored_statements() + [
                "INSERT INTO task_tags (task_id, tag_id) VALUES ('t2', 'g1')"
            ])
            problems = driver.link_identity_problems(
                driver.payload_links(_archive_with(tmp, _payload())),
                driver.restored_links(db),
            )
            self.assertEqual(len(problems), 1)
            self.assertIn("not in the dataset", problems[0])

    def test_a_task_without_its_project_is_a_lost_relation(self):
        """A task that lost its project keeps every count of the dataset."""
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            db = _database(tmp, [
                statement.replace("VALUES ('t1', 'Task 1', 'p1', NULL)",
                                  "VALUES ('t1', 'Task 1', NULL, NULL)")
                for statement in _restored_statements()
            ])
            problems = driver.link_identity_problems(
                driver.payload_links(_archive_with(tmp, _payload())),
                driver.restored_links(db),
            )
            self.assertEqual(len(problems), 1)
            self.assertIn("project link", problems[0])
            self.assertIn("are missing", problems[0])

    def test_a_task_without_its_tag_is_a_lost_relation(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            db = _database(tmp, [
                statement
                for statement in _restored_statements()
                if "INSERT INTO task_tags" not in statement
            ])
            problems = driver.link_identity_problems(
                driver.payload_links(_archive_with(tmp, _payload())),
                driver.restored_links(db),
            )
            self.assertEqual(len(problems), 1)
            self.assertIn("tags link", problems[0])
            self.assertIn("are missing", problems[0])

    def test_a_task_without_its_dependency_is_a_lost_relation(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            db = _database(tmp, [
                statement
                for statement in _restored_statements()
                if "INSERT INTO task_dependencies" not in statement
            ])
            problems = driver.link_identity_problems(
                driver.payload_links(_archive_with(tmp, _payload())),
                driver.restored_links(db),
            )
            self.assertEqual(len(problems), 1)
            self.assertIn("dependencies link", problems[0])
            self.assertIn("are missing", problems[0])


class DebugBuildTest(unittest.TestCase):
    """The procedure restores into the debug build, and says so in the report.

    The wipe it needs is a debug-only path, and the issue asks about the
    debug APK. An APK that is not a debug build — or a device that will not
    say which build it carries — is refused rather than measured.
    """

    def test_a_build_of_this_checkout_is_a_debug_one(self):
        self.assertEqual(driver.apk_variant(True), "debug")

    def test_an_apk_from_the_outside_has_no_known_variant(self):
        self.assertEqual(driver.apk_variant(False), "unknown")

    def test_a_device_that_says_is_believed(self):
        self.assertEqual(driver.apk_variant(False, True), "debug")
        self.assertEqual(driver.apk_variant(True, False), "release")


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


class DeviceClaimTest(unittest.TestCase):
    """One run per device — and a report that survives a run that never got far.

    The command drives the device on its own, so two runs on one device have to
    exclude each other: they kill each other's device server, and the run that
    loses says nothing true about the restore.

    And a run that dies early has still measured things — the archive, the
    import, the counts. A report that exists only for a run that got all the
    way through throws those measurements away exactly when they matter most:
    the 50k profile restores, and then the app crashes on the task list.
    """

    def _run(self, out_dir: Path, lock_dir: Path) -> int:
        """A run that cannot start: the APK it was told to install is not there."""
        argv = [
            "--serial",
            "test-device",
            "--apk",
            str(out_dir / "missing.apk"),
            "--out",
            str(out_dir),
        ]
        with unittest.mock.patch.dict(os.environ, {"MAESTRO_LOCK_DIR": str(lock_dir)}):
            with unittest.mock.patch.object(
                driver.capture, "resolve_device", lambda serial, devices: "test-device"
            ), unittest.mock.patch.object(
                driver.capture, "attached_devices", lambda: []
            ), unittest.mock.patch.object(
                driver, "device_facts", lambda serial: {"model": "test device"}
            ):
                return driver.main(argv)

    def _report(self, out_dir: Path) -> dict:
        import json

        return json.loads((out_dir / "report.json").read_text(encoding="utf-8"))

    def test_a_run_that_fails_early_still_writes_its_report(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            out = tmp / "out"
            out.mkdir()
            exit_code = self._run(out, tmp / "locks")
            self.assertEqual(exit_code, driver.EXIT_SETUP)
            report = self._report(out)
            self.assertEqual(report["status"], "failed")
            self.assertIn("does not exist", report["error"])
            self.assertEqual(report["phases"], ["device claimed"])

    def test_the_device_is_released_when_the_run_is_over(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            out = tmp / "out"
            out.mkdir()
            locks = tmp / "locks"
            self._run(out, locks)
            lock = locks / "singularity-maestro-test-device.lock"
            self.assertFalse(lock.exists())

    def test_a_device_held_by_another_run_is_refused(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            out = tmp / "out"
            out.mkdir()
            locks = tmp / "locks"
            locks.mkdir()
            # A live owner: this process, which is alive by definition.
            (locks / "singularity-maestro-test-device.lock").write_text(
                f"{os.getpid()}\n", encoding="utf-8"
            )
            exit_code = self._run(out, locks)
            self.assertEqual(exit_code, driver.EXIT_SETUP)
            report = self._report(out)
            self.assertEqual(report["status"], "failed")
            self.assertIn("already in use", report["error"])


class LateFailureReportTest(unittest.TestCase):
    """A run that dies after it has measured things still keeps the measurements.

    The early-failure test above covers a run that never started. This one
    covers the run that matters more: the 50k profile restores, the counts
    are read, and then the app crashes on the task list. The archive size, the
    import time and the counts are the measurements the report exists for —
    a run that throws them away at that moment has failed at exactly the
    point where its numbers are worth something.
    """

    def _patches(self, tmp: Path):
        """Every collaborator the run talks to, faked to a device that works.

        The run is driven to the last step — the Inbox — and there it fails:
        the flow does not open, which is what a crash on the task list looks
        like from the host.
        """
        archive = _archive_with(tmp, _payload())
        db = _database(tmp, _restored_statements())
        manifest = {
            "actualCounts": {
                "tasks": 2,
                "projects": 1,
                "tags": 1,
                "notes": 0,
                "checklistItems": 0,
                "taskDependencies": 1,
                "taskTags": 1,
            }
        }
        return [
            unittest.mock.patch.object(
                driver.capture, "resolve_device", lambda serial, devices: "test-device"
            ),
            unittest.mock.patch.object(driver.capture, "attached_devices", lambda: []),
            unittest.mock.patch.object(
                driver, "device_facts", lambda serial: {"model": "test device"}
            ),
            unittest.mock.patch.object(driver.capture, "install_apk", lambda serial, apk: None),
            unittest.mock.patch.object(
                driver.capture, "verify_identity", lambda serial, apk, built: ("pkg", "1.0")
            ),
            unittest.mock.patch.object(
                driver, "verify_installed_apk", lambda serial, apk: "md5:" + "0" * 32
            ),
            unittest.mock.patch.object(
                driver, "installed_app_is_debuggable", lambda serial: True
            ),
            unittest.mock.patch.object(driver, "database_version", lambda serial: None),
            unittest.mock.patch.object(
                driver,
                "generate_archive",
                lambda profile, seed, out: (archive, tmp / "sidecar.json", manifest),
            ),
            unittest.mock.patch.object(driver, "tool_versions", lambda serial: {}),
            unittest.mock.patch.object(
                driver, "reset_content", lambda serial, out, attempts=4, before_db=None: ({}, 1.0, "u1")
            ),
            unittest.mock.patch.object(
                driver,
                "check_over_budget",
                lambda serial, out, arc, apk=None, scope=None: {
                    "counts": {},
                    "problems": [],
                    "verdict": "refused",
                },
            ),
            unittest.mock.patch.object(
                driver,
                "check_interrupted_import",
                lambda serial, out, arc, apk=None, expected_counts=None, scope=None: {
                    "interrupted": True,
                    "importStarted": True,
                    "evidence": "the app's own working indicator was on the screen",
                    "counts": {"tasks": 0},
                    "problems": [],
                    "verdict": "empty",
                },
            ),
            unittest.mock.patch.object(driver, "push_archive", lambda serial, arc: "synthetic.zip"),
            unittest.mock.patch.object(
                driver,
                "restore_and_watch",
                lambda serial, name, log, timeout=1800, apk=None: {
                    "durationSeconds": 12.5,
                    "measured": "confirm-to-snackbar",
                    "reportedBySnackbar": True,
                },
            ),
            unittest.mock.patch.object(driver, "app_memory", lambda serial: "TOTAL PSS: 1"),
            unittest.mock.patch.object(driver, "pull_database", lambda serial, out: db),
            unittest.mock.patch.object(driver, "adb", lambda *a, **k: (0, "")),
            unittest.mock.patch.object(driver, "run_flow", lambda *a, **k: False),
        ]

    def test_the_measurements_survive_a_failure_late_in_the_run(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            out = tmp / "out"
            out.mkdir()
            apk = tmp / "the.apk"
            apk.write_bytes(b"not really an APK")
            argv = ["--serial", "test-device", "--apk", str(apk), "--out", str(out)]
            with unittest.mock.patch.dict(
                os.environ, {"MAESTRO_LOCK_DIR": str(tmp / "locks")}
            ):
                with contextlib.ExitStack() as stack:
                    for patch in self._patches(tmp):
                        stack.enter_context(patch)
                    exit_code = driver.main(argv)

            self.assertEqual(exit_code, driver.EXIT_SETUP)
            report = json.loads((out / "report.json").read_text(encoding="utf-8"))
            self.assertEqual(report["status"], "failed")
            self.assertIn("Inbox", report["error"])
            # The measurements the run had already taken are still in the report.
            self.assertEqual(report["import"]["durationSeconds"], 12.5)
            self.assertEqual(report["dataset"]["counts"]["tasks"], 2)
            self.assertEqual(report["database"]["counts"]["tasks"], 2)
            self.assertIn("counts compared", report["phases"])

    def test_the_readable_report_names_the_measurements_too(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            out = tmp / "out"
            out.mkdir()
            apk = tmp / "the.apk"
            apk.write_bytes(b"not really an APK")
            argv = ["--serial", "test-device", "--apk", str(apk), "--out", str(out)]
            with unittest.mock.patch.dict(
                os.environ, {"MAESTRO_LOCK_DIR": str(tmp / "locks")}
            ):
                with contextlib.ExitStack() as stack:
                    for patch in self._patches(tmp):
                        stack.enter_context(patch)
                    driver.main(argv)

            text = (out / "report.md").read_text(encoding="utf-8")
            self.assertIn("12.5 s", text)
            self.assertIn("| tasks | 2 | 2 |", text)


class FlowExitBeforeScreenTest(unittest.TestCase):
    """The screen is read only after the flow has let go of the device.

    Maestro writes the line about the confirmation tap before it performs the
    tap. A watcher that reads the screen right after that line takes the
    device's accessibility service away from Maestro's driver, the driver
    fails to start, and the watch reads a screen the flow is in the middle of
    changing.
    """

    def _watch(self, tmp: Path, flow_lets_go: bool):
        """Run the watch against a fake flow; return what happened, in order."""
        order: list[str] = []
        log = tmp / "restore-flow.log"
        log.write_text("", encoding="utf-8")

        class FakeProcess:
            def __init__(self):
                self.exited = False

            def poll(self):
                return 0 if self.exited else None

            def terminate(self):
                self.exited = True

            def wait(self, timeout=None):
                self.exited = True
                return 0

            def kill(self):
                self.exited = True

        process = FakeProcess()

        def fake_popen(command, **kwargs):
            # The flow writes the confirmation line and then keeps running.
            log.write_text(driver.CONFIRM_TAP + "\n", encoding="utf-8")
            return process

        def fake_wait(_process, timeout):
            order.append("waited for the flow")
            if flow_lets_go:
                process.exited = True
            return flow_lets_go

        def fake_dump(serial):
            order.append("read the screen")
            return f'<node text="{driver.SNACKBAR_ID}" />'

        with contextlib.ExitStack() as stack:
            stack.enter_context(
                unittest.mock.patch.object(driver, "flow_command", lambda *a, **k: ["maestro"])
            )
            stack.enter_context(unittest.mock.patch.object(driver, "wake_unlock", lambda serial: None))
            stack.enter_context(unittest.mock.patch.object(driver.subprocess, "Popen", fake_popen))
            stack.enter_context(unittest.mock.patch.object(driver, "wait_for_flow_exit", fake_wait))
            stack.enter_context(unittest.mock.patch.object(driver, "ui_dump", fake_dump))
            stack.enter_context(unittest.mock.patch.object(driver.time, "sleep", lambda seconds: None))
            outcome = driver._restore_and_watch_once("device", "archive.zip", log, timeout=30)
        return order, outcome

    def test_the_screen_is_read_after_the_flow_has_let_go(self):
        with tempfile.TemporaryDirectory() as raw:
            order, outcome = self._watch(Path(raw), flow_lets_go=True)
            self.assertEqual(order, ["waited for the flow", "read the screen"])
            self.assertFalse(outcome["flowOverlappedWithWatch"])

    def test_a_flow_that_does_not_let_go_is_recorded(self):
        with tempfile.TemporaryDirectory() as raw:
            _order, outcome = self._watch(Path(raw), flow_lets_go=False)
            self.assertTrue(outcome["flowOverlappedWithWatch"])


class OutputDirectoryTest(unittest.TestCase):
    """A run does not replace the measurements of an earlier run."""

    def test_an_empty_directory_holds_no_results(self):
        with tempfile.TemporaryDirectory() as raw:
            self.assertFalse(driver.output_dir_holds_results(Path(raw)))

    def test_a_directory_with_a_report_holds_results(self):
        with tempfile.TemporaryDirectory() as raw:
            out = Path(raw)
            (out / "report.json").write_text("{}", encoding="utf-8")
            self.assertTrue(driver.output_dir_holds_results(out))

    def test_a_directory_with_an_archive_holds_results(self):
        with tempfile.TemporaryDirectory() as raw:
            out = Path(raw)
            (out / "synthetic-1k.zip").write_bytes(b"")
            self.assertTrue(driver.output_dir_holds_results(out))

    def test_a_run_refuses_a_directory_that_already_holds_results(self):
        with tempfile.TemporaryDirectory() as raw:
            out = Path(raw)
            report = out / "report.json"
            report.write_text('{"status": "complete"}', encoding="utf-8")
            exit_code = driver.main(["--out", str(out)])
            self.assertEqual(exit_code, driver.EXIT_SETUP)
            # The earlier run's report is still there, untouched.
            self.assertEqual(report.read_text(encoding="utf-8"), '{"status": "complete"}')


class ProfileNameTest(unittest.TestCase):
    """A profile that carries a path never reaches the filesystem."""

    def test_the_archive_stays_inside_the_output_directory(self):
        with tempfile.TemporaryDirectory() as raw:
            out = Path(raw)
            archive, sidecar = driver.archive_paths("10k", out)
            self.assertEqual(archive.parent, out.resolve())
            self.assertEqual(sidecar.parent, out.resolve())

    def test_a_traversing_profile_is_refused(self):
        with tempfile.TemporaryDirectory() as raw:
            with self.assertRaises(driver.RunError):
                driver.archive_paths("x/../../../victim", Path(raw))

    def test_a_refused_profile_leaves_the_file_it_would_have_deleted_alone(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            out = root / "out"
            out.mkdir()
            # The name the run would have written to, had the profile been
            # allowed to carry a path.
            victim = root / "synthetic-victim.zip"
            victim.write_text("somebody else's archive", encoding="utf-8")
            with self.assertRaises(driver.RunError):
                driver.generate_archive("x/../../../synthetic-victim", 42, out)
            self.assertEqual(
                victim.read_text(encoding="utf-8"), "somebody else's archive"
            )


class OutputDirectoryReservationTest(unittest.TestCase):
    """One output directory belongs to one run at a time."""

    def test_a_second_run_cannot_claim_the_same_directory(self):
        with tempfile.TemporaryDirectory() as raw:
            out = Path(raw)
            self.assertTrue(driver.reserve_output_dir(out))
            self.assertFalse(driver.reserve_output_dir(out))
            driver.release_output_dir(out)
            self.assertTrue(driver.reserve_output_dir(out))

    def test_a_reservation_of_a_dead_run_is_taken_over(self):
        with tempfile.TemporaryDirectory() as raw:
            out = Path(raw)
            (out / driver.RESERVATION_FILE).write_text("424242", encoding="utf-8")
            with unittest.mock.patch("os.kill", side_effect=ProcessLookupError):
                self.assertTrue(driver.reserve_output_dir(out))

    def test_a_run_refuses_a_directory_another_run_holds(self):
        with tempfile.TemporaryDirectory() as raw:
            out = Path(raw)
            (out / driver.RESERVATION_FILE).write_text(str(os.getpid()), encoding="utf-8")
            exit_code = driver.main(["--out", str(out)])
            self.assertEqual(exit_code, driver.EXIT_SETUP)

    def test_the_report_is_written_while_the_directory_is_still_reserved(self):
        """The directory is given back only after the report is on disk.

        A run that released the reservation first would let another run claim
        the directory and start writing its archive into it while this run is
        still writing its report.
        """
        with tempfile.TemporaryDirectory() as raw:
            out = Path(raw)
            held: list[bool] = []
            original = driver.write_report

            def spy(out_dir: Path, report: dict) -> None:
                held.append((out_dir / driver.RESERVATION_FILE).exists())
                original(out_dir, report)

            with unittest.mock.patch.object(driver, "write_report", spy):
                driver.main(["--out", str(out)])
            self.assertEqual(held, [True])
            # And the directory is free again once the run is over.
            self.assertFalse((out / driver.RESERVATION_FILE).exists())


#: A dataset that carries the relations of the checklist items, the notes and
#: the projects — not only the ones of the tasks.
def _payload_with_more_links() -> dict:
    payload = _payload()
    payload["projects"] = [
        {"id": "p1", "name": "Project 1", "parentId": None},
        {"id": "p2", "name": "Project 2", "parentId": "p1"},
    ]
    payload["notes"] = [
        {"id": "n1", "title": "Note 1", "taskId": "t1", "projectId": None, "parentNoteId": None},
        {"id": "n2", "title": "Note 2", "taskId": None, "projectId": "p1", "parentNoteId": "n1"},
    ]
    payload["checklistItems"] = [
        {"id": "c1", "taskId": "t1"},
        {"id": "c2", "taskId": "t2"},
    ]
    return payload


#: The same dataset, as it comes back from a restore.
def _restored_statements_with_more_links() -> list[str]:
    return _restored_statements() + [
        "INSERT INTO projects (id, name, parent_id) VALUES ('p2', 'Project 2', 'p1')",
        "INSERT INTO notes (id, title, task_id, project_id, parent_note_id) "
        "VALUES ('n1', 'Note 1', 't1', NULL, NULL)",
        "INSERT INTO notes (id, title, task_id, project_id, parent_note_id) "
        "VALUES ('n2', 'Note 2', NULL, 'p1', 'n1')",
        "INSERT INTO checklist_items (id, task_id) VALUES ('c1', 't1')",
        "INSERT INTO checklist_items (id, task_id) VALUES ('c2', 't2')",
    ]


class MoreLinkIdentityTest(unittest.TestCase):
    """Every relation of the dataset is compared, not only the task's.

    A restore that moves a checklist item to another task, or a note to
    another project, keeps every count — only the relations tell. The
    comparison has to cover them, or the report claims the links survived
    while they did not.
    """

    def _links(self, tmp: Path, statements: list[str] | None = None):
        archive = _archive_with(tmp, _payload_with_more_links())
        db = _database(tmp, statements or _restored_statements_with_more_links())
        return driver.payload_links(archive), driver.restored_links(db)

    def test_the_links_of_the_checklist_the_notes_and_the_projects_are_read(self):
        with tempfile.TemporaryDirectory() as raw:
            links, _actual = self._links(Path(raw))
            self.assertEqual(links["checklist"], {("c1", "t1"), ("c2", "t2")})
            self.assertEqual(links["noteTask"], {("n1", "t1")})
            self.assertEqual(links["noteProject"], {("n2", "p1")})
            self.assertEqual(links["noteParent"], {("n2", "n1")})
            self.assertEqual(links["projectParent"], {("p2", "p1")})

    def test_a_restored_dataset_has_the_same_links(self):
        with tempfile.TemporaryDirectory() as raw:
            expected, actual = self._links(Path(raw))
            self.assertEqual(driver.link_identity_problems(expected, actual), [])

    def test_a_checklist_item_on_another_task_is_a_lost_relation(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            statements = [
                statement.replace("VALUES ('c2', 't2')", "VALUES ('c2', 't1')")
                for statement in _restored_statements_with_more_links()
            ]
            expected, actual = self._links(tmp, statements)
            problems = driver.link_identity_problems(expected, actual)
            # The relation is missing and a relation that is not in the
            # dataset is there instead: both are the same wrong link.
            self.assertEqual(len(problems), 2)
            self.assertTrue(any("checklist link" in problem for problem in problems))
            self.assertTrue(any("are missing" in problem for problem in problems))

    def test_a_note_on_another_project_is_a_lost_relation(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            statements = [
                statement.replace("VALUES ('n2', 'Note 2', NULL, 'p1', 'n1')",
                                  "VALUES ('n2', 'Note 2', NULL, 'p2', 'n1')")
                for statement in _restored_statements_with_more_links()
            ]
            expected, actual = self._links(tmp, statements)
            problems = driver.link_identity_problems(expected, actual)
            self.assertEqual(len(problems), 2)
            self.assertTrue(any("noteProject link" in problem for problem in problems))
            self.assertTrue(any("are missing" in problem for problem in problems))

    def test_a_project_outside_another_project_is_a_lost_relation(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            statements = [
                statement.replace("VALUES ('p2', 'Project 2', 'p1')",
                                  "VALUES ('p2', 'Project 2', NULL)")
                for statement in _restored_statements_with_more_links()
            ]
            expected, actual = self._links(tmp, statements)
            problems = driver.link_identity_problems(expected, actual)
            self.assertEqual(len(problems), 1)
            self.assertIn("projectParent link", problems[0])

    def test_a_note_without_its_task_is_a_lost_relation(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            statements = [
                statement.replace("VALUES ('n1', 'Note 1', 't1', NULL, NULL)",
                                  "VALUES ('n1', 'Note 1', NULL, NULL, NULL)")
                for statement in _restored_statements_with_more_links()
            ]
            expected, actual = self._links(tmp, statements)
            problems = driver.link_identity_problems(expected, actual)
            self.assertEqual(len(problems), 1)
            self.assertIn("noteTask link", problems[0])
            self.assertIn("are missing", problems[0])

    def test_a_note_without_its_parent_note_is_a_lost_relation(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            statements = [
                statement.replace("VALUES ('n2', 'Note 2', NULL, 'p1', 'n1')",
                                  "VALUES ('n2', 'Note 2', NULL, 'p1', NULL)")
                for statement in _restored_statements_with_more_links()
            ]
            expected, actual = self._links(tmp, statements)
            problems = driver.link_identity_problems(expected, actual)
            self.assertEqual(len(problems), 1)
            self.assertIn("noteParent link", problems[0])
            self.assertIn("are missing", problems[0])


class NegativePhaseExitCodeTest(unittest.TestCase):
    """A negative phase that finds something has to fail the run.

    The decision functions are tested on their own, and that is not enough:
    what matters is that the problem they return reaches the verdict and the
    exit code. A run that loses the problem on the way there would report a
    successful restore of a dataset the app refused or half wrote.
    """

    def _patches(self, tmp: Path, over_budget_problems: list[str],
                 interrupted_problems: list[str]):
        """Every collaborator of the run, faked to a device that works."""
        archive = _archive_with(tmp, _payload())
        db = _database(tmp, _restored_statements())
        manifest = {
            "actualCounts": {
                "tasks": 2,
                "projects": 1,
                "tags": 1,
                "notes": 0,
                "checklistItems": 0,
                "taskDependencies": 1,
                "taskTags": 1,
            }
        }
        return [
            unittest.mock.patch.object(
                driver.capture, "resolve_device", lambda serial, devices: "test-device"
            ),
            unittest.mock.patch.object(driver.capture, "attached_devices", lambda: []),
            unittest.mock.patch.object(
                driver, "device_facts", lambda serial: {"model": "test device"}
            ),
            unittest.mock.patch.object(driver.capture, "install_apk", lambda serial, apk: None),
            unittest.mock.patch.object(
                driver.capture, "verify_identity", lambda serial, apk, built: ("pkg", "1.0")
            ),
            unittest.mock.patch.object(
                driver, "verify_installed_apk", lambda serial, apk: "md5:" + "0" * 32
            ),
            unittest.mock.patch.object(
                driver, "installed_app_is_debuggable", lambda serial: True
            ),
            unittest.mock.patch.object(driver, "database_version", lambda serial: None),
            unittest.mock.patch.object(
                driver,
                "generate_archive",
                lambda profile, seed, out: (archive, tmp / "sidecar.json", manifest),
            ),
            unittest.mock.patch.object(driver, "tool_versions", lambda serial: {}),
            unittest.mock.patch.object(driver, "reset_content", lambda serial, out, attempts=4, before_db=None: ({}, 1.0, "u1")),
            unittest.mock.patch.object(
                driver,
                "check_over_budget",
                lambda serial, out, arc, apk=None, scope=None: {
                    "counts": {},
                    "problems": over_budget_problems,
                    "verdict": "refused",
                },
            ),
            unittest.mock.patch.object(
                driver,
                "check_interrupted_import",
                lambda serial, out, arc, apk=None, expected_counts=None, scope=None: {
                    "interrupted": True,
                    "importStarted": True,
                    "evidence": "the app's own working indicator was on the screen",
                    "counts": {"tasks": 0},
                    "problems": interrupted_problems,
                    "verdict": "empty",
                },
            ),
            unittest.mock.patch.object(driver, "push_archive", lambda serial, arc: "synthetic.zip"),
            unittest.mock.patch.object(
                driver,
                "restore_and_watch",
                lambda serial, name, log, timeout=1800, apk=None: {
                    "durationSeconds": 12.5,
                    "measured": "confirm-to-snackbar",
                    "reportedBySnackbar": True,
                },
            ),
            unittest.mock.patch.object(driver, "app_memory", lambda serial: "TOTAL PSS: 1"),
            unittest.mock.patch.object(driver, "pull_database", lambda serial, out: db),
            unittest.mock.patch.object(driver, "adb", lambda *a, **k: (0, "")),
            unittest.mock.patch.object(driver, "run_flow", lambda *a, **k: True),
            unittest.mock.patch.object(
                driver, "wait_for_task_row", lambda serial, timeout=180: "task_item_task_1"
            ),
            unittest.mock.patch.object(driver, "title_for_tag", lambda db_dir, tag, scope=None: "Task 1"),
            unittest.mock.patch.object(
                driver, "related_task", lambda db_dir, scope=None: ("t2", "Task 2")
            ),
            unittest.mock.patch.object(
                driver, "picker_candidate", lambda db_dir, current_id, scope=None: ("t3", "Task 3")
            ),
        ]

    def _run(self, tmp: Path, out: Path, over_budget_problems: list[str],
             interrupted_problems: list[str]) -> int:
        apk = tmp / "the.apk"
        apk.write_bytes(b"not really an APK")
        argv = ["--serial", "test-device", "--apk", str(apk), "--out", str(out)]
        with unittest.mock.patch.dict(
            os.environ, {"MAESTRO_LOCK_DIR": str(tmp / "locks")}
        ):
            with contextlib.ExitStack() as stack:
                for patch in self._patches(tmp, over_budget_problems, interrupted_problems):
                    stack.enter_context(patch)
                return driver.main(argv)

    def test_a_run_without_a_problem_passes(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            out = tmp / "out"
            out.mkdir()
            exit_code = self._run(tmp, out, [], [])
            self.assertEqual(exit_code, driver.EXIT_OK)

    def test_an_over_budget_problem_fails_the_run(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            out = tmp / "out"
            out.mkdir()
            exit_code = self._run(tmp, out, ["the archive was accepted"], [])
            self.assertEqual(exit_code, driver.EXIT_FAILED)
            report = json.loads((out / "report.json").read_text(encoding="utf-8"))
            self.assertIn(
                "over-budget: the archive was accepted",
                report["verdict"]["problems"],
            )

    def test_an_interrupted_import_problem_fails_the_run(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            out = tmp / "out"
            out.mkdir()
            exit_code = self._run(tmp, out, [], ["the import was already committed"])
            self.assertEqual(exit_code, driver.EXIT_FAILED)
            report = json.loads((out / "report.json").read_text(encoding="utf-8"))
            self.assertIn(
                "interrupted: the import was already committed",
                report["verdict"]["problems"],
            )


if __name__ == "__main__":
    unittest.main()
