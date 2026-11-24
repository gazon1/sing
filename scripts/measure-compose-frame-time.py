#!/usr/bin/env python3
"""Measure Android frame times for the app's primary screens.

One command, one named device, one dataset that is already on the device:

    python3 scripts/measure-compose-frame-time.py --serial emulator-5554 \
        --profile-name verify-large-restore

WHAT IT DOES

    1. switches the app into the profile that holds the dataset (the debug
       seed link carries the profile name, so the switch is the app's own
       path, not a write into its database);
    2. drives four representative interactions — the Upcoming timeline, a
       project's task list, a task's detail, the calendar's periods — and
       records the frame times the platform reports for each of them;
    3. repeats every interaction after a warm-up and writes the medians, the
       sample counts and the raw runs into a JSON report and a Markdown
       table.

WHAT IT DOES NOT DO

    It does not pick a device. The serial is required: one attached device
    is as likely to be somebody's phone as the test AVD, and this command
    drives whatever it is pointed at.

    It does not restore anything. The dataset is prepared by
    `scripts/verify-large-restore.py`, which restores it through the app's
    own backup path; this command measures a device that already holds it
    and says so in the report when it does not.

    It does not claim Android's frame metrics mean anything on the desktop.
    The desktop has no equivalent, so the desktop numbers come from a
    different procedure and are reported next to, not inside, these.

WHERE THE NUMBERS COME FROM

    `dumpsys gfxinfo <package>` — the platform's own frame accounting for
    the app's process: how many frames were rendered, how many of them
    missed their deadline, and the percentiles of the frame times. The
    per-frame table (`gfxinfo <package> framestats`) is read as well, so
    the medians in the report are computed here and not taken on faith.

    A frame is janky when it missed its deadline: the platform's own
    verdict, from the summary, is the one the report carries, because the
    deadline depends on the display's refresh rate and only the platform
    knows it.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import statistics
import subprocess
import sys
import time
import xml.etree.ElementTree as ElementTree
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# The repository's own device lock: one run per device, and the same lock
# file the certified suite takes, so this command and a qualified Maestro
# run exclude each other instead of driving the same phone at once.
_SCRIPTS_SRC = str(ROOT / "scripts" / "src")
if _SCRIPTS_SRC not in sys.path:
    sys.path.insert(0, _SCRIPTS_SRC)

from emulator.maestro._lock import DeviceLock, DeviceLocked  # noqa: E402

APP_ID = "com.singularity.todo"
MAIN_ACTIVITY = "com.singularity.todo/.MainActivity"
DEBUG_SEED_SCHEME = "todo-debug://seed"

#: How long a node may take to appear before the run gives up on it.
NODE_TIMEOUT_SECONDS = 30.0

#: How long the app is given to settle after an interaction before the
#: frames are read. A frame that is still being produced when the frames
#: are read is a frame that is counted without its duration, so the wait
#: has to outlast the last frame of the interaction.
SETTLE_SECONDS = 1.5

#: How many times a failing UI dump is retried. The platform's dump fails
#: on a busy screen and on a screen that is falling asleep, and neither
#: failure says anything about the app.
DUMP_ATTEMPTS = 3

#: How many times the way to the agenda's rows is tried. The app can be
#: still coming up, or covered by the first-run tour, when a run starts.
AGENDA_ATTEMPTS = 5

#: How many times a list is scrolled to find a node in it. The dataset
#: holds a hundred projects, and the card that is looked for is somewhere
#: in the middle of them.
SCROLL_ATTEMPTS = 40

#: How many times the run takes the screen back from another app. The
#: phone's browser opens an advertisement on its own schedule, and that
#: screen is not the screen the run measures.
FOREGROUND_ATTEMPTS = 5


class MeasurementError(RuntimeError):
    """The run cannot go on."""


# ── the device ──────────────────────────────────────────────────────────────


def adb(*args: str, serial: str, timeout: float = 120.0) -> tuple[int, str]:
    """Run one adb command; return its exit code and its output."""
    proc = subprocess.run(
        ["adb", "-s", serial, *args],
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    return proc.returncode, proc.stdout + proc.stderr


def adb_ok(*args: str, serial: str, timeout: float = 120.0) -> str:
    """Run one adb command that has to succeed."""
    code, out = adb(*args, serial=serial, timeout=timeout)
    if code != 0:
        raise MeasurementError(f"adb {' '.join(args)} failed:\n{out.strip()}")
    return out


def screen_size(serial: str) -> tuple[int, int]:
    """The device's pixel size, as (width, height)."""
    out = adb_ok("shell", "wm", "size", serial=serial)
    match = re.search(r"(\d+)x(\d+)", out)
    if not match:
        raise MeasurementError(f"the device did not report its screen size:\n{out.strip()}")
    return int(match.group(1)), int(match.group(2))


def wake_device(serial: str) -> bool:
    """Wake the screen, if the device is not awake.

    A sleeping device shows nothing: the run waits for a node that the
    screen does not have, and the wait ends in a timeout that says
    nothing about the app. A dozing device (the screen is off, but the
    device is still in the doze state) fails the same way, only later:
    the first runs pass, and the dump that follows them fails.

    The wake is done only when the device says it is not awake, and it
    changes nothing else about the device — the repository's own device
    preparation leaves somebody's phone untouched for the same reason.
    """
    code, out = adb("shell", "dumpsys", "power", serial=serial)
    if code != 0 or "mWakefulness=Awake" in out:
        return False
    adb_ok("shell", "input", "keyevent", "KEYCODE_WAKEUP", serial=serial)
    adb_ok("shell", "wm", "dismiss-keyguard", serial=serial)
    time.sleep(1.0)
    return True


def dump_ui(serial: str) -> list[dict]:
    """The screen's semantics tree, as a list of node dictionaries.

    The dump is retried: the platform's dump fails on a screen that is
    busy or on one that is falling asleep, and both failures are about the
    moment, not about the app.
    """
    return list(dump_tree(serial).iter("node"))


def dump_tree(serial: str) -> ElementTree.Element:
    """The screen's semantics tree, as it is.

    The tree is kept as a tree because a node's clickable ancestor is not
    in the flat list of the nodes: it is its parent.
    """
    last_error = ""
    for _ in range(DUMP_ATTEMPTS):
        # The screen can doze off between two calls, and a dump of a dark
        # screen fails with an error that says nothing about the app.
        wake_device(serial)
        code, out = adb(
            "shell", "uiautomator", "dump", "/sdcard/frame-time-dump.xml", serial=serial
        )
        if code == 0:
            raw = adb_ok("exec-out", "cat", "/sdcard/frame-time-dump.xml", serial=serial)
            start = raw.find("<?xml")
            if start >= 0:
                return ElementTree.fromstring(raw[start:])
            last_error = "the UI dump carried no XML"
        else:
            last_error = out.strip()
        time.sleep(1.0)
    raise MeasurementError(f"the UI dump failed: {last_error}")


def parse_bounds(text: str) -> tuple[int, int, int, int] | None:
    """The bounds of a node, as (left, top, right, bottom)."""
    match = re.match(r"\[(\d+),(\d+)]\[(\d+),(\d+)]", text)
    if not match:
        return None
    left, top, right, bottom = (int(value) for value in match.groups())
    return left, top, right, bottom


def node_bounds(nodes: list[dict], tag: str) -> tuple[int, int, int, int] | None:
    """The bounds of the node whose resource-id is the tag, if it is there.

    The resource-id is matched on its tail: Compose's `testTagsAsResourceId`
    publishes the test tag itself, and some platform versions prefix it
    with the package name.
    """
    for node in nodes:
        resource_id = node.get("resource-id", "")
        if resource_id == tag or resource_id.endswith("/" + tag) or resource_id.endswith(":" + tag):
            bounds = parse_bounds(node.get("bounds", ""))
            if bounds:
                return bounds
    return None


def node_bounds_by_desc(nodes: list[dict], desc: str) -> tuple[int, int, int, int] | None:
    """The bounds of the node with the content description, if it is there."""
    for node in nodes:
        if node.get("content-desc") == desc:
            bounds = parse_bounds(node.get("bounds", ""))
            if bounds:
                return bounds
    return None


def scrollable_bounds(nodes: list[dict]) -> tuple[int, int, int, int] | None:
    """The bounds of the largest scrollable node on the screen.

    This is where the finger has to be. The app lays itself out differently
    on a phone and on a tablet — on the tablet the navigation is a rail on
    the left and the list is the pane beside it — so a swipe at fixed
    coordinates lands on whatever happens to be there, which on the summary
    screen is nothing that scrolls.
    """
    best: tuple[int, int, int, int] | None = None
    best_area = 0
    for node in nodes:
        if node.get("scrollable") != "true":
            continue
        bounds = parse_bounds(node.get("bounds", ""))
        if not bounds:
            continue
        area = (bounds[2] - bounds[0]) * (bounds[3] - bounds[1])
        if area > best_area:
            best, best_area = bounds, area
    return best


def visible_task_tags(nodes: list[dict]) -> set[str]:
    """The task rows that are on the screen right now."""
    return {
        node.get("resource-id", "").split("/")[-1]
        for node in nodes
        if "task_item_" in node.get("resource-id", "")
    }


def wait_for_tag(
    serial: str,
    tag: str,
    timeout: float = NODE_TIMEOUT_SECONDS,
    package: str = APP_ID,
) -> tuple[int, int, int, int]:
    """Wait until the node with the tag is on the screen; return its bounds.

    Another app can take the screen while the wait runs — the phone's
    browser opens an advertisement on its own schedule. Such a screen is
    closed with the back key, and the wait goes on: the node is still
    coming, it is only behind an advertisement now.
    """
    deadline = time.monotonic() + timeout
    while True:
        bounds = node_bounds(dump_ui(serial), tag)
        if bounds:
            return bounds
        if time.monotonic() > deadline:
            raise MeasurementError(f"the node '{tag}' did not appear within {timeout:.0f}s")
        owner = focus_package(serial)
        if owner and owner != package:
            adb_ok("shell", "input", "keyevent", "KEYCODE_BACK", serial=serial)
            time.sleep(1.0)
            continue
        time.sleep(0.5)


def tap_tag(
    serial: str,
    tag: str,
    timeout: float = NODE_TIMEOUT_SECONDS,
    package: str = APP_ID,
) -> None:
    """Tap the node with the tag."""
    left, top, right, bottom = wait_for_tag(serial, tag, timeout, package)
    adb_ok(
        "shell",
        "input",
        "tap",
        str((left + right) // 2),
        str((top + bottom) // 2),
        serial=serial,
    )


def dismiss_spotlight(serial: str) -> bool:
    """Dismiss the first-run spotlight tour, if it is on the screen.

    The tour dims the agenda and swallows every tap under its scrim, so a
    run that drives the agenda drives nothing at all: the section it is
    waiting for is not on the screen, and the wait ends in a timeout that
    says nothing about the app. The tour is armed on a device that has
    never seen it, and it is dismissed once per device.
    """
    if not node_bounds(dump_ui(serial), "onboarding_spotlight_card"):
        return False
    tap_tag(serial, "onboarding_spotlight_skip")
    time.sleep(1.0)
    return True


def tap_text(serial: str, prefix: str, timeout: float = NODE_TIMEOUT_SECONDS) -> None:
    """Tap the control whose text starts with `prefix`.

    The tap goes to the control, not to the text: the text of a button is
    often a thin line inside a much bigger clickable node, and a tap in
    the middle of that line can land on whatever lies under it. A tap on
    the line itself opened a browser once — the text was not the control,
    and the click fell through to a link that lay behind it.

    A control that has no clickable ancestor is tapped by its own bounds:
    there is nothing else to aim at.
    """
    deadline = time.monotonic() + timeout
    while True:
        root = dump_tree(serial)
        parents = {child: parent for parent in root.iter() for child in parent}
        for node in root.iter("node"):
            if not node.get("text", "").startswith(prefix):
                continue
            bounds = parse_bounds(node.get("bounds", ""))
            if not bounds:
                continue
            target = node
            while target is not None and target.get("clickable") != "true":
                target = parents.get(target)
            target_bounds = parse_bounds(target.get("bounds", "")) if target is not None else bounds
            tap_bounds(serial, target_bounds or bounds)
            return
        if time.monotonic() > deadline:
            raise MeasurementError(f"no control with the text '{prefix}' appeared within {timeout:.0f}s")
        time.sleep(0.5)


def tap_tag_if_present(serial: str, tag: str) -> bool:
    """Tap the node with the tag if it is on the screen right now."""
    bounds = node_bounds(dump_ui(serial), tag)
    if not bounds:
        return False
    left, top, right, bottom = bounds
    adb_ok(
        "shell",
        "input",
        "tap",
        str((left + right) // 2),
        str((top + bottom) // 2),
        serial=serial,
    )
    return True


def swipe(serial: str, x1: int, y1: int, x2: int, y2: int, duration_ms: int = 300) -> None:
    """One swipe gesture."""
    adb_ok(
        "shell",
        "input",
        "swipe",
        str(x1),
        str(y1),
        str(x2),
        str(y2),
        str(duration_ms),
        serial=serial,
    )


# ── the frames ──────────────────────────────────────────────────────────────


def reset_frames(serial: str, package: str) -> None:
    """Drop the frames the platform has counted so far."""
    adb_ok("shell", "dumpsys", "gfxinfo", package, "reset", serial=serial)


def frame_summary(serial: str, package: str) -> dict:
    """The platform's own summary of the frames it has counted."""
    return parse_frame_summary(adb_ok("shell", "dumpsys", "gfxinfo", package, serial=serial))


def parse_frame_summary(text: str) -> dict:
    """Read the platform's frame summary out of the text of `gfxinfo`."""
    summary: dict = {}
    for key, pattern in (
        ("totalFrames", r"Total frames rendered:\s*(\d+)"),
        ("jankyFrames", r"Janky frames:\s*(\d+)"),
        ("jankyPercent", r"Janky frames:\s*\d+\s*\(([\d.]+)%\)"),
        ("p50", r"50th percentile:\s*(\d+)ms"),
        ("p90", r"90th percentile:\s*(\d+)ms"),
        ("p95", r"95th percentile:\s*(\d+)ms"),
        ("p99", r"99th percentile:\s*(\d+)ms"),
        ("missedVsync", r"Number Missed Vsync:\s*(\d+)"),
        ("slowUiThread", r"Number Slow UI thread:\s*(\d+)"),
        ("slowDrawCommands", r"Number Slow issue draw commands:\s*(\d+)"),
    ):
        match = re.search(pattern, text)
        if match:
            summary[key] = float(match.group(1)) if "." in match.group(1) else int(match.group(1))
    return summary


def frame_durations(serial: str, package: str) -> list[float]:
    """The duration of every frame the platform has timed, in milliseconds."""
    return parse_frame_durations(
        adb_ok("shell", "dumpsys", "gfxinfo", package, "framestats", serial=serial)
    )


def parse_frame_durations(text: str) -> list[float]:
    """The time each frame occupied the screen, in milliseconds.

    The duration is the distance from the frame's intended vsync to the
    moment it was finished. A row that carries no frame — the zeros the
    platform writes for a frame that never happened — is dropped: a zero in
    the distribution would be a frame that took no time, and there was no
    such frame.
    """
    marker = "---PROFILEDATA---"
    if marker not in text:
        return []
    header_line = text.split(marker, 1)[1].strip().splitlines()
    if not header_line:
        return []
    header = header_line[0].split(",")
    try:
        intended = header.index("IntendedVsync")
        completed = header.index("FrameCompleted")
    except ValueError:
        return []
    durations: list[float] = []
    for line in header_line[1:]:
        cells = line.split(",")
        if len(cells) <= max(intended, completed):
            continue
        try:
            start = int(cells[intended])
            end = int(cells[completed])
        except ValueError:
            continue
        if start <= 0 or end <= 0 or end < start:
            continue
        durations.append((end - start) / 1_000_000.0)
    return durations


# ── the device's facts ──────────────────────────────────────────────────────


def device_fingerprint(serial: str) -> str:
    """A stable, non-identifying name for the device.

    The serial number identifies somebody's phone; the report is committed to
    the repository, so the device is named by a fingerprint instead.
    """
    return hashlib.sha256(serial.encode("utf-8")).hexdigest()[:12]


def app_version(serial: str) -> str:
    """The version name of the app that is installed on the device.

    The version name carries the commit the build was made from, so it says
    which build a run measured. The device is shared, and another run can
    put another build on it between two measurements.
    """
    code, out = adb("shell", "dumpsys", "package", APP_ID, serial=serial)
    if code != 0:
        return ""
    match = re.search(r"versionName=(\S+)", out)
    return match.group(1) if match else ""


def device_facts(serial: str) -> dict:
    """What the device is, for the report."""
    def prop(name: str) -> str:
        code, out = adb("shell", "getprop", name, serial=serial)
        return out.strip() if code == 0 else ""

    code, out = adb("shell", "dumpsys", "package", APP_ID, serial=serial)
    version = ""
    debuggable = False
    if code == 0:
        match = re.search(r"versionName=(\S+)", out)
        if match:
            version = match.group(1)
        debuggable = "DEBUGGABLE" in out
    return {
        "fingerprint": device_fingerprint(serial),
        "model": prop("ro.product.model"),
        "manufacturer": prop("ro.product.manufacturer"),
        "androidVersion": prop("ro.build.version.release"),
        "sdk": prop("ro.build.version.sdk"),
        "abi": prop("ro.product.cpu.abi"),
        "appVersionName": version,
        "appDebuggable": debuggable,
    }


def profile_scope_facts(connection, profile_name: str) -> dict:
    """What one profile holds, out of the database the device keeps.

    The app keeps every profile in the same database file, one user scope per
    profile. The counts of the whole file therefore say how much the device
    has ever stored, not how much the profile under measurement holds — and
    the second number is the one the measurement is about.
    """
    facts: dict = {}
    try:
        row = connection.execute(
            "select id from profiles where name = ?",
            (profile_name,),
        ).fetchone()
    except Exception:  # noqa: BLE001 — the report says what it could not read
        row = None
    if not row:
        facts["profileError"] = f"the profile '{profile_name}' is not in the database"
        return facts
    profile_id = row[0]
    scope = profile_id + "/%"
    facts["profileId"] = profile_id
    facts["tasks"] = connection.execute(
        "select count(*) from tasks where user_id like ?",
        (scope,),
    ).fetchone()[0]
    facts["projects"] = connection.execute(
        "select count(*) from projects where user_id like ?",
        (scope,),
    ).fetchone()[0]
    busiest = connection.execute(
        "select p.name, count(t.id) as n from projects p "
        "join tasks t on t.project_id = p.id where t.user_id like ? "
        "group by p.id order by n desc limit 1",
        (scope,),
    ).fetchone()
    if busiest:
        facts["busiestProject"] = {"name": busiest[0], "tasks": busiest[1]}
    return facts


def copy_device_database(serial: str, local: Path) -> None:
    """Copy the app's database off the device, whole.

    The whole of the database is three files, not one: the WAL holds the
    commits that the main file has not absorbed yet, and a force-stop is a
    kill, not a clean close — so the data of the last writes is still in
    the WAL. The copy takes the WAL and its index with it, and is opened
    for writing, because SQLite recovers a WAL into the main file only when
    it may write to it. Read that copy without the WAL and the counts are
    of a database that is days old.

    The previous run's leftovers go first: a WAL that belongs to another
    database is not a WAL that can be recovered into this one.
    """
    for suffix in ("-wal", "-shm"):
        local.with_name(local.name + suffix).unlink(missing_ok=True)
    for name in ("todo.db", "todo.db-wal", "todo.db-shm"):
        proc = subprocess.run(
            ["adb", "-s", serial, "exec-out", "run-as", APP_ID, "cat", f"databases/{name}"],
            capture_output=True,
            timeout=300,
        )
        if proc.returncode == 0 and proc.stdout:
            target = local if name == "todo.db" else local.with_name(local.name + name[len("todo.db"):])
            target.write_bytes(proc.stdout)


def dataset_facts(serial: str, profile_name: str = "") -> dict:
    """What the device's own database holds, read from the app's copy.

    The app has to be stopped for the copy to be whole: a database copied
    while the app is writing it is a torn copy, and a count read from a
    torn copy is a number about nothing.
    """
    adb_ok("shell", "am", "force-stop", APP_ID, serial=serial)
    # One copy per device: two runs that share a file read each other's
    # database, and the counts in the report stop being about the device.
    local = ROOT / "build" / "perf" / f"frame-time-device-{device_fingerprint(serial)}.db"
    local.parent.mkdir(parents=True, exist_ok=True)
    copy_device_database(serial, local)
    facts: dict = {"database": str(local.name)}
    if local.is_file():
        try:
            import sqlite3

            connection = sqlite3.connect(f"file:{local}?mode=rw", uri=True)
            try:
                facts["tasksInDatabase"] = connection.execute("select count(*) from tasks").fetchone()[0]
                facts["projectsInDatabase"] = connection.execute("select count(*) from projects").fetchone()[0]
                if profile_name:
                    facts["profile"] = profile_name
                    facts["profileScope"] = profile_scope_facts(connection, profile_name)
            finally:
                connection.close()
        except Exception as exc:  # noqa: BLE001 — the report says what it could not read
            facts["databaseError"] = str(exc)
    return facts


# ── the scenarios ───────────────────────────────────────────────────────────


@dataclass
class Scenario:
    """One interaction, and how to get to it and back."""

    name: str
    description: str
    navigate: object
    interact: object
    #: The gestures that bring the screen back to its starting state between
    #: the runs. They happen before the frames are counted, so they cost the
    #: run nothing but time.
    restore: object = field(default=lambda device: None)
    settle: float = SETTLE_SECONDS


@dataclass
class Device:
    """The device the run drives."""

    serial: str
    package: str
    width: int
    height: int
    #: What the run found on the device and writes into the report.
    notes: dict = field(default_factory=dict)

    def scroll_pane(
        self,
        bounds: tuple[int, int, int, int],
        count: int = 4,
        direction: str = "up",
    ) -> None:
        """Swipe inside the pane, `count` times.

        The finger stays inside the pane's own bounds: the swipe has to
        reach the list, not the chrome beside it.
        """
        left, top, right, bottom = bounds
        x = (left + right) // 2
        height = bottom - top
        if direction == "up":
            y1, y2 = int(bottom - height * 0.15), int(top + height * 0.35)
        else:
            y1, y2 = int(top + height * 0.35), int(bottom - height * 0.15)
        for _ in range(count):
            swipe(self.serial, x, y1, x, y2)
            time.sleep(0.4)


def focus_package(serial: str) -> str:
    """The package that owns the screen that is up right now."""
    out = adb_ok("shell", "dumpsys", "window", "displays", serial=serial)
    match = re.search(r"mCurrentFocus=.*?u0 (\S+)/", out)
    return match.group(1) if match else ""


def ensure_foreground(device: Device, attempts: int = FOREGROUND_ATTEMPTS) -> None:
    """Make sure that our app owns the screen.

    The phone is a real phone, and its browser puts an advertisement on the
    screen on its own schedule. Such a screen is not the screen the run
    measures: the swipe would land on the advertisement, and the frames
    would be the advertisement's. The advertisement is closed with the back
    key, and the app is brought back up.
    """
    for _ in range(attempts):
        if focus_package(device.serial) == device.package:
            return
        adb_ok("shell", "input", "keyevent", "KEYCODE_BACK", serial=device.serial)
        time.sleep(1.0)
    if focus_package(device.serial) != device.package:
        raise MeasurementError(
            "another app kept the screen: " + (focus_package(device.serial) or "an unknown one")
        )


def open_app(device: Device) -> None:
    """Bring the app to the front, on its first screen.

    The first-run spotlight tour is dismissed here: it covers the agenda
    and swallows every tap under its scrim, so a run that starts while it
    is up drives a screen that is not there.
    """
    adb_ok("shell", "am", "start", "-W", "-n", MAIN_ACTIVITY, serial=device.serial)
    dismiss_spotlight(device.serial)


def switch_profile(device: Device, profile_name: str) -> None:
    """Put the app into the profile that holds the dataset.

    The debug seed link is the app's own path into a profile: it switches
    the active profile and brings the app up. A link that seeds nothing
    seeds nothing — it only switches.
    """
    adb_ok(
        "shell",
        "am",
        "start",
        "-W",
        "-a",
        "android.intent.action.VIEW",
        "-d",
        f"{DEBUG_SEED_SCHEME}?profile={profile_name}",
        serial=device.serial,
    )
    time.sleep(2.0)


def tap_bounds(serial: str, bounds: tuple[int, int, int, int]) -> None:
    """Tap the middle of the node's bounds."""
    left, top, right, bottom = bounds
    adb_ok(
        "shell",
        "input",
        "tap",
        str((left + right) // 2),
        str((top + bottom) // 2),
        serial=serial,
    )


def pane_bounds(serial: str) -> tuple[int, int, int, int]:
    """The scrollable pane of the screen that is up now."""
    bounds = scrollable_bounds(dump_ui(serial))
    if not bounds:
        raise MeasurementError("the screen that is up has nothing that scrolls")
    return bounds


def wait_for_pane(
    serial: str,
    timeout: float = NODE_TIMEOUT_SECONDS,
) -> tuple[int, int, int, int]:
    """The scrollable pane of the screen that is up now, once it is there.

    A screen that is still composing has no scrollable node yet: the list of
    a hundred project cards appears after the tap, and the run that looks for
    the pane before the list is composed finds nothing and gives up on a
    screen that was on its way.
    """
    deadline = time.monotonic() + timeout
    while True:
        bounds = scrollable_bounds(dump_ui(serial))
        if bounds:
            return bounds
        if time.monotonic() > deadline:
            raise MeasurementError("the screen that is up has nothing that scrolls")
        time.sleep(1.0)


def scroll_to_tag(serial: str, tag: str, attempts: int = SCROLL_ATTEMPTS) -> None:
    """Bring the node with the tag into view and tap it.

    The list is scrolled to find the node, so the node that gets tapped is
    one that is really on the screen.

    The list can be long: the dataset holds a hundred projects, and the
    card that is looked for is somewhere in the middle of them. The scroll
    therefore goes as far as it has to, and it stops when the list stops
    moving — a list that has reached its end will not move again, and a
    node that is not in this list is not going to be found by scrolling
    harder.
    """
    last_signature: tuple | None = None
    for _ in range(attempts):
        nodes = dump_ui(serial)
        bounds = node_bounds(nodes, tag)
        if bounds:
            tap_bounds(serial, bounds)
            return
        pane = scrollable_bounds(nodes)
        if pane:
            left, top, right, bottom = pane
            height = bottom - top
            swipe(
                serial,
                (left + right) // 2,
                int(bottom - height * 0.1),
                (left + right) // 2,
                int(top + height * 0.3),
            )
            time.sleep(0.4)
        signature = screen_signature(nodes)
        if signature == last_signature:
            break
        last_signature = signature
    raise MeasurementError(f"the node '{tag}' did not come into view")


def navigate_agenda(device: Device) -> None:
    """The agenda, on the section that holds the dataset.

    The generator spreads the tasks over the two years before the day it
    made them, so the forward-looking tab is empty and the agenda's weight
    is in the overdue section. That section is collapsed when the screen
    comes up; expanding it is what puts the rows on the screen.

    The way to the rows is retried: a run that starts while the app is
    still coming up, or while the first-run tour covers the agenda, sees
    no rows and no section to expand, and a single attempt would fail on
    a screen that was never ready. Each attempt brings the app up again
    and looks at the screen afresh.
    """
    last_seen = ""
    for _ in range(AGENDA_ATTEMPTS):
        open_app(device)
        try:
            tap_tag(device.serial, "nav_tab_today", timeout=15.0, package=device.package)
            time.sleep(1.5)
            nodes = dump_ui(device.serial)
            if not visible_task_tags(nodes):
                if not node_bounds(nodes, "agenda_section_toggle_overdue"):
                    last_seen = ", ".join(sorted(visible_ids(nodes))[:12]) or "nothing"
                    time.sleep(2.0)
                    continue
                tap_tag(
                    device.serial,
                    "agenda_section_toggle_overdue",
                    timeout=15.0,
                    package=device.package,
                )
                time.sleep(2.0)
            if visible_task_tags(dump_ui(device.serial)):
                wait_for_pane(device.serial)
                return
            last_seen = ", ".join(sorted(visible_ids(dump_ui(device.serial)))[:12]) or "nothing"
            time.sleep(2.0)
        except MeasurementError:
            # The screen was not ready: the app is still loading, the first-run
            # tour covers the agenda, or the section did not come up in time.
            # The attempt is repeated with the app brought up again.
            time.sleep(2.0)
    raise MeasurementError(
        "the agenda never showed a task row to measure"
        + (f"; the last screen held: {last_seen}" if last_seen else "")
    )


def visible_ids(nodes: list[dict]) -> set[str]:
    """The tags of the nodes that are on the screen right now."""
    return {
        node.get("resource-id", "").split("/")[-1]
        for node in nodes
        if node.get("resource-id")
    }


def interact_agenda(device: Device) -> None:
    """Scroll the agenda's populated section."""
    device.scroll_pane(wait_for_pane(device.serial), count=4)


def navigate_projects(device: Device, project_name: str) -> None:
    """The list of projects, with the dataset's projects on it.

    The list of projects is what the screen is: a hundred cards, each with
    its own counts, is a list that has to be laid out. The detail of one
    project is a second screen, and it is measured as the task detail is.
    """
    open_app(device)
    tap_tag(device.serial, "nav_menu_button", package=device.package)
    wait_for_tag(device.serial, "menu_sheet", package=device.package)
    scroll_to_tag(device.serial, "menu_all_projects")
    wait_for_tag(device.serial, "projects_create_button", package=device.package)
    time.sleep(1.5)
    wait_for_pane(device.serial)


def interact_projects(device: Device) -> None:
    """Scroll the list of projects."""
    device.scroll_pane(wait_for_pane(device.serial), count=4)


def navigate_project_detail(device: Device, project_name: str) -> None:
    """The task list of the project that holds the most tasks.

    The list of projects and the task list inside one project are two
    different screens, and they are measured separately: the issue asks
    for the task list of a project with many tasks.

    The detail of the project itself shows five of its tasks and a "See
    all" link; the list itself is the screen behind that link. The list is
    what holds a hundred rows, so the list is what is measured.
    """
    navigate_projects(device, project_name)
    # The card of the project is somewhere in the list, and the list is
    # alphabetical: the tap has to scroll to it, not wait for it.
    scroll_to_tag(device.serial, "project_card_" + slug(project_name))
    wait_for_tag(device.serial, "project_detail_tasks_header", package=device.package)
    time.sleep(1.0)
    tap_text(device.serial, "See all")
    time.sleep(2.0)


def interact_project_detail(device: Device) -> None:
    """Scroll the task list of the project."""
    device.scroll_pane(wait_for_pane(device.serial), count=4)


def navigate_task_detail(device: Device, task_title: str) -> None:
    """The detail of a task with a realistic amount of metadata.

    The task is a row that is really on the screen: the run reads the rows
    off the agenda's populated section and opens the first one.
    """
    navigate_agenda(device)
    row = ""
    for node in dump_ui(device.serial):
        resource_id = node.get("resource-id", "")
        if "task_item_" in resource_id:
            row = resource_id.split("/")[-1]
            device.notes["task"] = node.get("text", row)
            break
    if not row:
        if not task_title:
            raise MeasurementError("the agenda showed no task row to open")
        row = "task_item_" + slug(task_title)
    tap_tag(device.serial, row, timeout=60.0, package=device.package)
    wait_for_tag(device.serial, "task_editor_title_input", package=device.package)


def restore_to_top(device: Device) -> None:
    """Bring the list back to its first row.

    The run scrolls the same list twice: once to warm the screen up, once for
    the measurement. A list that is already at its end does not move a second
    time, and the frames of a list that did not move say nothing — so the
    list is put back to its top before the measured scroll.
    """
    try:
        pane = pane_bounds(device.serial)
    except MeasurementError:
        return
    device.scroll_pane(pane, count=10, direction="down")


def interact_task_detail(device: Device) -> None:
    """Scroll inside the open task."""
    device.scroll_pane(wait_for_pane(device.serial), count=3)


def navigate_calendar(device: Device) -> None:
    """The calendar, on the month view."""
    open_app(device)
    tap_tag(device.serial, "nav_tab_calendar", package=device.package)
    wait_for_tag(device.serial, "calendar_controls", package=device.package)


def interact_calendar(device: Device) -> None:
    """Move to the next period, the way the reader does it."""
    for _ in range(3):
        nodes = dump_ui(device.serial)
        bounds = node_bounds_by_desc(nodes, "Next")
        if not bounds:
            raise MeasurementError("the calendar showed no 'Next' control")
        tap_bounds(device.serial, bounds)
        time.sleep(0.8)


def slug(input_text: str) -> str:
    """The tag slug of a title, the way the app makes it.

    The app's own `slug` is in `TestTags.kt`: the title is lowercased,
    every run of non-alphanumeric characters becomes one underscore, and
    the underscores at the ends are trimmed.
    """
    out: list[str] = []
    pending = False
    for char in input_text.strip().lower():
        if char.isalnum():
            if pending and out:
                out.append("_")
            out.append(char)
            pending = False
        else:
            pending = True
    return "".join(out) or "untitled"


# ── the run ─────────────────────────────────────────────────────────────────


def screen_signature(nodes: list[dict]) -> tuple:
    """What is on the screen, in a form that changes when the screen moves.

    Two dumps of the same screen differ in nothing but the identity of the
    rows and where they are; a screen that scrolled or changed period
    differs in both.
    """
    return tuple(
        (node.get("resource-id") or node.get("text", ""), node.get("bounds", ""))
        for node in nodes
        if node.get("resource-id") or node.get("text")
    )


def measure_scenario(
    device: Device,
    scenario: Scenario,
    runs: int,
    warmup: int,
    on_run: Callable[[dict], None] | None = None,
) -> dict:
    """Run one scenario and collect its frames.

    `on_run` is called after every measured run with the samples so far:
    a run that dies on its last measurement still leaves the earlier ones in
    the report.
    """
    samples: list[dict] = []
    for index in range(warmup + runs):
        scenario.navigate(device)
        ensure_foreground(device)
        scenario.restore(device)
        scenario.interact(device)
        time.sleep(scenario.settle)
        if index < warmup:
            continue
        # The list is put back to its top before the measured interaction:
        # the warm-up scroll left it at the end, and a list that is already at
        # its end does not move again.
        scenario.restore(device)
        time.sleep(scenario.settle)
        # The frames are counted from here: the navigation that brought the
        # screen up is not part of the interaction, and the platform's
        # counters start empty.
        ensure_foreground(device)
        before = screen_signature(dump_ui(device.serial))
        reset_frames(device.serial, device.package)
        scenario.interact(device)
        time.sleep(scenario.settle)
        summary = frame_summary(device.serial, device.package)
        durations = frame_durations(device.serial, device.package)
        summary["measuredDurationsMs"] = durations
        after = screen_signature(dump_ui(device.serial))
        if focus_package(device.serial) != device.package:
            # Another app took the screen in the middle of the interaction:
            # the frames of that stretch are the other app's frames.
            raise MeasurementError(
                f"another app took the screen during the '{scenario.name}' "
                "interaction, so its frames say nothing"
            )
        if before == after:
            # Frames of a screen that did not move are frames about
            # nothing, and a report that counts them is a lie.
            raise MeasurementError(
                f"the '{scenario.name}' interaction changed nothing on the screen, "
                "so its frames say nothing"
            )
        samples.append(summary)
        # The version of the app that is installed right now: the device is
        # shared, and another run can put another build on it in the middle
        # of a measurement.
        summary["appVersionName"] = app_version(device.serial)
        print(
            f"  run {index - warmup + 1}/{runs}: "
            f"frames={summary.get('totalFrames', '?')} "
            f"janky={summary.get('jankyFrames', '?')} "
            f"p50={summary.get('p50', '?')}ms "
            f"build={summary.get('appVersionName', '?')}",
            flush=True,
        )
        if on_run is not None:
            on_run(
                {
                    "name": scenario.name,
                    "description": scenario.description,
                    "samples": list(samples),
                    "summary": aggregate(samples),
                }
            )
    return {"name": scenario.name, "description": scenario.description, "samples": samples}


def aggregate(samples: list[dict]) -> dict:
    """The medians and the spread of a scenario's runs."""
    if not samples:
        return {}
    def median_of(values: list[float]) -> float | None:
        return round(statistics.median(values), 2) if values else None

    totals = [float(s.get("totalFrames", 0)) for s in samples]
    janky = [float(s.get("jankyFrames", 0)) for s in samples]
    p50 = [float(s["p50"]) for s in samples if "p50" in s]
    p90 = [float(s["p90"]) for s in samples if "p90" in s]
    p95 = [float(s["p95"]) for s in samples if "p95" in s]
    durations = [d for s in samples for d in s.get("measuredDurationsMs", [])]
    total_frames = sum(totals)
    total_janky = sum(janky)
    return {
        "runs": len(samples),
        "framesPerRunMedian": median_of(totals),
        "jankyFramesPerRunMedian": median_of(janky),
        "jankyPercentOverall": round(100.0 * total_janky / total_frames, 2) if total_frames else None,
        "gfxinfoP50MedianMs": median_of(p50),
        "gfxinfoP90MedianMs": median_of(p90),
        "gfxinfoP95MedianMs": median_of(p95),
        "measuredFramesMedianMs": median_of(durations),
        "measuredFramesP95Ms": (
            round(sorted(durations)[int(0.95 * (len(durations) - 1))], 2) if durations else None
        ),
        "measuredFramesMaxMs": round(max(durations), 2) if durations else None,
    }


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    """The command line."""
    parser = argparse.ArgumentParser(
        prog="measure-compose-frame-time.py",
        description="Measure Android frame times for the app's primary screens.",
    )
    parser.add_argument("--serial", required=True, help="the device to measure on")
    parser.add_argument("--package", default=APP_ID, help="the app's package")
    parser.add_argument(
        "--profile-name",
        default="",
        help="the profile that holds the dataset; the run switches into it "
        "through the app's own debug link",
    )
    parser.add_argument(
        "--scenario",
        action="append",
        default=[],
        choices=[
            "agenda-scroll",
            "project-tasks-scroll",
            "project-detail-scroll",
            "task-detail-scroll",
            "calendar-periods",
        ],
        help="the scenario to measure; may be given more than once "
        "(default: all of them)",
    )
    parser.add_argument("--runs", type=int, default=5, help="measured runs per scenario, default 5")
    parser.add_argument("--warmup", type=int, default=2, help="warm-up runs per scenario, default 2")
    parser.add_argument(
        "--project",
        default="",
        help="the project to open; by default the busiest project in the device's own database",
    )
    parser.add_argument(
        "--task",
        default="",
        help="the task to open; by default the first task of the busiest project",
    )
    parser.add_argument(
        "--out",
        default="",
        help="where the report goes; by default a new directory under build/perf/",
    )
    return parser.parse_args(argv)


def write_report(report: dict, out_dir: Path) -> Path:
    """Write the report, and say where it went."""
    path = out_dir / "frame-time-report.json"
    path.write_text(json.dumps(report, indent=2, sort_keys=True), encoding="utf-8")
    markdown = out_dir / "frame-time-report.md"
    markdown.write_text(render_markdown(report), encoding="utf-8")
    return path


def render_markdown(report: dict) -> str:
    """The report as a Markdown table, the way the document quotes it.

    The table is written by the script that measured, so the numbers in
    the document are the numbers of the report and not a second, hand-kept
    copy of them.
    """
    lines = [
        "| сценарий | прогонов | кадров в прогоне (медиана) | janky в прогоне (медиана) "
        "| janky, % | p50 сводки, мс | p95 сводки, мс | кадр медиана, мс | кадр p95, мс |",
        "|---|---|---|---|---|---|---|---|---|",
    ]
    for name, scenario in (report.get("scenarios") or {}).items():
        summary = scenario.get("summary") or {}
        lines.append(
            "| `{name}` | {runs} | {frames} | {janky} | {percent} | {p50} | {p95} "
            "| {median} | {frame_p95} |".format(
                name=name,
                runs=summary.get("runs", "—"),
                frames=summary.get("framesPerRunMedian", "—"),
                janky=summary.get("jankyFramesPerRunMedian", "—"),
                percent=summary.get("jankyPercentOverall", "—"),
                p50=summary.get("gfxinfoP50MedianMs", "—"),
                p95=summary.get("gfxinfoP95MedianMs", "—"),
                median=summary.get("measuredFramesMedianMs", "—"),
                frame_p95=summary.get("measuredFramesP95Ms", "—"),
            )
        )
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    """Measure, and write the report."""
    args = parse_args(argv)
    out_dir = Path(args.out) if args.out else ROOT / "build" / "perf" / (
        "frame-time-" + time.strftime("%Y%m%d-%H%M%S")
    )
    out_dir = out_dir.resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    # One run per device: the lock is the repository's own, so this command
    # and a qualified Maestro run do not drive the same phone at once.
    lock = DeviceLock(args.serial)
    try:
        lock.acquire()
    except DeviceLocked as exc:
        raise MeasurementError(str(exc)) from exc
    try:
        return measure(args, out_dir)
    finally:
        lock.release()


def measure(args: argparse.Namespace, out_dir: Path) -> int:
    """The measurement itself, on a device this process has claimed."""
    width, height = screen_size(args.serial)
    device = Device(serial=args.serial, package=args.package, width=width, height=height)

    print(f"device: {args.serial}")
    if wake_device(args.serial):
        print("device: woken up")
    dataset = dataset_facts(args.serial, args.profile_name)
    print(f"dataset: {dataset}")

    scope = dataset.get("profileScope") or {}
    project_name = args.project or (scope.get("busiestProject") or {}).get("name", "Project 1")
    task_title = args.task or "Task 1"

    if args.profile_name:
        switch_profile(device, args.profile_name)

    scenarios = {
        "agenda-scroll": Scenario(
            name="agenda-scroll",
            description="Upcoming timeline, opened and scrolled",
            navigate=navigate_agenda,
            interact=interact_agenda,
            restore=restore_to_top,
        ),
        "project-tasks-scroll": Scenario(
            name="project-tasks-scroll",
            description="the list of projects, with the dataset's projects on it",
            navigate=lambda device: navigate_projects(device, project_name),
            interact=interact_projects,
            restore=restore_to_top,
        ),
        "project-detail-scroll": Scenario(
            name="project-detail-scroll",
            description=f"the task list of the project '{project_name}'",
            navigate=lambda device: navigate_project_detail(device, project_name),
            interact=interact_project_detail,
            restore=restore_to_top,
        ),
        "task-detail-scroll": Scenario(
            name="task-detail-scroll",
            description=f"detail of the task '{task_title}'",
            navigate=lambda device: navigate_task_detail(device, task_title),
            interact=interact_task_detail,
            restore=restore_to_top,
        ),
        "calendar-periods": Scenario(
            name="calendar-periods",
            description="calendar, moving between periods",
            navigate=navigate_calendar,
            interact=interact_calendar,
        ),
    }
    wanted = args.scenario or list(scenarios)

    report: dict = {
        "device": device_facts(args.serial),
        "dataset": dataset,
        "build": {"package": args.package, "variant": "debug"},
        "method": {
            "source": "adb shell dumpsys gfxinfo <package> (summary) and framestats (per frame)",
            "warmupRuns": args.warmup,
            "measuredRuns": args.runs,
            "settleSeconds": SETTLE_SECONDS,
            "note": "the frames of the navigation are not counted; only the frames of the interaction",
        },
        "scenarios": {},
    }

    for name in wanted:
        scenario = scenarios[name]

        def persist(partial: dict, name: str = name) -> None:
            """Keep the samples of a run that is still going."""
            report["scenarios"][name] = partial
            write_report(report, out_dir)

        print(f"scenario: {name}", flush=True)
        measured = measure_scenario(device, scenario, args.runs, args.warmup, on_run=persist)
        measured["summary"] = aggregate(measured["samples"])
        report["scenarios"][name] = measured
        # The report is written after every scenario and after every run of
        # it, not only at the end: a run that fails on the fourth scenario
        # still leaves the first three measured, and a measurement that is
        # thrown away is a measurement that has to be paid for twice.
        write_report(report, out_dir)

    write_report(report, out_dir)
    print(f"report: {out_dir / 'frame-time-report.json'}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except MeasurementError as exc:
        print(f"error: {exc}", file=sys.stderr)
        sys.exit(1)
