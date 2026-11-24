"""Tests for measure-compose-frame-time.py — the parts that decide what a number means.

The command itself is a device scenario: it needs an attached phone or
emulator and a dataset that is already on it. What is worth testing here is
the arithmetic that turns the platform's output into the report:

* `frame_summary` reads the platform's own verdict — how many frames were
  rendered, how many of them missed their deadline — and not a number the
  script made up;
* `frame_durations` turns the per-frame table into the time each frame
  occupied the screen, and drops the rows that are not frames;
* `aggregate` reports medians over the runs, so one slow run cannot become
  the baseline;
* `require_frame_summary` refuses a run that counted no frames — a missing
  measurement is not a measurement of zero;
* `percentile` is the nearest-rank rule the report and the document quote;
* `choose_task_row` opens the task that carries the most of what the detail
  screen shows, and refuses to open a different task than the one asked for;
* `slug` reproduces the app's own test tag (`TestTags.slug`) — a slug that
  differs by one character makes the run open nothing;
* `node_bounds` finds the node a test tag names, with or without the
  package prefix the platform puts in front of it.

The file name has a dash in it, so the script is loaded by path — the way
the repository's own tests load it.
"""

import contextlib
import importlib.util
import io
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ElementTree
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent


def _load_driver():
    path = ROOT / "scripts" / "measure-compose-frame-time.py"
    spec = importlib.util.spec_from_file_location("measure_compose_frame_time", path)
    module = importlib.util.module_from_spec(spec)
    # The module has to be in `sys.modules` before it runs: a dataclass looks
    # its own module up there while it is being built.
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


driver = _load_driver()


GFXINFO_SUMMARY = """
Total frames rendered: 412
Janky frames: 96 (23.30%)
50th percentile: 12ms
90th percentile: 34ms
95th percentile: 61ms
99th percentile: 120ms
Number Missed Vsync: 3
Number High input latency: 0
Number Slow UI thread: 40
Number Slow bitmap uploads: 0
Number Slow issue draw commands: 2
"""

FRAMESTATS = """
---PROFILEDATA---
Flags,FrameTimelineVsyncId,IntendedVsync,Vsync,OldestInputEvent,NewestInputEvent,HandleInputStart,AnimationStart,PerformTraversalsStart,DrawStart,FrameDeadline,FrameStartTime,FrameInterval,SyncQueued,SyncStart,IssueDrawCommandsStart,SwapBuffers,FrameCompleted,DequeueBufferDuration,QueueBufferDuration,GpuCompleted
0,0,1000000000,1000000000,0,0,1000000000,0,1000000000,1000000000,1016666667,1000000000,16666666,1000000000,1000000000,1000000000,1000000000,1016000000,0,0,0
0,1,1016666667,1016666667,0,0,1016666667,0,1016666667,1016666667,1033333334,1016666667,16666666,1016666667,1016666667,1016666667,1016666667,1042000000,0,0,0
0,2,1033333334,1033333334,0,0,1033333334,0,1033333334,1033333334,1050000001,1033333334,16666666,1033333334,1033333334,1033333334,1033333334,1039000000,0,0,0
0,3,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0
"""

#: A node as the platform's dump writes it.
NODE = (
    '<node index="0" text="" resource-id="task_item_task_1" class="android.view.View" '
    'package="com.singularity.todo" content-desc="" checkable="false" checked="false" '
    'clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" '
    'long-clickable="false" password="false" selected="false" bounds="[12,340][1080,420]" />'
)

PREFIXED_NODE = NODE.replace('resource-id="task_item_task_1"', 'resource-id="com.singularity.todo:task_item_task_1"')


class FrameSummaryTest(unittest.TestCase):
    def test_reads_the_platform_verdict(self):
        summary = driver.parse_frame_summary(GFXINFO_SUMMARY)
        self.assertEqual(summary["totalFrames"], 412)
        self.assertEqual(summary["jankyFrames"], 96)
        self.assertEqual(summary["jankyPercent"], 23.30)
        self.assertEqual(summary["p50"], 12)
        self.assertEqual(summary["p95"], 61)
        self.assertEqual(summary["slowUiThread"], 40)

    def test_a_summary_without_the_lines_reports_nothing(self):
        summary = driver.parse_frame_summary("No process found for: com.singularity.todo")
        self.assertEqual(summary, {})


class FrameDurationsTest(unittest.TestCase):
    def test_each_frame_becomes_the_time_it_occupied_the_screen(self):
        durations = driver.parse_frame_durations(FRAMESTATS)
        self.assertEqual(len(durations), 3)
        self.assertAlmostEqual(durations[0], 16.0, places=3)
        self.assertAlmostEqual(durations[1], 25.333, places=2)
        self.assertAlmostEqual(durations[2], 5.666, places=2)

    def test_a_row_that_is_not_a_frame_is_dropped(self):
        # The fourth row of the table carries zeros: it is a frame that never
        # happened, and counting it would put a zero into the distribution.
        durations = driver.parse_frame_durations(FRAMESTATS)
        self.assertNotIn(0.0, durations)

    def test_a_table_without_the_marker_yields_nothing(self):
        self.assertEqual(driver.parse_frame_durations("no data here"), [])


class AggregateTest(unittest.TestCase):
    def test_medians_over_the_runs(self):
        samples = [
            {
                "totalFrames": 100,
                "jankyFrames": 10,
                "p50": 12,
                "p90": 30,
                "p95": 40,
                "measuredDurationsMs": [10.0, 12.0, 14.0],
            },
            {
                "totalFrames": 120,
                "jankyFrames": 30,
                "p50": 20,
                "p90": 50,
                "p95": 60,
                "measuredDurationsMs": [12.0, 20.0, 28.0],
            },
        ]
        summary = driver.aggregate(samples)
        self.assertEqual(summary["runs"], 2)
        self.assertEqual(summary["gfxinfoP50MedianMs"], 16)
        self.assertAlmostEqual(summary["measuredFramesMedianMs"], 13.0)
        self.assertAlmostEqual(summary["measuredFramesMaxMs"], 28.0)
        # 40 janky frames out of 220 rendered frames.
        self.assertAlmostEqual(summary["jankyPercentOverall"], 18.18, places=2)

    def test_no_runs_report_nothing(self):
        self.assertEqual(driver.aggregate([]), {})


class RequireFrameSummaryTest(unittest.TestCase):
    """A run that counted no frames is an error, not a zero.

    `dumpsys gfxinfo` prints nothing useful for a process that was not
    drawing. A report that stores such a run as a sample with zero frames
    states a fact about nothing — and the medians of the scenario then carry
    that nothing into the baseline.
    """

    def test_a_summary_without_the_frame_count_is_refused(self):
        with self.assertRaises(driver.MeasurementError):
            driver.require_frame_summary({"jankyFrames": 0}, "agenda-scroll")

    def test_a_run_that_counted_no_frames_is_refused(self):
        with self.assertRaises(driver.MeasurementError):
            driver.require_frame_summary({"totalFrames": 0, "jankyFrames": 0}, "agenda-scroll")

    def test_more_janky_frames_than_frames_is_refused(self):
        with self.assertRaises(driver.MeasurementError):
            driver.require_frame_summary({"totalFrames": 10, "jankyFrames": 11}, "agenda-scroll")

    def test_a_real_summary_passes_through(self):
        summary = driver.parse_frame_summary(GFXINFO_SUMMARY)
        self.assertIs(driver.require_frame_summary(summary, "agenda-scroll"), summary)


class PercentileTest(unittest.TestCase):
    """The percentile rule the report and the document quote.

    The nearest-rank rule: the p-th percentile is the value at rank
    `ceil(p * n)` of the sorted values, so every percentile that is reported
    is a value that was actually measured.
    """

    def test_the_nearest_rank_is_the_value_that_was_measured(self):
        values = [float(value) for value in range(1, 101)]
        self.assertEqual(driver.percentile(values, 0.95), 95)

    def test_a_short_sample_ends_at_its_last_value(self):
        self.assertEqual(driver.percentile([10.0, 20.0, 30.0], 0.95), 30.0)

    def test_no_values_are_no_percentile(self):
        self.assertIsNone(driver.percentile([], 0.95))

    def test_the_median_of_an_even_sample_is_the_mean_of_its_two_middle_values(self):
        samples = [
            {
                "totalFrames": 100,
                "jankyFrames": 0,
                "measuredDurationsMs": [10.0, 20.0, 30.0, 40.0],
            }
        ]
        summary = driver.aggregate(samples)
        self.assertAlmostEqual(summary["measuredFramesMedianMs"], 25.0)
        self.assertAlmostEqual(summary["measuredFramesP95Ms"], 40.0)


class ChooseTaskRowTest(unittest.TestCase):
    """The task the detail scenario opens.

    The first row of a sorted list is the emptiest one: a detail screen that
    holds nothing but a title says nothing about the cost of drawing a
    detail screen. So the row with the most relations wins — and a task that
    was named explicitly is opened by name or not at all.

    The rows are recognised by the tag the app made from the title: the
    tagged node of a row carries no text of its own.
    """

    ROWS = [("task_item_task_a", ""), ("task_item_task_b", "")]
    RELATIONS = {
        "Task A": {"children": 0, "tags": 1, "dependencies": 0, "notes": 0, "checklist": 0},
        "Task B": {"children": 3, "tags": 2, "dependencies": 1, "notes": 1, "checklist": 1},
    }

    def test_the_named_task_is_the_one_that_is_opened(self):
        tag, _ = driver.choose_task_row(self.ROWS, "Task A", self.RELATIONS)
        self.assertEqual(tag, "task_item_task_a")

    def test_a_named_task_that_is_not_on_the_screen_is_refused(self):
        with self.assertRaises(driver.MeasurementError):
            driver.choose_task_row(self.ROWS, "Task C", self.RELATIONS)

    def test_without_a_name_the_row_with_the_most_relations_wins(self):
        tag, _ = driver.choose_task_row(self.ROWS, "", self.RELATIONS)
        self.assertEqual(tag, "task_item_task_b")

    def test_an_agenda_without_a_row_is_refused(self):
        with self.assertRaises(driver.MeasurementError):
            driver.choose_task_row([], "", self.RELATIONS)

    def test_a_title_the_app_wrote_as_text_still_counts(self):
        rows = [("task_item_task_a", "Task A"), ("task_item_task_b", "Task B")]
        tag, _ = driver.choose_task_row(rows, "", self.RELATIONS)
        self.assertEqual(tag, "task_item_task_b")


class SwitchProfileTest(unittest.TestCase):
    """The profile name travels in the link, encoded.

    A profile name with a space in it breaks a link that is put together by
    hand: the app receives a truncated name and switches into no profile at
    all — silently, because the link still opens.
    """

    def test_the_profile_name_is_encoded_into_the_link(self):
        commands: list[str] = []
        original_adb = driver.adb_ok
        original_sleep = driver.time.sleep

        def fake_adb(*args, serial, timeout=120.0):
            commands.append(" ".join(args))
            return ""

        driver.adb_ok = fake_adb
        driver.time.sleep = lambda seconds: None
        try:
            driver.switch_profile(driver.Device("serial", "com.singularity.todo", 10, 10), "my profile")
        finally:
            driver.adb_ok = original_adb
            driver.time.sleep = original_sleep
        self.assertIn("profile=my+profile", commands[0])


class ArgumentsTest(unittest.TestCase):
    """The command line refuses the runs that would measure nothing."""

    def _refused(self, argv: list[str]) -> None:
        # argparse writes the usage it refused with to stderr; the test does
        # not need it, and the test output stays readable.
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                driver.parse_args(argv)

    def test_a_run_without_a_measurement_is_refused(self):
        self._refused(["--serial", "serial", "--runs", "0"])

    def test_a_negative_warm_up_is_refused(self):
        self._refused(["--serial", "serial", "--warmup", "-1"])

    def test_the_package_is_not_choosable(self):
        # The command measures this application; a flag that half of it
        # honoured was a contradiction waiting to be found.
        self._refused(["--serial", "serial", "--package", "com.example"])


class SlugTest(unittest.TestCase):
    def test_the_apps_own_slug(self):
        self.assertEqual(driver.slug("Project 1"), "project_1")
        self.assertEqual(driver.slug("Task 12"), "task_12")
        self.assertEqual(driver.slug("BackupRoundTripTask"), "backuproundtriptask")

    def test_runs_of_separators_become_one(self):
        self.assertEqual(driver.slug("a  --  b"), "a_b")

    def test_an_empty_title_is_not_empty(self):
        self.assertEqual(driver.slug("   "), "untitled")


def _nodes(xml: str) -> list:
    """The nodes of a UI dump, the way the run reads them."""
    return list(ElementTree.fromstring(xml).iter("node"))


class NodeBoundsTest(unittest.TestCase):
    def test_a_node_is_found_by_its_tag(self):
        bounds = driver.node_bounds(_nodes(NODE), "task_item_task_1")
        self.assertEqual(bounds, (12, 340, 1080, 420))

    def test_a_prefixed_resource_id_is_found_too(self):
        bounds = driver.node_bounds(_nodes(PREFIXED_NODE), "task_item_task_1")
        self.assertEqual(bounds, (12, 340, 1080, 420))

    def test_a_tag_that_is_not_there_is_not_found(self):
        self.assertIsNone(driver.node_bounds(_nodes(NODE), "task_item_other"))


class DatabaseCopyTest(unittest.TestCase):
    """The copy of the device's database has to be the whole database.

    A force-stop is a kill, not a clean close: the commits of the last
    writes are still in the write-ahead log, and a copy that leaves it
    behind is a copy of a database that is days old.
    """

    class _Proc:
        def __init__(self, returncode: int, stdout: bytes):
            self.returncode = returncode
            self.stdout = stdout

    def _copy(self, tmp: str, failing: set[str]) -> list[str]:
        copied: list[str] = []
        original = driver.subprocess.run

        def fake_run(cmd, **kwargs):
            name = cmd[-1]
            copied.append(name)
            if name in failing:
                return DatabaseCopyTest._Proc(1, b"")
            return DatabaseCopyTest._Proc(0, f"payload of {name}".encode())

        driver.subprocess.run = fake_run
        try:
            driver.copy_device_database("serial", Path(tmp) / "device.db")
        finally:
            driver.subprocess.run = original
        return copied

    def test_the_log_and_its_index_travel_with_the_database(self):
        with tempfile.TemporaryDirectory() as tmp:
            copied = self._copy(tmp, failing=set())
            self.assertEqual(
                copied,
                ["databases/todo.db", "databases/todo.db-wal", "databases/todo.db-shm"],
            )
            local = Path(tmp) / "device.db"
            self.assertTrue(local.is_file())
            self.assertTrue(local.with_name(local.name + "-wal").is_file())
            self.assertTrue(local.with_name(local.name + "-shm").is_file())

    def test_a_log_of_another_run_does_not_survive_a_failed_copy(self):
        with tempfile.TemporaryDirectory() as tmp:
            local = Path(tmp) / "device.db"
            local.with_name(local.name + "-wal").write_bytes(b"a log of another database")
            self._copy(tmp, failing={"databases/todo.db-wal"})
            self.assertFalse(local.with_name(local.name + "-wal").exists())

    def test_the_database_of_yesterday_does_not_survive_a_failed_copy(self):
        with tempfile.TemporaryDirectory() as tmp:
            local = Path(tmp) / "device.db"
            local.write_bytes(b"the database of yesterday")
            with self.assertRaises(driver.MeasurementError):
                self._copy(tmp, failing={"databases/todo.db"})
            self.assertFalse(local.exists())


class WakeDeviceTest(unittest.TestCase):
    """The screen is woken only when the device says it is asleep.

    Somebody's phone is not ours to touch for no reason, and a run that
    wakes a screen that is already on changes a device it was only asked
    to measure.
    """

    def _wake(self, wakefulness: str) -> list[str]:
        commands: list[str] = []
        original_adb = driver.adb
        original_ok = driver.adb_ok

        def fake_adb(*args, serial, timeout=120.0):
            commands.append(" ".join(args))
            return 0, f"mWakefulness={wakefulness}"

        def fake_ok(*args, serial, timeout=120.0):
            commands.append(" ".join(args))
            return ""

        driver.adb = fake_adb
        driver.adb_ok = fake_ok
        try:
            woken = driver.wake_device("serial")
        finally:
            driver.adb = original_adb
            driver.adb_ok = original_ok
        self.assertEqual(woken, wakefulness != "Awake")
        return commands

    def test_an_awake_device_is_left_alone(self):
        commands = self._wake("Awake")
        self.assertTrue(all("keyevent" not in command for command in commands))

    def test_a_sleeping_device_is_woken_and_unlocked(self):
        commands = self._wake("Asleep")
        self.assertTrue(any("KEYCODE_WAKEUP" in command for command in commands))
        self.assertTrue(any("dismiss-keyguard" in command for command in commands))

    def test_a_dozing_device_is_woken_too(self):
        # A dozing screen is dark as well: the dump of it fails with an
        # error that has nothing to do with the app.
        commands = self._wake("Dozing")
        self.assertTrue(any("KEYCODE_WAKEUP" in command for command in commands))


class DumpUiTest(unittest.TestCase):
    """A dump that fails once is retried; a dump that always fails is an error.

    The platform's dump fails on a busy screen and on a screen that is
    falling asleep. Both failures are about the moment, and a run that
    gives up on the first one throws away measurements it already made.
    """

    XML = '<?xml version="1.0" encoding="UTF-8"?><hierarchy><node resource-id="a"/></hierarchy>'

    def _dump(self, failures: int) -> list[str]:
        commands: list[str] = []
        remaining = failures
        original_adb = driver.adb
        original_ok = driver.adb_ok

        def fake_adb(*args, serial, timeout=120.0):
            nonlocal remaining
            commands.append(" ".join(args))
            if args[:2] == ("shell", "dumpsys"):
                return 0, "mWakefulness=Awake"
            if "uiautomator" in args:
                if remaining > 0:
                    remaining -= 1
                    return 1, "ERROR: could not get idle state"
                return 0, ""
            return 0, ""

        def fake_ok(*args, serial, timeout=120.0):
            commands.append(" ".join(args))
            return self.XML

        driver.adb = fake_adb
        driver.adb_ok = fake_ok
        try:
            nodes = driver.dump_ui("serial")
        finally:
            driver.adb = original_adb
            driver.adb_ok = original_ok
        self.assertEqual(len(nodes), 1)
        return commands

    def test_a_dump_that_fails_once_is_retried(self):
        commands = self._dump(failures=1)
        dumps = [command for command in commands if "uiautomator" in command]
        self.assertEqual(len(dumps), 2)

    def test_a_dump_that_always_fails_is_an_error(self):
        original_adb = driver.adb
        original_ok = driver.adb_ok

        def fake_adb(*args, serial, timeout=120.0):
            if args[:2] == ("shell", "dumpsys"):
                return 0, "mWakefulness=Awake"
            return 1, "ERROR: could not get idle state"

        driver.adb = fake_adb
        driver.adb_ok = lambda *args, serial, timeout=120.0: ""
        try:
            with self.assertRaises(driver.MeasurementError):
                driver.dump_ui("serial")
        finally:
            driver.adb = original_adb
            driver.adb_ok = original_ok


class DeviceLockTest(unittest.TestCase):
    """A device that somebody else is driving is refused, not measured.

    Two runs on one device kill each other's device server, so the
    measurement takes the repository's own lock and gives the device back
    when it is done.
    """

    def test_a_locked_device_is_refused(self):
        class Locked:
            def __init__(self, serial):
                pass

            def acquire(self):
                raise driver.DeviceLocked("serial", 4242)

        original = driver.DeviceLock
        driver.DeviceLock = Locked
        try:
            with self.assertRaises(driver.MeasurementError):
                driver.main(["--serial", "serial"])
        finally:
            driver.DeviceLock = original

    def test_the_lock_is_released_when_the_run_fails(self):
        released: list[bool] = []

        class Lock:
            def __init__(self, serial):
                pass

            def acquire(self):
                pass

            def release(self):
                released.append(True)

        original_lock = driver.DeviceLock
        original_measure = driver.measure
        driver.DeviceLock = Lock
        driver.measure = lambda args, out_dir: (_ for _ in ()).throw(
            driver.MeasurementError("the run failed")
        )
        try:
            with self.assertRaises(driver.MeasurementError):
                driver.main(["--serial", "serial"])
        finally:
            driver.DeviceLock = original_lock
            driver.measure = original_measure
        self.assertEqual(released, [True])


class SpotlightTest(unittest.TestCase):
    """The first-run tour is dismissed, because it swallows every tap.

    The tour dims the agenda and takes the taps under its scrim. A run
    that starts while it is up waits for a section that the scrim hides,
    and the wait ends in a timeout that says nothing about the app.
    """

    def _dismiss(self, on_screen: bool) -> list[str]:
        taps: list[str] = []
        original_dump = driver.dump_ui
        original_tap = driver.tap_tag

        def fake_dump(serial):
            if on_screen:
                return [{"resource-id": "onboarding_spotlight_card", "bounds": "[0,0][10,10]"}]
            return []

        def fake_tap(serial, tag, timeout=driver.NODE_TIMEOUT_SECONDS, **kwargs):
            taps.append(tag)

        driver.dump_ui = fake_dump
        driver.tap_tag = fake_tap
        try:
            dismissed = driver.dismiss_spotlight("serial")
        finally:
            driver.dump_ui = original_dump
            driver.tap_tag = original_tap
        self.assertEqual(dismissed, on_screen)
        return taps

    def test_a_tour_on_the_screen_is_dismissed(self):
        self.assertEqual(self._dismiss(True), ["onboarding_spotlight_skip"])

    def test_a_device_that_has_seen_the_tour_is_left_alone(self):
        self.assertEqual(self._dismiss(False), [])


class NavigateAgendaTest(unittest.TestCase):
    """The way to the agenda's rows is retried.

    A run can start while the app is still coming up, and the screen it
    sees then holds neither a row nor a section to expand. One attempt
    would fail the whole run on a screen that was never ready.
    """

    def _navigate(self, screens: list[list[dict]]) -> list[str]:
        """Drive `navigate_agenda` over a screen that changes per attempt."""
        attempts = list(screens)
        current: list[dict] = []
        opened: list[str] = []
        original_open = driver.open_app
        original_dump = driver.dump_ui
        original_tap = driver.tap_tag
        original_spotlight = driver.dismiss_spotlight
        original_pane = driver.wait_for_pane
        original_sleep = driver.time.sleep

        def fake_open(device):
            nonlocal current
            opened.append(device.serial)
            if attempts:
                current = attempts.pop(0)

        def fake_dump(serial):
            return current

        def fake_tap(serial, tag, timeout=driver.NODE_TIMEOUT_SECONDS, **kwargs):
            return None

        driver.open_app = fake_open
        driver.dump_ui = fake_dump
        driver.tap_tag = fake_tap
        driver.dismiss_spotlight = lambda serial: False
        driver.wait_for_pane = lambda serial, timeout=driver.NODE_TIMEOUT_SECONDS: (0, 0, 10, 10)
        driver.time.sleep = lambda seconds: None
        try:
            driver.navigate_agenda(driver.Device("serial", "pkg", 10, 10))
        finally:
            driver.open_app = original_open
            driver.dump_ui = original_dump
            driver.tap_tag = original_tap
            driver.dismiss_spotlight = original_spotlight
            driver.wait_for_pane = original_pane
            driver.time.sleep = original_sleep
        return opened

    def test_a_screen_that_is_not_ready_is_tried_again(self):
        ready = [{"resource-id": "task_item_task_1", "bounds": "[0,0][10,10]"}]
        opened = self._navigate([[], ready])
        self.assertEqual(len(opened), 2)

    def test_an_agenda_that_never_shows_a_row_is_an_error(self):
        original_open = driver.open_app
        original_dump = driver.dump_ui
        original_tap = driver.tap_tag
        original_spotlight = driver.dismiss_spotlight
        original_sleep = driver.time.sleep
        driver.open_app = lambda device: None
        driver.dump_ui = lambda serial: []
        driver.tap_tag = lambda serial, tag, timeout=driver.NODE_TIMEOUT_SECONDS, **kwargs: None
        driver.dismiss_spotlight = lambda serial: False
        driver.time.sleep = lambda seconds: None
        try:
            with self.assertRaises(driver.MeasurementError):
                driver.navigate_agenda(driver.Device("serial", "pkg", 10, 10))
        finally:
            driver.open_app = original_open
            driver.dump_ui = original_dump
            driver.tap_tag = original_tap
            driver.dismiss_spotlight = original_spotlight
            driver.time.sleep = original_sleep


class MarkdownTableTest(unittest.TestCase):
    """The report is also a table, so the document quotes the report.

    The numbers in the document are the numbers of the report: a second,
    hand-kept copy of them drifts, and a drifted baseline is worse than no
    baseline at all.
    """

    def test_a_scenario_becomes_a_row(self):
        table = driver.render_markdown(
            {
                "scenarios": {
                    "agenda-scroll": {
                        "summary": {
                            "runs": 5,
                            "framesPerRunMedian": 179,
                            "jankyFramesPerRunMedian": 5,
                            "jankyPercentOverall": 2.94,
                            "gfxinfoP50MedianMs": 14,
                            "gfxinfoP95MedianMs": 20,
                            "measuredFramesMedianMs": 13.75,
                            "measuredFramesP95Ms": 20.46,
                        }
                    }
                }
            }
        )
        self.assertIn("`agenda-scroll`", table)
        self.assertIn("179", table)
        self.assertIn("13.75", table)

    def test_a_report_without_scenarios_is_an_empty_table(self):
        table = driver.render_markdown({"scenarios": {}})
        self.assertEqual(len(table.strip().splitlines()), 2)


class TapTextTest(unittest.TestCase):
    """A control is tapped by its own bounds, not by the bounds of its text.

    The text of a button is often a thin line inside a much bigger
    clickable node, and a tap in the middle of that line can land on
    whatever lies under it.
    """

    def _tap(self, tree_xml: str) -> tuple[str, ...]:
        taps: list[str] = []
        original_tree = driver.dump_tree
        original_tap = driver.tap_bounds

        def fake_tree(serial):
            return ElementTree.fromstring(tree_xml)

        def fake_tap(serial, bounds):
            taps.append(",".join(str(value) for value in bounds))

        driver.dump_tree = fake_tree
        driver.tap_bounds = fake_tap
        try:
            driver.tap_text("serial", "See all")
        finally:
            driver.dump_tree = original_tree
            driver.tap_bounds = original_tap
        return tuple(taps)

    def test_the_clickable_ancestor_is_tapped(self):
        tree = (
            '<hierarchy><node clickable="true" bounds="[0,0][100,100]">'
            '<node text="See all 85 tasks" clickable="false" bounds="[10,20][60,29]" />'
            '</node></hierarchy>'
        )
        self.assertEqual(self._tap(tree), ("0,0,100,100",))

    def test_a_text_without_a_clickable_ancestor_is_tapped_by_its_own_bounds(self):
        tree = '<hierarchy><node text="See all" clickable="false" bounds="[10,20][60,29]" /></hierarchy>'
        self.assertEqual(self._tap(tree), ("10,20,60,29",))

    def test_a_text_that_is_not_on_the_screen_is_an_error(self):
        original_tree = driver.dump_tree
        original_sleep = driver.time.sleep
        driver.dump_tree = lambda serial: ElementTree.fromstring("<hierarchy />")
        driver.time.sleep = lambda seconds: None
        try:
            with self.assertRaises(driver.MeasurementError):
                driver.tap_text("serial", "See all", timeout=0.1)
        finally:
            driver.dump_tree = original_tree
            driver.time.sleep = original_sleep


class ScrollToTagTest(unittest.TestCase):
    """A node is found by scrolling the list to it, and the scroll gives up.

    The list of the dataset holds a hundred projects, and the card that is
    looked for is somewhere in the middle of them. A scroll that gives up
    early never reaches it.
    """

    def _scroll(self, screens: list[list[dict]]) -> list[str]:
        swipes: list[str] = []
        screens_left = list(screens)
        original_dump = driver.dump_ui
        original_tap = driver.tap_bounds
        original_swipe = driver.swipe
        original_sleep = driver.time.sleep

        def fake_dump(serial):
            return screens_left.pop(0) if screens_left else []

        def fake_tap(serial, bounds):
            swipes.append("tap")

        def fake_swipe(serial, x1, y1, x2, y2, duration_ms=300):
            swipes.append("swipe")

        driver.dump_ui = fake_dump
        driver.tap_bounds = fake_tap
        driver.swipe = fake_swipe
        driver.time.sleep = lambda seconds: None
        try:
            driver.scroll_to_tag("serial", "project_card_project_39")
        finally:
            driver.dump_ui = original_dump
            driver.tap_bounds = original_tap
            driver.swipe = original_swipe
            driver.time.sleep = original_sleep
        return swipes

    def test_the_list_is_scrolled_until_the_node_is_in_view(self):
        pane = {"resource-id": "", "bounds": "[0,0][100,1000]", "scrollable": "true"}
        first = [pane, {"resource-id": "project_card_a", "bounds": "[0,0][10,10]"}]
        second = [pane, {"resource-id": "project_card_b", "bounds": "[0,0][10,10]"}]
        found = [pane, {"resource-id": "project_card_project_39", "bounds": "[0,0][10,10]"}]
        self.assertEqual(self._scroll([first, second, found]), ["swipe", "swipe", "tap"])

    def test_a_list_that_stops_moving_gives_up(self):
        pane = [{"resource-id": "", "bounds": "[0,0][100,1000]", "scrollable": "true"}]
        original_dump = driver.dump_ui
        original_tap = driver.tap_bounds
        original_swipe = driver.swipe
        original_sleep = driver.time.sleep
        driver.dump_ui = lambda serial: pane
        driver.tap_bounds = lambda serial, bounds: None
        driver.swipe = lambda serial, x1, y1, x2, y2, duration_ms=300: None
        driver.time.sleep = lambda seconds: None
        try:
            with self.assertRaises(driver.MeasurementError):
                driver.scroll_to_tag("serial", "project_card_project_39")
        finally:
            driver.dump_ui = original_dump
            driver.tap_bounds = original_tap
            driver.swipe = original_swipe
            driver.time.sleep = original_sleep


class ForeignScreenTest(unittest.TestCase):
    """Another app on the screen is closed, and its frames are refused.

    The phone is a real phone, and its browser opens an advertisement on its
    own schedule. Such a screen is not the screen the run measures, and the
    frames of the stretch it covered are the advertisement's frames.
    """

    FOCUS_OUTPUT = (
        "  mCurrentFocus=Window{d8e170 u0 com.singularity.todo/"
        "com.singularity.todo.MainActivity}, [type=1]"
    )

    def test_the_package_of_the_screen_is_read(self):
        original = driver.adb_ok

        driver.adb_ok = lambda *args, serial, timeout=120.0: self.FOCUS_OUTPUT
        try:
            self.assertEqual(driver.focus_package("serial"), "com.singularity.todo")
        finally:
            driver.adb_ok = original

    def test_a_foreign_screen_is_closed_and_the_app_is_brought_back(self):
        owners = ["com.heytap.browser", "com.singularity.todo"]
        keys: list[str] = []
        original_focus = driver.focus_package
        original_adb = driver.adb_ok
        original_sleep = driver.time.sleep

        def fake_focus(serial):
            return owners[0] if len(owners) > 1 else owners.pop(0)

        def fake_adb(*args, serial, timeout=120.0):
            keys.append(" ".join(args))
            if args[:3] == ("shell", "input", "keyevent"):
                owners.clear()
                owners.append("com.singularity.todo")
            return ""

        driver.focus_package = fake_focus
        driver.adb_ok = fake_adb
        driver.time.sleep = lambda seconds: None
        try:
            driver.ensure_foreground(driver.Device("serial", "com.singularity.todo", 10, 10))
        finally:
            driver.focus_package = original_focus
            driver.adb_ok = original_adb
            driver.time.sleep = original_sleep
        self.assertTrue(any("KEYCODE_BACK" in key for key in keys))

    def test_a_screen_that_stays_foreign_is_an_error(self):
        original_focus = driver.focus_package
        original_adb = driver.adb_ok
        original_sleep = driver.time.sleep
        driver.focus_package = lambda serial: "com.heytap.browser"
        driver.adb_ok = lambda *args, serial, timeout=120.0: ""
        driver.time.sleep = lambda seconds: None
        try:
            with self.assertRaises(driver.MeasurementError):
                driver.ensure_foreground(driver.Device("serial", "com.singularity.todo", 10, 10))
        finally:
            driver.focus_package = original_focus
            driver.adb_ok = original_adb
            driver.time.sleep = original_sleep

    def test_a_wait_survives_a_foreign_screen(self):
        """The node is still coming; it is only behind an advertisement."""
        foreign = ["com.heytap.browser"]
        keys: list[str] = []
        original_dump = driver.dump_ui
        original_focus = driver.focus_package
        original_adb = driver.adb_ok
        original_sleep = driver.time.sleep

        def fake_focus(serial):
            return foreign[0] if foreign else "com.singularity.todo"

        def fake_adb(*args, serial, timeout=120.0):
            keys.append(" ".join(args))
            if args[:3] == ("shell", "input", "keyevent"):
                foreign.clear()
            return ""

        def fake_dump(serial):
            if foreign:
                return []
            return [
                {"resource-id": "com.singularity.todo:id/task_editor_title_input",
                 "bounds": "[0,0][10,10]"}
            ]

        driver.dump_ui = fake_dump
        driver.focus_package = fake_focus
        driver.adb_ok = fake_adb
        driver.time.sleep = lambda seconds: None
        try:
            bounds = driver.wait_for_tag("serial", "task_editor_title_input")
        finally:
            driver.dump_ui = original_dump
            driver.focus_package = original_focus
            driver.adb_ok = original_adb
            driver.time.sleep = original_sleep
        self.assertEqual(bounds, (0, 0, 10, 10))
        self.assertTrue(any("KEYCODE_BACK" in key for key in keys))


class WaitForPaneTest(unittest.TestCase):
    """A screen that is still composing has no scrollable node yet.

    The list of a hundred project cards appears after the tap, and a run that
    looks for the pane before the list is composed finds nothing and gives up
    on a screen that was on its way.
    """

    def test_the_pane_is_waited_for(self):
        pane = {"resource-id": "", "bounds": "[0,0][100,1000]", "scrollable": "true"}
        screens = [[], [], [pane]]
        original_dump = driver.dump_ui
        original_sleep = driver.time.sleep

        def fake_dump(serial):
            return screens.pop(0) if screens else []

        driver.dump_ui = fake_dump
        driver.time.sleep = lambda seconds: None
        try:
            bounds = driver.wait_for_pane("serial")
        finally:
            driver.dump_ui = original_dump
            driver.time.sleep = original_sleep
        self.assertEqual(bounds, (0, 0, 100, 1000))

    def test_a_screen_that_never_scrolls_is_an_error(self):
        original_dump = driver.dump_ui
        original_sleep = driver.time.sleep
        driver.dump_ui = lambda serial: []
        driver.time.sleep = lambda seconds: None
        try:
            with self.assertRaises(driver.MeasurementError):
                driver.wait_for_pane("serial", timeout=0.0)
        finally:
            driver.dump_ui = original_dump
            driver.time.sleep = original_sleep


if __name__ == "__main__":
    unittest.main()
