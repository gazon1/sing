#!/usr/bin/env python3
"""Restore a synthetic backup into the Android app and check what came out.

One command, one named device, one archive:

    python3 scripts/verify-large-restore.py --serial 0O65321F32204DD5 --profile 10k

WHAT IT DOES

    1. builds (or takes) the debug APK and installs it on the *named* device;
    2. generates the synthetic archive with `import-cli` and reads its sidecar
       manifest — the manifest is what the archive is supposed to contain;
    3. pushes the archive into the device's Downloads and restores it through
       the app's own restore path: Settings -> Backup -> Restore -> the system
       file picker -> the confirm dialog. No direct write into Room, no debug
       endpoint that writes rows in bulk;
    4. checks the two ways the restore can go wrong — an archive over the
       budget, and an import that is cut short — and the one way it has to go
       right: the counts and the links, the data after a restart, and the task
       list, the task detail, the search and the related-tasks picker;
    5. writes the measurements into a report.

WHAT IT DELIBERATELY DOES NOT DO

    It does not pick a device. The serial is required, and a serial that is
    not attached is an error rather than a guess — the same rule the store
    capture follows, and the same reason: one attached device is just as
    likely to be somebody's phone as the test AVD, and this command installs
    an APK on it and overwrites its data.

    It does not uninstall the app. A signature clash is reported, not resolved
    by throwing away the data on the device.

WHY THE DEVICE POLICY LIVES IN `store-capture.py`

    The rules above — name the device, refuse to guess, refuse to throw the
    data away — are already written down and already tested, in the script
    that installs an APK on a device and takes its picture. This command does
    the same kind of work on the same kind of device, so it uses the same
    implementation instead of a second one that drifts.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# The repository's own device lock. This command drives the device on its own
# — it starts Maestro directly, not through the certified suite — so it has to
# claim the device the way the suite does: one run per device, and the *same*
# lock file, so the two exclude each other instead of fighting over the
# device's server.
_SCRIPTS_SRC = str(ROOT / "scripts" / "src")
if _SCRIPTS_SRC not in sys.path:
    sys.path.insert(0, _SCRIPTS_SRC)

from emulator.maestro._lock import DeviceLock, DeviceLocked  # noqa: E402

#: The flows this command drives. They live next to the command and not in
#: `Maestro/flows/`: they are a local performance scenario, not part of the
#: certified suite — no CI runs them, so they carry no catalogue entry and no
#: qualification record.
FLOW_DIR = Path(__file__).resolve().parent / "verify-large-restore"

#: The package the app installs under.
APP_ID = "com.singularity.todo"

#: Where the archive is pushed on the device. The system file picker opens on
#: this folder's own listing, so the archive has to be somewhere the picker
#: can show it.
DEVICE_DOWNLOAD_DIR = "/sdcard/Download"

#: The archives the run pushed into the device's Downloads, so the run can
#: delete them when it is done with them.
PUSHED_ARCHIVES: list[str] = []

#: The tables the manifest's counts are about, and the table each count is
#: counted in. The names are the Room table names.
COUNT_TABLES = {
    "tasks": "tasks",
    "projects": "projects",
    "tags": "tags",
    "notes": "notes",
    "checklistItems": "checklist_items",
    "taskDependencies": "task_dependencies",
    "taskTags": "task_tags",
}

#: The tables whose rows belong to a profile and carry that profile's scope
#: in a `user_id` column. The app writes every profile's rows under the
#: profile's own scope, so the scope is what tells one profile's rows from
#: another's.
SCOPED_TABLES = ("tasks", "projects", "tags", "notes")

#: The tables that hang off a task and therefore carry the task's scope.
TASK_SCOPED_TABLES = ("checklist_items", "task_dependencies", "task_tags")

#: The id of the profile the app starts in. The rows of that profile carry
#: the plain user id; the rows of every other profile carry the profile's id
#: in front of the user id.
DEFAULT_PROFILE_ID = "default"

#: The snackbar the restore path reports itself with. Its test tag is the
#: evidence; the text is translated on the device, so it is not asserted on.
SNACKBAR_ID = "snackbar_message"

#: The button of the dialog that asks before it overwrites the profile's data.
CONFIRM_ID = "dialog_confirm"

#: What Maestro writes when it starts the step that taps that button.
CONFIRM_TAP = "Tap on id: dialog_confirm"

#: What Maestro writes when that step is done. The step's line is written
#: before the tap is dispatched, so this is the moment the confirmation was
#: really tapped — and the import cannot start before it. The import's
#: duration is measured from here, not from the moment the step began.
CONFIRM_TAP_DONE = "Tap on id: dialog_confirm... COMPLETED"

#: The words the backup screen shows while the app is working. The backup
#: screen puts them there for as long as the app is busy, and the app is busy
#: for as long as the restore runs: it is the app saying that it is restoring,
#: and not the test guessing from a tap. The ellipsis is part of the words, so
#: that the "Working Days" section of the settings cannot be mistaken for them.
WORKING_MARKER = re.compile(r"Working(?:\u2026|\.\.\.)")

#: The words the app's own snackbar carries when a restore is over. Seeing
#: them means the import ran all the way through before it could be cut
#: short — the case the interrupted-import check exists for was not tested.
RESTORE_DONE_MARKER = "Restore complete"

#: How the device is asked for the digest of the installed APK, in the order it
#: is tried. Which one a device has depends on its Android version, and the
#: name is part of the digest so that the two are never compared to each other.
APK_DIGEST_COMMANDS = (("md5", "md5sum"), ("sha256", "sha256sum"))

#: The shared dialog the failure of an import is reported in.
ERROR_DIALOG_ID = "result_dialog"

#: The words the app's own budget check puts in the message it fails an
#: archive with. The message is the app's own English — the title of the
#: dialog is translated, this is not — so it can be matched on. It is what
#: tells the two refusals apart: a refusal because the archive is too big,
#: and a refusal because anything at all went wrong.
#:
#: The check fails an archive in two ways: "backup archive exceeds limits:
#: entries=… bytes=…" when the running total is over the budget, and
#: "backup archive entry exceeds remaining byte budget" when a single entry
#: is bigger than what is left of it. The padded archive trips the second
#: one, so both are here.
BUDGET_REFUSAL_MARKERS = ("exceeds limits", "byte budget")

#: The line the debug activity logs when the profile's content has been
#: wiped. It is the end-of-wipe signal: the deep link returns long before the
#: deletes are committed.
RESET_MARKER = "reset(content) reached"

#: A tag the app's agenda always carries. The agenda is the app's own proof
#: that it has resolved its identity: the rows it lists are the current user's
#: rows, so the app cannot show the agenda before the session is there.
AGENDA_READY_TAG = 'resource-id="nav_tab_inbox"'

#: How many bytes the padded archive adds to push the archive over the
#: budget the app enforces (64 MiB of unpacked bytes).
OVER_BUDGET_PADDING_BYTES = 70 * 1024 * 1024

#: How long the watch waits after the confirmation before it stops believing
#: that the app is still importing. Past this the import is over, whatever
#: the screen says; the database is what settles whether it worked.
SILENT_IMPORT_SECONDS = 600

#: How long the watch waits for the Maestro flow to let go of the device after
#: the confirmation tap. The screen is only read after that: a dump taken while
#: the flow still holds the accessibility service starves Maestro's driver.
FLOW_EXIT_WAIT_SECONDS = 120

#: The screen dump is scanned, not parsed: the device may cut the tree off
#: in the middle and glues the shell's own output onto the end.
NODE_TAG = re.compile(r"<node\b[^>]*>")
NODE_ATTRIBUTE = re.compile(r'([\w-]+)="([^"]*)"')

EXIT_OK = 0
EXIT_FAILED = 1
EXIT_SETUP = 2


class RunError(RuntimeError):
    """The run could not measure what it claims to measure.

    A different failure from "the restore did not work": this one says the
    command is not measuring the thing it claims to measure — no device, no
    APK, no archive, a database that cannot be copied as one moment, a scope
    that cannot be attributed to a profile. These are refusals to measure,
    and they are reported as EXIT_SETUP.
    """


class VerificationError(RunError):
    """The dataset was restored, and what the app holds does not hold up.

    A different failure from the one above: the restore was attempted and
    the measurements were taken, and the app did not show what the archive
    carried — the Inbox does not open, the task list holds no row, the
    related-tasks picker has nothing to offer. The run has an answer, and
    the answer is "the restore did not hold up", not "the command could not
    start": a caller has to be able to tell the two apart.
    """


# ── the device policy, borrowed from the store capture ──────────────────────


def _load_store_capture():
    """The store capture's device helpers, loaded as a module.

    The file name has a dash in it, so it cannot be imported by name; it is
    loaded the way the repository's own tests load it.
    """
    path = ROOT / "scripts" / "store-capture.py"
    spec = importlib.util.spec_from_file_location("store_capture", path)
    if spec is None or spec.loader is None:
        raise RunError(f"the device helpers in {path} could not be loaded")
    module = importlib.util.module_from_spec(spec)
    # The module has to be in `sys.modules` before it is executed: a dataclass
    # looks its own module up there while it is being built.
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


capture = _load_store_capture()


def adb(*args: str, serial: str = "", timeout: float = 120) -> tuple[int, str]:
    """Run an adb command against the device; return its exit code and output."""
    return capture.adb(*args, serial=serial, timeout=timeout)


def adb_bytes(*args: str, serial: str = "", timeout: float = 900) -> tuple[int, bytes]:
    """Run an adb command and keep its output as bytes.

    The device's database is binary. The helper the capture uses decodes what
    it reads as text, and a decoded database is not a database: the bytes that
    are not valid text are replaced, and the copy that comes back is a file
    that opens and holds nothing.
    """
    command = ["adb"]
    if serial:
        command += ["-s", serial]
    command += list(args)
    try:
        cp = subprocess.run(command, capture_output=True, timeout=timeout)
    except (OSError, subprocess.SubprocessError) as exc:
        return 1, str(exc).encode()
    return cp.returncode, cp.stdout


def device_facts(serial: str) -> dict:
    """What the device is: the model, the Android version, the ABI."""
    facts = {}
    for name, prop in (
        ("model", "ro.product.model"),
        ("androidRelease", "ro.build.version.release"),
        ("sdk", "ro.build.version.sdk"),
        ("abi", "ro.product.cpu.abi"),
    ):
        code, out = adb("shell", "getprop", prop, serial=serial)
        facts[name] = out.strip() if code == 0 else "unknown"
    return facts


def database_version(serial: str) -> int | None:
    """The schema version of the database on the device, or None.

    SQLite keeps the version in the first bytes of the file, so the header is
    enough to read it.
    """
    code, out = adb_bytes(
        "exec-out", "run-as", APP_ID, "cat", "databases/todo.db", serial=serial, timeout=300
    )
    if code != 0 or len(out) < 64:
        return None
    return int.from_bytes(out[60:64], "big")


def app_schema_version() -> int:
    """The schema version of this checkout's database."""
    exports = ROOT / "core-database" / "schemas" / f"{APP_ID}.core.database.AppDatabase"
    versions = [int(path.stem) for path in exports.glob("*.json") if path.stem.isdigit()]
    return max(versions) if versions else 0


def installed_apk_digest(serial: str) -> str:
    """The digest the device reports for the installed APK, e.g. `md5:abc…`.

    Empty when the device cannot say. A device that does not report a
    checksum does not prove that the build on it is the build this run
    installed, so the callers treat that as a failure and not as a pass.
    """
    code, out = adb("shell", "pm", "path", APP_ID, serial=serial)
    if code != 0:
        return ""
    remote = ""
    for line in out.splitlines():
        if line.startswith("package:"):
            remote = line.strip()[len("package:"):]
            break
    if not remote:
        return ""
    for algorithm, command in APK_DIGEST_COMMANDS:
        code, out = adb("shell", f"{command} {remote}", serial=serial, timeout=300)
        if code == 0 and out.split():
            return f"{algorithm}:{out.split()[0]}"
    return ""


#: The digests of the APK this run installed, by the file they were taken
#: from. The APK does not change while a run lasts, and hashing it again
#: before every flow would read the whole file again for nothing; the key
#: holds its size and its time, so a file that did change is hashed again.
_APK_DIGEST_CACHE: dict[tuple[str, int, int], dict[str, str]] = {}


def local_apk_digests(apk: Path) -> dict[str, str]:
    """The digests of the APK this run installed."""
    stat = apk.stat()
    key = (str(apk), stat.st_size, stat.st_mtime_ns)
    cached = _APK_DIGEST_CACHE.get(key)
    if cached is not None:
        return cached
    data = apk.read_bytes()
    digests = {
        "md5": hashlib.md5(data).hexdigest(),
        "sha256": hashlib.sha256(data).hexdigest(),
    }
    _APK_DIGEST_CACHE[key] = digests
    return digests


def digest_matches(remote: str, local: dict[str, str]) -> bool:
    """Whether the device's digest is the digest of the given APK."""
    algorithm, _, value = remote.partition(":")
    return bool(value) and value == local.get(algorithm)


def verify_installed_apk(serial: str, apk: Path) -> str:
    """Check that the device carries the very APK this run installed.

    The version name is not enough. Two builds of the same project can carry
    the same version name, and a phone shared with another session can have
    another session's build installed on it between the install and the run.
    That is not a theoretical worry: a foreign build with an older database
    version makes the app refuse to open the database at all, and the flow
    then fails on a screen the restore never touched.

    A device that reports no checksum at all is refused as well: the identity
    of the build on it cannot be established, and a run that cannot tell the
    builds apart cannot claim to have measured this one.

    Returns the digest the device reported, so the report can name the exact
    build the measurements belong to.
    """
    local = local_apk_digests(apk)
    remote = installed_apk_digest(serial)
    if not remote:
        raise RunError(
            f"the device does not report a checksum of the installed APK, so the build "
            f"on {serial} cannot be told apart from the one this run installed. The "
            "run is refused rather than measured against an unknown build."
        )
    if not digest_matches(remote, local):
        algorithm, _, value = remote.partition(":")
        raise RunError(
            f"the device carries an APK with the {algorithm} checksum {value}, but the "
            f"one this run installed has {local.get(algorithm)}. Another session "
            "installed another build on this device; the run is refused rather than "
            "measured against it."
        )
    return remote


def verify_apk_unchanged(serial: str, apk: Path) -> None:
    """Check that the APK on the device is still the one this run installed.

    The check at the start of the run is not enough: another session can
    install its own build while this run is between two flows. The foreign
    build then opens the profile's database with a different schema version,
    and the app shows a migration error instead of its own screens — a
    failure that has nothing to do with the restore and that reads like one.

    So the check is made again before every flow, where a foreign build is
    still somebody else's problem to notice. A device that stops reporting a
    checksum is refused here too: the identity of the build is exactly what
    this check is for.
    """
    local = local_apk_digests(apk)
    remote = installed_apk_digest(serial)
    if not remote:
        raise RunError(
            f"the device stopped reporting a checksum of the installed APK, so the "
            f"build on {serial} can no longer be told apart from the one this run "
            "installed. The run stops here rather than measuring a restore against "
            "somebody else's app."
        )
    if not digest_matches(remote, local):
        algorithm, _, value = remote.partition(":")
        raise RunError(
            f"the APK on {serial} changed while this run was going: it carries "
            f"{value}, this run installed {local.get(algorithm)}. Another session "
            "installed another build on this device; the run stops here rather than "
            "measuring a restore against somebody else's app."
        )


# ── the archive ──────────────────────────────────────────────────────────────

#: The generator's profiles are plain names ("1k", "10k", "50k", "100k"). The
#: profile ends up in a file name, so a name that is not a plain token is
#: refused before it can reach the filesystem.
PROFILE_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]*")


def archive_paths(profile: str, out_dir: Path) -> tuple[Path, Path]:
    """Where the archive and its sidecar live — and a refusal if they could
    land anywhere but inside the output directory.

    The profile is a name the operator typed, and it becomes part of a path.
    A profile that carries a separator could otherwise name a file outside
    the output directory — and the run deletes the file it is about to write.
    The refusal comes before any file is touched.
    """
    if not PROFILE_PATTERN.fullmatch(profile):
        raise RunError(
            f"the profile {profile!r} is not a plain name, so it cannot be used as a "
            "file name. The run refuses it before touching any file."
        )
    base = out_dir.resolve()
    archive = (base / f"synthetic-{profile}.zip").resolve()
    sidecar = archive.with_suffix(".manifest.json")
    for path in (archive, sidecar):
        if path.parent != base:
            raise RunError(
                f"the archive would land on {path}, outside the output directory {base}. "
                "The run refuses it before touching any file."
            )
    return archive, sidecar


def generate_archive(profile: str, seed: int, out_dir: Path) -> tuple[Path, Path, dict]:
    """Generate the synthetic archive and read its sidecar manifest.

    Returns the archive, the sidecar and the manifest.
    """
    archive, sidecar = archive_paths(profile, out_dir)
    for path in (archive, sidecar):
        if path.exists():
            path.unlink()
    command = [
        str(ROOT / "gw"),
        ":import-cli:run",
        f"--args=generate --profile {profile} --seed {seed} --output {archive}",
    ]
    print(f"generating: {' '.join(command)}")
    cp = subprocess.run(command, cwd=ROOT, capture_output=True, text=True)
    if cp.returncode != 0:
        raise RunError(
            "the generator failed:\n"
            + (cp.stdout or "")[-2000:]
            + (cp.stderr or "")[-2000:]
        )
    if not archive.is_file() or not sidecar.is_file():
        raise RunError(f"the generator produced no archive at {archive}")
    manifest = json.loads(sidecar.read_text(encoding="utf-8"))
    print(f"archive: {archive} ({archive.stat().st_size} bytes)")
    return archive, sidecar, manifest


def padded_archive(archive: Path, out_dir: Path) -> Path:
    """A copy of the archive that is over the budget the app enforces.

    The generator refuses to write such a dataset, which is the right answer
    for the generator — so the archive the app is asked to refuse is built
    here: the same two entries, plus one entry of padding that pushes the
    unpacked size past the budget. The app has to refuse it, and it has to
    refuse it for the budget's reason and not for a malformed archive's.
    """
    padded = out_dir / "over-budget.zip"
    with zipfile.ZipFile(archive) as source, zipfile.ZipFile(
        padded, "w", zipfile.ZIP_DEFLATED
    ) as target:
        for info in source.infolist():
            target.writestr(info.filename, source.read(info.filename))
        target.writestr("padding.bin", b"\0" * OVER_BUDGET_PADDING_BYTES)
    return padded


def push_archive(serial: str, archive: Path) -> str:
    """Put the archive into the device's Downloads; return its name there."""
    name = archive.name
    remote = f"{DEVICE_DOWNLOAD_DIR}/{name}"
    code, out = adb("push", str(archive), remote, serial=serial, timeout=900)
    if code != 0:
        raise RunError(f"the archive could not be pushed to {remote}:\n{out.strip()}")
    PUSHED_ARCHIVES.append(name)
    # The picker's "recent" list is fed by the media store, so the file has to
    # be scanned in. Without the scan the file is in Downloads but the picker
    # does not offer it.
    adb(
        "shell",
        "am",
        "broadcast",
        "-a",
        "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
        "-d",
        f"file://{remote}",
        serial=serial,
    )
    return name


def remove_pushed_archives(serial: str) -> list[str]:
    """Delete the archives this run pushed into the device's Downloads.

    The device is not the run's own: its storage belongs to whoever uses it,
    and the archives are large — the padded one is tens of megabytes. The run
    pushes three of them (the over-budget copy, the interrupted-import copy
    and the archive that is really imported), and a run that leaves them
    behind fills the device with copies of the same dataset.

    The list is the run's own record of what it pushed, so a run that dies
    half way through still cleans up what it managed to push.
    """
    removed: list[str] = []
    for name in list(PUSHED_ARCHIVES):
        remote = f"{DEVICE_DOWNLOAD_DIR}/{name}"
        code, out = adb("shell", "rm", "-f", remote, serial=serial)
        PUSHED_ARCHIVES.remove(name)
        if code == 0:
            removed.append(name)
        else:
            print(f"warning: {remote} could not be deleted:\n{out.strip()}")
    return removed


def wait_for_reset_marker(serial: str, timeout: float = 7200) -> float:
    """Wait for the app's own line that says the wipe is finished.

    The debug activity logs `reset(content) reached N tables` when
    `resetContent()` has returned — that is, when every delete has been
    committed. The line is the only end-of-wipe signal there is: the deep link
    returns as soon as the app has taken it, long before the deletes are done.

    Waiting on the line rather than on a fixed delay matters, because a wipe
    of a large dataset takes minutes (every removed task is pushed to the sync
    outbox) and a reader that stops the app to read the database in the
    middle of the wipe rolls the wipe back: the app is killed with the
    transaction open, the transaction is discarded, and the profile still
    holds every row the wipe had already deleted.

    The wait is two hours, because a wipe of a big profile is a long piece of
    work on the device: the wipe of 10 000 tasks took 457 s on the realme
    RMX3938, and the wipe of 50 000 of them took 4743 s there (measured
    2026-11-23, 19:05:25 → 20:24:28). A timeout shorter than the wipe itself
    would call the wipe failed while it was still deleting, and a run that
    gave up on the wipe of a profile the device can prepare would report a
    limit of the run where the limit is the device's.
    """
    started = time.monotonic()
    while time.monotonic() - started < timeout:
        code, out = adb("logcat", "-d", "-s", "DebugSeedActivity:V", serial=serial)
        if RESET_MARKER in out:
            return time.monotonic() - started
        time.sleep(5)
    raise RunError(f"the profile's content was not wiped within {int(timeout)}s")


def agenda_is_up(dump: str) -> bool:
    """Whether the screen shows the app's agenda.

    The agenda is the first screen of the app and it lists the current user's
    rows, so it can only be there once the app knows who the current user is.
    """
    return AGENDA_READY_TAG in dump


def warm_up_app(serial: str, timeout: float = 240) -> None:
    """Start the app and wait until it shows its agenda.

    A wipe that runs before the app has resolved its identity wipes nothing.
    The session is read from the encrypted store on a cold start, and that read
    takes seconds — the reset asked for in the meantime is answered by the
    unresolved scope, which has no rows to delete: the app logs that the reset
    reached every table in a second and the profile keeps every row it had.
    The run then reads a profile that was never wiped and blames the restore.

    So the app is brought up first and waited for until its agenda is on the
    screen. The agenda is the proof that the identity is resolved — it is the
    current user's rows the app is showing — and the wipe link that follows
    arrives at a running process, where the identity is already there.
    """
    wake_unlock(serial)
    adb("shell", "am", "start", "-n", f"{APP_ID}/.MainActivity", serial=serial, timeout=60)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if agenda_is_up(ui_dump(serial)):
            return
        time.sleep(2)
    raise RunError(
        f"the app did not come up with its agenda within {int(timeout)}s, so the "
        "profile's rows cannot be wiped: the wipe would run against an identity "
        "the app has not resolved yet"
    )


def reset_content(
    serial: str,
    out_dir: Path | None = None,
    attempts: int = 4,
    before_db: Path | None = None,
) -> tuple[dict[str, int], float, str | None]:
    """Wipe the active profile's content through the debug seed link.

    The same link the Maestro runner uses before a flow. The import overwrites
    the profile's rows, but the counts afterwards are only the dataset's counts
    if there was nothing else in the profile to begin with.

    The wipe is waited for with the app's own marker (see
    [wait_for_reset_marker]) and the check is made on a database that is read
    with the app stopped. A wipe that did not take effect is asked for again.

    The app is warmed up before every attempt (see [warm_up_app]): a wipe that
    reaches a cold process deletes nothing, because the profile's rows are not
    the current user's rows until the session has been read.

    Returns the row counts of the wiped profile, how long the wipe took, and
    the scope the counts are about.

    ## The scope of the counts

    The reset deletes the rows of the *active* profile and nothing else, so a
    device that holds a second profile keeps that profile's rows — and the
    counts of the profile under test are not the counts of the database. The
    scope is therefore taken from the rows the reset removed: their
    `user_id` is the scope of the profile the reset acted on, and the counts
    after the reset are counted for that scope alone.

    A reset that removed nothing leaves the scope unknown. That is the case of
    a profile that held no rows to begin with — the counts of an empty
    database are the profile's counts either way — and the case of a device
    that holds another profile's rows, which cannot be told apart from a
    reset that did not take effect. The second case is refused rather than
    measured: the counts of a database that holds rows of two profiles
    belong to neither of them.
    """
    counts: dict[str, int] = {}
    scope: str | None = None
    for _ in range(attempts):
        warm_up_app(serial)
        # The log is cleared first: the marker of an earlier wipe would be
        # read as this one's.
        adb("logcat", "-c", serial=serial)
        code, out = adb(
            "shell",
            "am",
            "start",
            "-a",
            "android.intent.action.VIEW",
            "-d",
            "todo-debug://seed?reset=content",
            serial=serial,
        )
        if code != 0:
            raise RunError(f"the profile's content could not be reset:\n{out.strip()}")
        elapsed = wait_for_reset_marker(serial)
        if out_dir is None:
            return {}, elapsed, scope
        db_path = pull_database(serial, out_dir / "reset-db")
        raw = count_rows(db_path)
        unreadable = sorted(key for key, value in raw.items() if value < 0)
        if unreadable:
            raise RunError(
                f"the tables {', '.join(unreadable)} could not be read, so the wipe "
                "cannot be confirmed"
            )
        removed = removed_scopes(before_db, db_path) if before_db is not None else set()
        scope = removed.pop() if len(removed) == 1 else None
        if scope is not None:
            counts = count_rows(db_path, scope)
            if not any(value > 0 for value in counts.values()):
                return counts, elapsed, scope
            # The profile still holds rows: the reset did not take effect.
            continue
        # The reset removed nothing, so no scope names the profile under test.
        # The profile held nothing to begin with — the counts of an empty
        # database are the profile's counts — or the device holds another
        # profile's rows, which is not to be told apart from a reset that
        # did not take effect. The second case is refused below.
        if before_db is None or not content_row_total(before_db):
            return raw, elapsed, None
    if scope is None:
        raise RunError(
            "the profile's content was not wiped — the reset removed none of the rows "
            "the device held, and nothing names the scope they belong to: either the "
            "reset did not take effect, or they belong to another profile. In both "
            "cases the counts cannot be attributed to the profile under test."
        )
    raise RunError(f"the profile's content was not wiped — it still holds {counts}")


def content_row_total(db_path: Path) -> int:
    """How many rows the content tables hold, whatever scope they belong to."""
    counts = count_rows(db_path)
    return sum(value for value in counts.values() if value > 0)


def wake_unlock(serial: str) -> None:
    """Wake the device and dismiss its lockscreen.

    A locked device shows the lock screen, not the app: every assertion of a
    flow that runs against it is made against a screen in front of the one it
    describes, and the run reports a failure that has nothing to do with the
    restore. The sequence is the one the repository's own device skill uses,
    and the swipe is the fallback for a device that does not take the menu
    key.
    """
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP", serial=serial)
    time.sleep(1)
    adb("shell", "input", "keyevent", "82", serial=serial)
    time.sleep(1)
    code, out = adb("shell", "dumpsys", "window", serial=serial)
    if code == 0 and "mDreamingLockscreen=true" in out:
        adb("shell", "input", "swipe", "500", "1500", "500", "500", serial=serial)
        time.sleep(1)


def prewarm_app(serial: str) -> None:
    """Start the app before Maestro is asked to.

    Maestro's `launchApp` waits for the app to report itself ready, and on a
    busy host the cold start of the app — the database opens, the first query
    runs — takes longer than that wait. The flow then hangs on the launch,
    although the phone is fine: asked directly, the app is up in a second. The
    start here makes the launch that follows a warm one, and the flow carries
    on where it would have carried on.
    """
    adb("shell", "am", "start", "-n", f"{APP_ID}/.MainActivity", serial=serial, timeout=60)
    time.sleep(2)


def keep_awake(serial: str) -> None:
    """Stop the device from locking itself while the run is watching it.

    A phone's screen times out after a minute or two, and the run spends far
    longer than that watching an import. When the screen goes off the device
    shows its lock screen, the screen dumps read that instead of the app, and
    the run concludes that the import said nothing — when in fact the import
    finished while the phone was asleep.

    So the screen is held on for the length of the run. The setting is the
    device's own `stay_on_while_plugged_in` in developer options, which is what
    a plugged-in test phone normally has set anyway.
    """
    adb("shell", "svc", "power", "stayon", "true", serial=serial)


#: What a locked screen looks like in a dump. The system UI's own ids are
#: there, and the app's are not.
LOCKED_MARKERS = ("keyguard_", "com.android.systemui:id/keyguard")


def screen_is_locked(dump: str) -> bool:
    """Whether the dump was taken of the lock screen rather than the app."""
    return any(marker in dump for marker in LOCKED_MARKERS)


# ── the import, through the app's own path ───────────────────────────────────


def find_maestro() -> str:
    """Where Maestro is.

    The repository's own runner knows where Maestro is; this command drives
    Maestro directly, and a shell that does not carry `~/.maestro/bin` on its
    PATH is not a reason for the run to fail with "no such file".
    """
    found = shutil.which("maestro")
    if found:
        return found
    candidate = Path.home() / ".maestro" / "bin" / "maestro"
    if candidate.is_file():
        return str(candidate)
    raise RunError("maestro was not found on PATH nor in ~/.maestro/bin")


def host_version(command: list[str]) -> str:
    """The first line a tool prints about itself, or "unknown".

    The tools that answer on stderr (`java -version`) and the ones that answer
    on stdout are both read, and a tool that is not installed is not a reason
    to stop the run.
    """
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.SubprocessError):
        return "unknown"
    lines = (result.stdout or "").strip().splitlines() or (
        result.stderr or ""
    ).strip().splitlines()
    return lines[0].strip() if lines else "unknown"


def tool_versions(serial: str) -> dict[str, str]:
    """The versions of the tools the run used.

    A measurement without the version of the tool that produced it cannot be
    compared with the next one, so the report carries them. The commit the run
    was made from belongs there too: the same profile restored from another
    commit is another measurement, and a report that does not say which commit
    it came from cannot be reproduced.
    """
    versions = {}
    try:
        result = subprocess.run(
            [find_maestro(), "--version"],
            capture_output=True,
            text=True,
            timeout=120,
        )
        lines = (result.stdout or "").strip().splitlines()
        versions["maestro"] = lines[0].strip() if result.returncode == 0 and lines else "unknown"
    except (OSError, subprocess.SubprocessError):
        versions["maestro"] = "unknown"
    code, out = adb("shell", "getprop", "ro.build.version.release", serial=serial)
    versions["android"] = out.strip() if code == 0 and out.strip() else "unknown"
    versions["adb"] = host_version(["adb", "version"])
    versions["jdk"] = host_version(["java", "-version"])
    versions["commit"] = host_version(["git", "rev-parse", "HEAD"])
    versions["dirty"] = repo_is_dirty()
    return versions


def repo_is_dirty() -> str:
    """Whether the tree the run was made from had changes in it.

    A run made from a tree with changes in it is not the same measurement as
    one made from the commit it names, and the report has to say so.
    """
    try:
        result = subprocess.run(
            ["git", "status", "--porcelain"], capture_output=True, text=True, timeout=60
        )
    except (OSError, subprocess.SubprocessError):
        return "unknown"
    if result.returncode != 0:
        return "unknown"
    return "yes" if result.stdout.strip() else "no"


def flow_command(serial: str, flow: str, env: dict[str, str]) -> list[str]:
    """The command that runs one of this command's flows on the named device.

    Maestro is driven directly: these flows are not in the repository's
    certified suite, so the suite's runner does not know them.
    """
    command = [find_maestro(), "test", "--udid", serial, "--no-ansi"]
    for key, value in env.items():
        command += ["-e", f"{key}={value}"]
    command.append(str(FLOW_DIR / flow))
    return command


#: What Maestro writes when its driver on the device does not come up. It is
#: the device's answer, not the flow's: the flow never ran. A phone shared with
#: another session refuses the driver now and then, and a run that reports that
#: as "the restore failed" is measuring the wrong thing.
DRIVER_FAILED = "did not start up in time"


def run_flow(
    serial: str,
    flow: str,
    env: dict[str, str],
    log: Path,
    timeout: int = 1200,
    apk: Path | None = None,
) -> bool:
    """Run one of this command's flows; return whether it passed.

    A flow that never got a driver is run again: the device was busy, and the
    run is about the restore, not about the device's mood.
    """
    for attempt in (1, 2):
        command = flow_command(serial, flow, env)
        print(f"flow: {flow}")
        wake_unlock(serial)
        prewarm_app(serial)
        if apk is not None:
            verify_apk_unchanged(serial, apk)
        with log.open("w", encoding="utf-8") as handle:
            try:
                cp = subprocess.run(
                    command,
                    cwd=ROOT,
                    stdout=handle,
                    stderr=subprocess.STDOUT,
                    timeout=timeout,
                )
            except (OSError, subprocess.SubprocessError) as exc:
                raise RunError(f"the flow {flow} could not be started: {exc}") from exc
        if cp.returncode == 0:
            return True
        if DRIVER_FAILED not in log.read_text(encoding="utf-8", errors="replace"):
            return False
        print(f"the driver of {flow} did not come up; running it again")
    return False


def ui_dump(serial: str) -> str:
    """The current screen's UI tree, as XML."""
    code, out = adb("exec-out", "uiautomator", "dump", "/dev/tty", serial=serial, timeout=60)
    return out if code == 0 else ""


def dialog_text(dump: str) -> str:
    """The words on the screen, joined.

    The dialog that reports a failed import carries the app's own message in
    its body. Reading the words is what tells a refusal for the archive's
    size apart from a refusal for any other reason — the tag of the dialog
    only says that something failed.
    """
    return " ".join(re.findall(r'text="([^"]*)"', dump))


def mentions_budget(message: str) -> bool:
    """Whether the app's message names the archive's size as the reason.

    The app's budget check fails an archive with a message of its own, and
    only that check writes those words. A failure that does not say that is
    a failure of something else, and saying so is the whole point of the
    over-budget check.
    """
    lowered = message.lower()
    return any(marker in lowered for marker in BUDGET_REFUSAL_MARKERS)


def restore_started(serial: str, timeout: int = 120) -> tuple[bool, str]:
    """Whether the app says that it is restoring, and what it said.

    The tap on the confirmation is not evidence that the restore began: it
    only says that Maestro dispatched a tap, and on a busy device the app may
    never get as far as the import. The app's own indicator is the evidence —
    the backup screen shows "Working…" from the moment the restore starts
    until it ends, so the words are on the screen exactly while the import
    runs. A snackbar that says the restore is complete means the import was
    too quick to be cut short, and the case was not tested at all.

    The screen is only read after the flow has let go of the device: reading
    it while Maestro drives takes the accessibility service away from
    Maestro's driver.
    """
    deadline = time.monotonic() + timeout
    while True:
        words = dialog_text(ui_dump(serial))
        if WORKING_MARKER.search(words):
            return True, "the app's own working indicator was on the screen"
        if RESTORE_DONE_MARKER in words:
            return False, "the restore finished before it could be cut short"
        if time.monotonic() >= deadline:
            return False, "the app never showed that it was working"
        time.sleep(1.0)


def import_duration(
    started: float,
    tapped: float | None,
    snackbar: float | None,
    error_at: float | None,
) -> tuple[float | None, str]:
    """How long the import took, and where that number came from.

    The import starts when the confirmation is tapped and ends when the app
    reports it. The report is a snackbar for an import that worked and a
    dialog for one that did not, so the two are different ends and each is
    measured from the tap.
    """
    if snackbar is not None and tapped is not None:
        return snackbar - tapped, "confirm-to-snackbar"
    if snackbar is not None:
        return snackbar - started, "flow-to-snackbar"
    if error_at is not None and tapped is not None:
        return error_at - tapped, "confirm-to-error-dialog"
    return None, "none"


def wait_for_flow_exit(process: subprocess.Popen, timeout: int) -> bool:
    """Wait for the Maestro flow to let go of the device.

    Maestro writes the line about the confirmation tap before it performs the
    tap, so a flow that is still running may still be holding the device's
    accessibility service. Dumping the screen in that moment takes the
    service away from Maestro's driver, the driver fails to start, and the
    watch reads a screen that the flow is in the middle of changing.

    The wait is bounded: a flow that does not let go is a flow that is stuck,
    and a stuck flow must not keep the watch from reporting what it saw.
    """
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            return True
        time.sleep(0.25)
    return process.poll() is not None


def restore_and_watch(
    serial: str, archive_name: str, log: Path, timeout: int = 1800, apk: Path | None = None
) -> dict:
    """Restore the archive and watch the screen while it happens.

    The import is measured between two moments: the confirmation being tapped
    — the import starts there — and the snackbar that reports it — the import
    ends there.

    The first moment is read off the flow's own output, not off the screen.
    Dumping the screen while Maestro drives it takes the device's
    accessibility service away from Maestro's driver, and the driver then
    fails to start; the screen is only read once the flow has done its last
    step and the import is running on its own.

    A flow whose driver never came up is run again: the device refused it,
    the import never started, and a run that read that as "the import failed"
    would report a failure that never happened.
    """
    for attempt in (1, 2):
        outcome = _restore_and_watch_once(serial, archive_name, log, timeout, apk)
        if attempt == 1 and DRIVER_FAILED in log.read_text(encoding="utf-8", errors="replace"):
            print("the driver of the flow did not come up; running the flow again")
            continue
        return outcome
    return outcome


def _restore_and_watch_once(
    serial: str, archive_name: str, log: Path, timeout: int, apk: Path | None = None
) -> dict:
    """One attempt at the restore: the flow, and the watch beside it."""
    command = flow_command(serial, "restore-archive.yaml", {"ARCHIVE_NAME": archive_name})
    print("flow: restore-archive.yaml")
    wake_unlock(serial)
    if apk is not None:
        verify_apk_unchanged(serial, apk)
    started = time.monotonic()
    tapped: float | None = None
    tap_started = False
    screen_phase = False
    screen_started = started
    snackbar: float | None = None
    error_at: float | None = None
    error_text = ""
    flow_overlapped = False
    with log.open("w", encoding="utf-8") as handle:
        process = subprocess.Popen(
            command,
            cwd=ROOT,
            stdout=handle,
            stderr=subprocess.STDOUT,
        )
        try:
            while True:
                now = time.monotonic()
                if not screen_phase:
                    text = log.read_text(encoding="utf-8", errors="replace")
                    if tapped is None and CONFIRM_TAP_DONE in text:
                        # The tap was dispatched: Maestro writes the line about
                        # the step when the step is done. The import cannot
                        # have started before this moment, so the duration is
                        # measured from here and never overstates it.
                        tapped = now
                    if CONFIRM_TAP in text and not tap_started:
                        # The step that taps the confirmation has started. The
                        # screen is only read once the flow has let go of the
                        # device: a dump taken while the flow still holds the
                        # accessibility service starves Maestro's driver.
                        tap_started = True
                        if not wait_for_flow_exit(process, FLOW_EXIT_WAIT_SECONDS):
                            flow_overlapped = True
                            print(
                                "the flow was still running when the watch started "
                                "reading the screen"
                            )
                        screen_phase = True
                        screen_started = time.monotonic()
                        text = log.read_text(encoding="utf-8", errors="replace")
                        if tapped is None and CONFIRM_TAP_DONE in text:
                            # The step is done, so the confirmation was
                            # dispatched. The moment is read after the flow
                            # has let go of the device, which is a little
                            # later than the tap itself: the duration is then
                            # a little shorter than the import really took,
                            # and never longer.
                            tapped = time.monotonic()
                    elif process.poll() is not None:
                        # The flow is over and it never got to the confirmation.
                        break
                else:
                    # The import can only have started after the confirmation
                    # was tapped; when the tap's moment is not known, the
                    # moment the screen started being read stands in for it.
                    import_started = tapped if tapped is not None else screen_started
                    dump = ui_dump(serial)
                    if screen_is_locked(dump):
                        # The phone locked itself while the import was
                        # running. The lock screen is dismissed and the watch
                        # goes on — the import is still the thing being
                        # measured, and a locked phone says nothing about it.
                        wake_unlock(serial)
                        dump = ui_dump(serial)
                    stamp = time.monotonic()
                    if ERROR_DIALOG_ID in dump:
                        # The app refused the archive and said so in the
                        # dialog it shares with other failures. The dialog
                        # stays up until it is dismissed, so the watch ends
                        # here: the import is over, it did not go well, and
                        # the dialog is the report.
                        error_at = stamp
                        error_text = dialog_text(dump)
                        break
                    if SNACKBAR_ID in dump:
                        snackbar = stamp
                        break
                    if stamp - import_started > SILENT_IMPORT_SECONDS:
                        # The import is over but the app has not said so —
                        # the snackbar was missed, or there was none. The
                        # counts in the database are what settles it.
                        break
                if now - started > timeout:
                    break
                time.sleep(0.5)
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=60)
                except subprocess.TimeoutExpired:
                    process.kill()
    flow_seconds = time.monotonic() - started
    duration, measured = import_duration(started, tapped, snackbar, error_at)
    return {
        "flowSeconds": round(flow_seconds, 1),
        "durationSeconds": round(duration, 1) if duration is not None else None,
        "measured": measured,
        "confirmedAt": round(tapped - started, 1) if tapped is not None else None,
        "reportedBySnackbar": snackbar is not None,
        "flowOverlappedWithWatch": flow_overlapped,
        "errorDialog": error_at is not None,
        "errorDialogText": error_text,
        "log": str(log),
    }


# ── the database on the device ───────────────────────────────────────────────


def stop_app(serial: str, timeout: int = 30) -> bool:
    """Stop the app and wait for its process to be gone.

    A database that is copied while the app is writing it is a torn copy:
    the main file and the write-ahead log are read at different moments, and
    SQLite replays one over the other. The result is a database that holds
    rows the app has deleted, and a run that reads it reports an import that
    left rows behind when nothing was left behind.
    """
    adb("shell", "am", "force-stop", APP_ID, serial=serial)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        code, out = adb("shell", "pidof", APP_ID, serial=serial, timeout=30)
        if code != 0 or not out.strip():
            return True
        time.sleep(0.5)
    return False


def pulled_db_path(out_dir: Path) -> Path:
    """Where `pull_database` puts the copy it pulled.

    The copy lives in a directory of its own inside the run's output, so a
    caller that needs the copy it pulled — the state of the database before a
    wipe, say — looks it up here instead of guessing the layout.
    """
    return out_dir / "device-db" / "todo.db"


def pull_database(serial: str, out_dir: Path, stop: bool = True) -> Path:
    """Copy the app's database (and its write-ahead log) off the device.

    The log is copied too: SQLite keeps the rows that were written last in
    `todo.db-wal`, and a copy without it is a database that is missing exactly
    the rows the import wrote.

    The app is stopped first, so that the three files are one moment of the
    database and not three. If the app refuses to stop, there is no such
    moment, and the copy is refused too: a torn database read as a whole one
    is worse than no database at all.
    """
    if stop and not stop_app(serial):
        raise RunError(
            "the app did not stop within the timeout, so the database cannot be "
            "copied as one moment: the copy would be a torn one"
        )
    target = out_dir / "device-db"
    target.mkdir(parents=True, exist_ok=True)
    # The old copy goes first. A write-ahead log that is left over from an
    # earlier copy is replayed over the new database, and the rows the app
    # has deleted come back with it: the copy then holds rows that are
    # really gone, and a run reads that as an import that left rows behind.
    for stale in target.glob("todo.db*"):
        stale.unlink()
    for name in ("todo.db", "todo.db-wal", "todo.db-shm"):
        code, out = adb_bytes(
            "exec-out",
            "run-as",
            APP_ID,
            "cat",
            f"databases/{name}",
            serial=serial,
            timeout=900,
        )
        if code != 0:
            # A missing `-wal` or `-shm` is normal: SQLite removes them when
            # the last connection closes.
            if name == "todo.db":
                raise RunError(f"the database could not be read from the device:\n{out!r}")
            continue
        (target / name).write_bytes(out)
    return target / "todo.db"


def database_bytes(db_dir: Path) -> int:
    """How much space the database takes on the device."""
    return sum(p.stat().st_size for p in db_dir.glob("todo.db*"))


def scoped_user_id(profile_id: str, owner_id: str) -> str:
    """The scope the rows of a profile carry.

    The app's own mapping — `ProfileAwareCurrentUser.scopedUserIdFor`: the
    rows of the default profile carry the plain user id, and the rows of
    every other profile carry the profile's id in front of the user id.
    """
    if profile_id == DEFAULT_PROFILE_ID:
        return owner_id
    return f"{profile_id}/{owner_id}"


def rows_of(db_path: Path, table: str, columns: str) -> list[tuple]:
    """The rows of a table, or nothing when the table cannot be read."""
    connection = None
    try:
        connection = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
        return connection.execute(f"SELECT {columns} FROM {table}").fetchall()
    except sqlite3.Error:
        return []
    finally:
        if connection is not None:
            connection.close()


def content_scopes(db_path: Path) -> set[str]:
    """The scopes the content tables hold rows of."""
    scopes: set[str] = set()
    for table in SCOPED_TABLES:
        for row in rows_of(db_path, table, "DISTINCT user_id"):
            if row and row[0]:
                scopes.add(str(row[0]))
    return scopes


def removed_scopes(before_db: Path, after_db: Path) -> set[str]:
    """The scopes of the rows the profile reset removed.

    The reset deletes the rows of the active profile and nothing else, so the
    rows that are gone after it are the active profile's rows, and the scope
    they carry is the scope the measurement has to count.
    """
    scopes: set[str] = set()
    for table in SCOPED_TABLES:
        before = {str(row[0]): str(row[1]) for row in rows_of(before_db, table, "id, user_id")}
        after = {str(row[0]) for row in rows_of(after_db, table, "id")}
        for row_id, scope in before.items():
            if row_id not in after:
                scopes.add(scope)
    return scopes


def dataset_scope(db_path: Path, task_ids: list[str]) -> str | None:
    """The scope the dataset's own rows carry.

    The archive names the id of every row it carries, and the app writes
    those rows into the profile that is active while the restore runs. The
    scope of the rows whose ids are the archive's is therefore the scope the
    restore wrote into — the one thing the measurement is about.
    """
    if not task_ids:
        return None
    connection = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    scopes: set[str] = set()
    try:
        for start in range(0, len(task_ids), 500):
            chunk = task_ids[start : start + 500]
            placeholders = ",".join("?" * len(chunk))
            found = connection.execute(
                f"SELECT DISTINCT user_id FROM tasks WHERE id IN ({placeholders})", chunk
            ).fetchall()
            scopes.update(str(row[0]) for row in found if row and row[0])
    except sqlite3.Error:
        return None
    finally:
        connection.close()
    return scopes.pop() if len(scopes) == 1 else None


def count_rows(db_path: Path, scope: str | None = None) -> dict[str, int]:
    """How many rows each of the manifest's tables holds.

    The count is the count of the profile under test. A device can hold
    more than one profile, and the rows of the other profiles are not the
    restore's rows: counting the whole database would report them as a
    difference between the manifest and the restore. So the count is taken
    for one scope — the tables that carry it directly are filtered by their
    `user_id`, and the tables that hang off a task are counted through the
    task they belong to.

    Without a scope the count is the whole database's, which is the
    profile's count only when the database holds nothing else.
    """
    try:
        connection = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    except sqlite3.Error:
        # A database that cannot be opened holds no known counts: every table
        # comes back as "could not be read".
        return {key: -1 for key in COUNT_TABLES}
    try:
        counts = {}
        for key, table in COUNT_TABLES.items():
            query, params = count_query(table, scope)
            try:
                row = connection.execute(query, params).fetchone()
            except sqlite3.Error:
                counts[key] = -1
                continue
            counts[key] = int(row[0]) if row else -1
        return counts
    finally:
        connection.close()


def count_query(table: str, scope: str | None) -> tuple[str, list]:
    """The query that counts one table, for one scope."""
    if scope is None:
        return f"SELECT COUNT(*) FROM {table}", []
    if table in SCOPED_TABLES:
        return f"SELECT COUNT(*) FROM {table} WHERE user_id = ?", [scope]
    return (
        f"SELECT COUNT(*) FROM {table} c JOIN tasks t ON c.task_id = t.id "
        "WHERE t.user_id = ?",
        [scope],
    )


def compare_counts(expected: dict, actual: dict) -> list[str]:
    """The differences between the manifest's counts and the database's.

    A count that is missing from the database is a difference too: a table
    that could not be read is not a table with no rows.
    """
    problems = []
    for key, want in expected.items():
        if key not in actual:
            problems.append(f"{key}: the database has no such table")
            continue
        got = actual[key]
        if got != want:
            problems.append(f"{key}: the manifest says {want}, the database holds {got}")
    return problems


def relationship_problems(db_path: Path, scope: str | None = None) -> list[str]:
    """The rows that point at a row that is not there.

    A restore that wrote the rows but not the links between them leaves a
    database that answers "how many" correctly and "of what" wrongly, so the
    links are counted, not assumed.

    The check belongs to the profile under test wherever the broken row can
    be attributed to one: a row that hangs off a task belongs to the scope of
    the task it hangs off, and a task belongs to its own scope. A row whose
    task is gone hangs off nothing and cannot be attributed to any profile,
    so it is reported whatever profile is under test.
    """
    checks = {
        "task_tags without a task": (
            "SELECT COUNT(*) FROM task_tags tt "
            "LEFT JOIN tasks t ON tt.task_id = t.id "
            "LEFT JOIN tags g ON tt.tag_id = g.id "
            "WHERE t.id IS NULL",
            "g.user_id = ?",
        ),
        "task_tags without a tag": (
            "SELECT COUNT(*) FROM task_tags tt "
            "LEFT JOIN tags g ON tt.tag_id = g.id "
            "LEFT JOIN tasks t ON tt.task_id = t.id "
            "WHERE g.id IS NULL",
            "t.user_id = ?",
        ),
        "task_dependencies without a task": (
            "SELECT COUNT(*) FROM task_dependencies d "
            "LEFT JOIN tasks t ON d.task_id = t.id "
            "JOIN tasks o ON d.depends_on_task_id = o.id "
            "WHERE t.id IS NULL",
            "o.user_id = ?",
        ),
        "task_dependencies without the depended-on task": (
            "SELECT COUNT(*) FROM task_dependencies d "
            "JOIN tasks t ON d.task_id = t.id "
            "LEFT JOIN tasks o ON d.depends_on_task_id = o.id "
            "WHERE o.id IS NULL",
            "t.user_id = ?",
        ),
        "checklist_items without a task": (
            "SELECT COUNT(*) FROM checklist_items c "
            "LEFT JOIN tasks t ON c.task_id = t.id "
            "WHERE t.id IS NULL",
            None,
        ),
        "tasks without a project": (
            "SELECT COUNT(*) FROM tasks t "
            "LEFT JOIN projects p ON t.project_id = p.id "
            "WHERE t.project_id IS NOT NULL AND p.id IS NULL",
            "t.user_id = ?",
        ),
        "subtasks without a parent task": (
            "SELECT COUNT(*) FROM tasks t "
            "LEFT JOIN tasks p ON t.parent_task_id = p.id "
            "WHERE t.parent_task_id IS NOT NULL AND p.id IS NULL",
            "t.user_id = ?",
        ),
    }
    connection = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    try:
        problems = []
        for what, (query, scope_column) in checks.items():
            params: list = []
            if scope is not None and scope_column is not None:
                query += f" AND {scope_column}"
                params = [scope]
            try:
                row = connection.execute(query, params).fetchone()
            except sqlite3.Error as exc:
                problems.append(f"{what}: the check could not run ({exc})")
                continue
            if row and int(row[0]) > 0:
                problems.append(f"{what}: {row[0]}")
        return problems
    finally:
        connection.close()


def number_of(title: str) -> int:
    """The number in a generated title, or -1 when it carries none."""
    digits = "".join(char for char in title if char.isdigit())
    return int(digits) if digits else -1


def related_task(db_path: Path, scope: str | None = None) -> tuple[str, str] | None:
    """The task the app will show relations for: its id and its title.

    The related-tasks section of the task detail is folded away until the
    task has relations, so the picker can only be reached through a task that
    has some. The app reads a task's relations from the edges the task
    *holds* — the ones it blocks (`task_dependencies.task_id`), not the ones
    that point at it — so only such a task shows the section.

    The title is the one of a task whose title is unique in the dataset, so a
    search for it finds that task and not a neighbour of it.

    The search is over the profile's rows: another profile's task is not a
    task of the dataset that was restored.
    """
    connection = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    try:
        tasks = dict(
            connection.execute(
                """
                SELECT id, title FROM tasks
                WHERE id IN (SELECT task_id FROM task_dependencies)
                """
                + scope_filter(scope, "user_id = ?"),
                scope_params(scope),
            ).fetchall()
        )
        counts = dict(
            connection.execute(
                "SELECT title, COUNT(*) FROM tasks"
                + scope_filter(scope, "user_id = ?", " WHERE ")
                + " GROUP BY title",
                scope_params(scope),
            ).fetchall()
        )
    except sqlite3.Error as exc:
        raise RunError(f"the restored database cannot be read: {exc}") from exc
    finally:
        connection.close()
    unique = [(task_id, title) for task_id, title in tasks.items() if counts.get(title) == 1]
    if not unique:
        return None
    # The title with the highest number in it: a search for it finds one row,
    # where a search for a low number finds its neighbours as well.
    return max(unique, key=lambda pair: (number_of(pair[1]), pair[1]))


def related_task_title(db_path: Path, scope: str | None = None) -> str | None:
    """The title of a task that the app will show relations for."""
    task = related_task(db_path, scope)
    return task[1] if task is not None else None


def picker_candidate(db_path: Path, current_id: str, scope: str | None = None) -> tuple[str, str] | None:
    """A task the related-tasks picker can offer, and the query that finds it.

    The picker refuses the task itself, the tasks it already holds relations
    to, and every candidate whose addition would close a cycle. A task that
    holds no relations of its own can never close a cycle, so the candidate
    is taken from the tasks that hold none.

    The title is the query, and the query is a phrase: the picker looks for
    the words of the title next to each other, the last of them unfinished. A
    number that begins another number's would find that neighbour too, so the
    candidate's number is one that no other number of the dataset begins
    with — the largest number of the dataset decides that, not the count of
    the tasks.

    The candidates are the profile's tasks: the picker searches the profile
    that was restored, and a task of another profile is not in it.
    """
    connection = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    try:
        rows = connection.execute(
            """
            SELECT id, title FROM tasks
            WHERE id != ?
              AND id NOT IN (SELECT task_id FROM task_dependencies)
              AND id NOT IN (
                    SELECT depends_on_task_id FROM task_dependencies WHERE task_id = ?
              )
            """
            + scope_filter(scope, "user_id = ?"),
            [current_id, current_id] + scope_params(scope),
        ).fetchall()
        titles = connection.execute(
            "SELECT title FROM tasks" + scope_filter(scope, "user_id = ?", " WHERE "),
            scope_params(scope),
        ).fetchall()
        counts = dict(
            connection.execute(
                "SELECT title, COUNT(*) FROM tasks"
                + scope_filter(scope, "user_id = ?", " WHERE ")
                + " GROUP BY title",
                scope_params(scope),
            ).fetchall()
        )
    except sqlite3.Error as exc:
        raise RunError(f"the restored database cannot be read: {exc}") from exc
    finally:
        connection.close()
    numbers = [number_of(title) for (title,) in titles]
    largest = max(numbers) if numbers else 0
    candidates = [
        (task_id, title)
        for task_id, title in rows
        if counts.get(title) == 1
        and (number := number_of(title)) > 0
        and 10 * number > largest
    ]
    if not candidates:
        return None
    return max(candidates, key=lambda pair: (number_of(pair[1]), pair[1]))


def task_titles(db_path: Path, scope: str | None = None) -> list[str]:
    """Every task title in the pulled database, for one scope."""
    connection = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    try:
        rows = connection.execute(
            "SELECT title FROM tasks" + scope_filter(scope, "user_id = ?", " WHERE "),
            scope_params(scope),
        ).fetchall()
    except sqlite3.Error as exc:
        raise RunError(f"the restored database cannot be read: {exc}") from exc
    finally:
        connection.close()
    return [title for (title,) in rows if title]


def slug(text: str) -> str:
    """The app's own slug of a title — the same shape as `TestTags.slug`."""
    out = []
    pending_separator = False
    for char in text.strip().lower():
        # Kotlin's `isLetterOrDigit` is Lu/Ll/Lt/Lm/Lo plus Nd — `isalpha()`
        # and `isdecimal()` are exactly those two sets.
        if char.isalpha() or char.isdecimal():
            if pending_separator and out:
                out.append("_")
            out.append(char)
            pending_separator = False
        else:
            pending_separator = True
    return "".join(out)


def task_item_id(title: str) -> str:
    """The test tag of the row that shows a task with this title."""
    return f"task_item_{slug(title)}"


def first_task_row(serial: str) -> str | None:
    """The test tag of the first task row on the screen.

    Read off the screen rather than guessed from the database: the Inbox of a
    large dataset holds thousands of rows, and the row the flow opens has to
    be a row that was really on the screen.

    The dump is scanned rather than parsed. The device writes the tree and
    then whatever the shell said, and on a screen full of rows it can cut
    the tree off in the middle — a reader that insists on a whole document
    finds nothing at all, and reports an imported dataset that is really
    there as an empty one.
    """
    dump = ui_dump(serial)
    for match in NODE_TAG.finditer(dump):
        attributes = dict(NODE_ATTRIBUTE.findall(match.group(0)))
        tag = attributes.get("resource-id", "")
        if tag.startswith("task_item_"):
            return tag
    return None


def title_for_tag(db_dir: Path, tag: str, scope: str | None = None) -> str | None:
    """The title of the task whose row carries this test tag.

    The row's tag is the app's slug of the title, so the title is the one
    whose slug is the tag. It is taken from the database rather than from the
    screen: the screen's row holds the title among several other texts,
    and the wrong one would send the flow looking for a title that is not
    there.
    """
    wanted = tag.removeprefix("task_item_")
    for title in task_titles(db_dir, scope):
        if slug(title) == wanted:
            return title
    return None


def wait_for_task_row(serial: str, timeout: int = 180) -> str | None:
    """Wait for the task list to show a row, and read its test tag.

    A list of a few thousand rows takes a moment to come up after the app
    starts, and the screen is empty until it does. Reading the screen once
    and giving up would call an imported dataset that is really there a
    failure.
    """
    deadline = time.monotonic() + timeout
    while True:
        row = first_task_row(serial)
        if row is not None:
            return row
        if time.monotonic() >= deadline:
            return None
        time.sleep(2)


def app_memory(serial: str) -> str:
    """What the app is holding in memory, as the device reports it."""
    code, out = adb("shell", "dumpsys", "meminfo", APP_ID, serial=serial, timeout=60)
    if code != 0:
        return ""
    for line in out.splitlines():
        if "TOTAL PSS" in line:
            return line.strip()
    return ""


# ── the report ───────────────────────────────────────────────────────────────


def write_report(out_dir: Path, report: dict) -> None:
    """Write the measurements as JSON and as a page a person can read.

    A report can be partial: the run may have died in the middle of it. What
    was measured is written, and what was not says so — a run that dies after
    the import has still measured the archive, the import and the counts, and
    those are the measurements the report is for.
    """
    (out_dir / "report.json").write_text(
        json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    device = report.get("device", {})
    apk = report.get("apk", {})
    dataset = report.get("dataset", {})
    database = report.get("database", {})
    imported = report.get("import", {})
    lines = [
        "# Restoring a synthetic dataset into the Android app",
        "",
        f"* status: {report.get('status', 'incomplete')}"
        + (f" — {report['error']}" if report.get("error") else ""),
        f"* phases: {', '.join(report.get('phases', [])) or 'none'}",
    ]
    if report.get("failure"):
        lines.insert(
            1,
            "* what failed: "
            + (
                "the restore was attempted and the app did not hold up"
                if report["failure"] == "verification"
                else "the run could not measure what it claims to measure"
            ),
        )
    if device:
        lines.append(
            f"* device: `{device.get('serial')}` — {device.get('model')}, "
            f"Android {device.get('androidRelease')} (SDK {device.get('sdk')})"
        )
    if apk:
        lines.append(
            f"* build: `{apk.get('version')}` ({apk.get('variant')}) — {apk.get('path')}"
        )
        if apk.get("digest"):
            lines.append(f"* apk digest: `{apk['digest']}`")
    if report.get("tools"):
        lines.append(f"* tools: Maestro {report['tools'].get('maestro', '—')}")
    if dataset:
        lines.append(
            f"* dataset: profile `{dataset.get('profile')}`, seed `{dataset.get('seed')}`"
        )
        lines.append(
            f"* archive: {dataset.get('archiveBytes')} bytes on disk, "
            f"{dataset.get('decompressedBytes')} bytes decompressed"
        )
    if imported:
        lines.append(
            f"* import: {imported.get('durationSeconds') or '—'} s "
            f"({imported.get('measured')})"
        )
        lines.append(f"* memory: {imported.get('memory') or '—'}")
    if database:
        lines.append(f"* database: {database.get('bytes')} bytes")
    lines.append(
        "* wipe of the profile before the run: "
        f"{report.get('wipe', {}).get('seconds', '—')} s"
    )
    profile = report.get("profile", {})
    if profile:
        lines.append(
            f"* profile scope: `{profile.get('scope') or 'unknown'}` "
            f"({profile.get('source')})"
        )
    if dataset.get("counts") and database.get("counts"):
        lines += [
            "",
            "## Counts",
            "",
            "| entity | manifest | database |",
            "|---|---|---|",
        ]
        for key, want in dataset["counts"].items():
            got = database["counts"].get(key)
            lines.append(f"| {key} | {want} | {got} |")
        subtasks = database.get("subtasks")
        if subtasks:
            lines.append(f"| subtasks | {subtasks.get('expected')} | {subtasks.get('actual')} |")
    if database.get("linkIdentityProblems"):
        lines += ["", "## The links that did not survive the restore", ""]
        lines += [f"* {problem}" for problem in database["linkIdentityProblems"]]
    if report.get("negative"):
        lines += ["", "## The ways it can go wrong", ""]
        lines += [
            f"* **{name}**: {phase.get('verdict', '—')}"
            for name, phase in report["negative"].items()
        ]
        evidence = report["negative"].get("interrupted", {}).get("evidence")
        if evidence:
            lines.append(f"* how the interrupted import was caught: {evidence}")
    lines += ["", "## Verdict", ""]
    lines += [f"* {problem}" for problem in report.get("verdict", {}).get("problems", [])] or [
        "* nothing to report"
    ]
    lines.append("")
    (out_dir / "report.md").write_text("\n".join(lines), encoding="utf-8")


# ── the run ──────────────────────────────────────────────────────────────────


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    """The command line."""
    parser = argparse.ArgumentParser(
        prog="verify-large-restore.py",
        description="Restore a synthetic backup into the Android app and check it.",
    )
    parser.add_argument(
        "--serial",
        default=os.environ.get("SERIAL", ""),
        help="the device to run on (also read from SERIAL); there is no default",
    )
    parser.add_argument(
        "--apk",
        default="",
        help="an APK to install instead of building this checkout's debug build",
    )
    parser.add_argument(
        "--profile",
        default="10k",
        help="the generator profile (1k, 10k, 50k, 100k), default 10k; the name is "
        "used as a file name, so it has to be a plain word",
    )
    parser.add_argument("--seed", type=int, default=42, help="the generator seed, default 42")
    parser.add_argument(
        "--out",
        default="",
        help="where the archive, the logs and the report go; by default a new "
        "directory under build/ that no earlier run has used",
    )
    return parser.parse_args(argv)


#: The files a finished run leaves in its output directory. A directory that
#: holds any of them holds the results of an earlier run.
RESULT_FILES = ("report.json", "report.md")

#: The file that claims an output directory for one run. It is created
#: exclusively, so two runs cannot claim the same directory.
RESERVATION_FILE = ".run-reservation"


def default_output_dir_name() -> str:
    """The output directory of a run that did not name one.

    Every run gets a directory of its own: a run that reuses a directory
    replaces the archive, the reports and the database copies of the run
    before it, and those are the measurements the run exists to produce.
    """
    return "large-restore-" + time.strftime("%Y%m%d-%H%M%S")


def output_dir_holds_results(out_dir: Path) -> bool:
    """Whether the output directory already holds the results of an earlier run.

    A run overwrites its archive, its reports and its database copies, so a
    directory that already holds them is the only copy of another run's
    measurements. The run refuses that directory rather than replacing it.
    """
    if not out_dir.is_dir():
        return False
    if any((out_dir / name).exists() for name in RESULT_FILES):
        return True
    return any(out_dir.glob("*.zip")) or any(out_dir.glob("*.manifest.json"))


def reservation_is_stale(reservation: Path) -> bool:
    """Whether the reservation belongs to a run that is gone.

    A run that was killed leaves its reservation behind, and the directory
    would be unusable forever otherwise. The reservation names the process
    that made it, and a process that no longer exists holds nothing.
    """
    try:
        pid = int(reservation.read_text(encoding="utf-8").strip())
    except (OSError, ValueError):
        return True
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return True
    except PermissionError:
        return False
    return False


def reserve_output_dir(out_dir: Path) -> bool:
    """Claim the output directory for this run.

    The device lock keeps two runs off one device; this keeps two runs off
    one directory. Two runs against different devices would otherwise
    overwrite each other's archive and reports, and the measurements of a run
    are the point of the run.

    Returns False when another live run holds the directory.
    """
    reservation = out_dir / RESERVATION_FILE
    for attempt in range(2):
        try:
            handle = os.open(reservation, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o644)
        except FileExistsError:
            if attempt or not reservation_is_stale(reservation):
                return False
            try:
                reservation.unlink()
            except OSError:
                return False
            continue
        with os.fdopen(handle, "w", encoding="utf-8") as stream:
            stream.write(str(os.getpid()))
        return True
    return False


def release_output_dir(out_dir: Path) -> None:
    """Give the output directory back, so a later run can claim it."""
    try:
        (out_dir / RESERVATION_FILE).unlink(missing_ok=True)
    except OSError:
        pass


def over_budget_problems(outcome: dict, counts: dict[str, int]) -> list[str]:
    """What is wrong with the way the app refused an over-budget archive.

    Two things have to hold: the app has to refuse the archive *for its
    size* — a refusal for any other reason is a different failure wearing
    the same dialog — and the refusal has to leave the profile alone.

    The check that the refusal is about the size is the point of this
    function: without it any failure at all reads as a working budget.

    A table that could not be read is not an empty table: without it the
    check cannot say that the refused archive wrote nothing.
    """
    problems = []
    message = outcome.get("errorDialogText", "")
    if outcome.get("errorDialog"):
        if not mentions_budget(message):
            problems.append(
                "the archive was refused, but not for its size — the dialog said: "
                f"{message or '(nothing)'}"
            )
    elif outcome.get("reportedBySnackbar"):
        problems.append("the app reported the import as done instead of refusing the archive")
    else:
        problems.append("the import failed without saying so")
    unreadable = sorted(key for key, value in counts.items() if value < 0)
    if unreadable:
        problems.append(
            f"the tables {', '.join(unreadable)} could not be read, "
            "so what the refused archive wrote is unknown"
        )
        return problems
    left = {key: value for key, value in counts.items() if value > 0}
    if left:
        problems.append(f"the refused archive left rows behind: {left}")
    return problems


def interrupted_import_problems(
    stopped: bool, started: bool, counts: dict[str, int], expected_counts: dict[str, int]
) -> list[str]:
    """What is wrong with the profile after an import was cut short.

    The import has to leave the profile either whole or empty. A profile
    that holds part of the dataset is the case this check exists for, and
    "part" is not only about tasks: an import that wrote the projects and
    the tags but no task yet is part of the dataset too.

    Every table is looked at, and a table that could not be read is not an
    empty table: it is an unknown one. Every table is compared with the
    manifest — a table that holds all of its rows while another holds none
    is a half-restored dataset, and saying "the import had committed" about
    it would hide that.
    """
    if not stopped:
        return [
            "the app was not stopped, so the import was not cut short — the case is untested"
        ]
    if not started:
        return [
            "the restore never started, so there was nothing to cut short — the case is untested"
        ]
    unreadable = sorted(key for key, value in counts.items() if value < 0)
    if unreadable:
        return [
            f"the tables {', '.join(unreadable)} could not be read, "
            "so what the interrupted import left behind is unknown"
        ]
    partial = {}
    committed = []
    empty = []
    for key, actual in counts.items():
        want = expected_counts.get(key)
        if want is None:
            partial[key] = ("the manifest does not say", actual)
            continue
        if actual == 0:
            empty.append(key)
            continue
        if actual == want:
            committed.append(key)
        else:
            partial[key] = (want, actual)
    if partial:
        left = ", ".join(f"{key}: {got} of {want}" for key, (want, got) in partial.items())
        return [
            f"the interrupted import left {left} behind — the profile holds part of the dataset"
        ]
    if committed and empty:
        return [
            f"the interrupted import committed {', '.join(committed)} and wrote none of "
            f"{', '.join(empty)} — the profile holds part of the dataset"
        ]
    if committed:
        return [
            f"the import had already committed when the app was stopped "
            f"({', '.join(f'{key}: {counts[key]}' for key in committed)})"
        ]
    return []


def archive_payload(archive: Path) -> dict:
    """The dataset the archive carries."""
    with zipfile.ZipFile(archive) as zf:
        names = zf.namelist()
        entry = "payload.json" if "payload.json" in names else "backup.json"
        return json.loads(zf.read(entry))


def archive_task_ids(archive: Path) -> list[str]:
    """The ids of the dataset's tasks.

    The archive names the id of every row it carries, so the ids are what
    says which rows of the restored database are the dataset's rows.
    """
    payload = archive_payload(archive)
    return [str(task["id"]) for task in payload.get("tasks", []) if task.get("id")]


def payload_links(archive: Path) -> dict[str, set[tuple[str, str]]]:
    """The links the archive says the dataset has.

    The manifest says how many rows there are; the archive says which rows
    point at which. Two datasets with the same counts can have different
    links — a restore that shuffles them keeps every count and loses every
    relation, so the links are read from the dataset itself.

    Every relation the dataset carries is read: the task's project, its
    parent, its tags and its dependencies, the checklist items that belong
    to a task, the notes that hang on a task or a project or on another
    note, and the projects that sit inside another project.
    """
    payload = archive_payload(archive)
    tasks = payload.get("tasks", [])
    notes = payload.get("notes", [])
    projects = payload.get("projects", [])
    checklist = payload.get("checklistItems", [])
    return {
        "parent": {(t["id"], t["parentTaskId"]) for t in tasks if t.get("parentTaskId")},
        "project": {(t["id"], t["projectId"]) for t in tasks if t.get("projectId")},
        "tags": {(r["taskId"], r["tagId"]) for r in payload.get("taskTags", [])},
        "dependencies": {
            (r["taskId"], r["dependsOnTaskId"]) for r in payload.get("taskDependencies", [])
        },
        "checklist": {(c["id"], c["taskId"]) for c in checklist if c.get("taskId")},
        "noteTask": {(n["id"], n["taskId"]) for n in notes if n.get("taskId")},
        "noteProject": {(n["id"], n["projectId"]) for n in notes if n.get("projectId")},
        "noteParent": {(n["id"], n["parentNoteId"]) for n in notes if n.get("parentNoteId")},
        "projectParent": {(p["id"], p["parentId"]) for p in projects if p.get("parentId")},
    }


def restored_links(db_path: Path, scope: str | None = None) -> dict[str, set[tuple[str, str]]]:
    """The links the restored database holds.

    The links are the profile's links: the tables that carry a scope are
    filtered by it, and the tables that hang off a task are read through the
    task they belong to. The links of another profile are not the restore's
    links, and a link that points from one profile's row into another
    profile's row is not a link the dataset has — so it is left out here and
    shows up as a link the archive does not have.
    """
    connection = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    try:
        return {
            "parent": set(
                connection.execute(
                    "SELECT id, parent_task_id FROM tasks "
                    "WHERE parent_task_id IS NOT NULL" + scope_filter(scope, "user_id = ?"),
                    scope_params(scope),
                )
            ),
            "project": set(
                connection.execute(
                    "SELECT id, project_id FROM tasks "
                    "WHERE project_id IS NOT NULL" + scope_filter(scope, "user_id = ?"),
                    scope_params(scope),
                )
            ),
            "tags": set(
                connection.execute(
                    "SELECT tt.task_id, tt.tag_id FROM task_tags tt "
                    "JOIN tasks t ON tt.task_id = t.id" + scope_filter(scope, "t.user_id = ?"),
                    scope_params(scope),
                )
            ),
            "dependencies": set(
                connection.execute(
                    "SELECT d.task_id, d.depends_on_task_id FROM task_dependencies d "
                    "JOIN tasks t ON d.task_id = t.id" + scope_filter(scope, "t.user_id = ?"),
                    scope_params(scope),
                )
            ),
            "checklist": set(
                connection.execute(
                    "SELECT c.id, c.task_id FROM checklist_items c "
                    "JOIN tasks t ON c.task_id = t.id" + scope_filter(scope, "t.user_id = ?"),
                    scope_params(scope),
                )
            ),
            "noteTask": set(
                connection.execute(
                    "SELECT id, task_id FROM notes "
                    "WHERE task_id IS NOT NULL" + scope_filter(scope, "user_id = ?"),
                    scope_params(scope),
                )
            ),
            "noteProject": set(
                connection.execute(
                    "SELECT id, project_id FROM notes "
                    "WHERE project_id IS NOT NULL" + scope_filter(scope, "user_id = ?"),
                    scope_params(scope),
                )
            ),
            "noteParent": set(
                connection.execute(
                    "SELECT id, parent_note_id FROM notes "
                    "WHERE parent_note_id IS NOT NULL" + scope_filter(scope, "user_id = ?"),
                    scope_params(scope),
                )
            ),
            "projectParent": set(
                connection.execute(
                    "SELECT id, parent_id FROM projects "
                    "WHERE parent_id IS NOT NULL" + scope_filter(scope, "user_id = ?"),
                    scope_params(scope),
                )
            ),
        }
    except sqlite3.OperationalError as exc:
        raise RunError(
            f"the restored database does not have the tables the links are read "
            f"from: {exc}"
        ) from exc
    finally:
        connection.close()


def scope_filter(scope: str | None, condition: str, prefix: str = " AND ") -> str:
    """The clause that limits a query to one scope, or nothing at all.

    `prefix` is what the clause is joined with: " AND " for a query that
    already has a WHERE, " WHERE " for one that has none. A query that has
    neither is a syntax error, and SQLite answers one with an error the
    caller may read as "there is nothing there" — so the prefix has to
    match the query.
    """
    return f"{prefix}{condition}" if scope is not None else ""


def scope_params(scope: str | None) -> list:
    """The parameter a scope filter binds."""
    return [scope] if scope is not None else []


def link_identity_problems(
    expected: dict[str, set[tuple[str, str]]], actual: dict[str, set[tuple[str, str]]]
) -> list[str]:
    """The links the restore got wrong.

    A link that is missing is a relation the dataset lost; a link that is not
    in the dataset is one the restore invented. Counts cannot see either: a
    dataset whose links were shuffled has exactly the same counts. A subtask
    whose parent was dropped is a missing `parent` link, and it is counted
    here as what it is — a lost relation, not a missing row.
    """
    problems = []
    for kind in sorted(expected):
        want = expected[kind]
        got = actual.get(kind, set())
        missing = want - got
        extra = got - want
        if missing:
            problems.append(
                f"{len(missing)} {kind} link(s) of the dataset are missing after the "
                f"restore (e.g. {sorted(missing)[:2]})"
            )
        if extra:
            problems.append(
                f"{len(extra)} {kind} link(s) after the restore are not in the dataset "
                f"(e.g. {sorted(extra)[:2]})"
            )
    return problems


def apk_variant(built_here: bool, debuggable: bool | None = None) -> str:
    """The build variant of the APK the run measured.

    A build of this checkout is a debug one by construction. An APK handed in
    with `--apk` says nothing about its build type until the device says: the
    package manager's flags say whether the installed app is debuggable.
    """
    if debuggable is None:
        return "debug" if built_here else "unknown"
    return "debug" if debuggable else "release"


def installed_app_is_debuggable(serial: str) -> bool | None:
    """Whether the installed app is a debug build; None when it cannot be told.

    The package manager keeps the flags of every installed package, and a
    debug build is debuggable. `None` is "the device did not say", which is
    not the same as "no": the run refuses an app it cannot place.
    """
    code, out = adb("shell", "dumpsys", "package", APP_ID, serial=serial, timeout=120)
    if code != 0:
        return None
    for line in out.splitlines():
        if "flags=[" in line:
            return "DEBUGGABLE" in line
    return None


def verdict_problems(
    count_problems: list[str],
    link_problems: list[str],
    negative_problems: list[str],
    browse_ok: bool,
    reported_by_snackbar: bool,
) -> list[str]:
    """Everything that makes the run unsuccessful, in one list.

    The exit code of the run is this list, so it is written once, here, and
    not assembled in the middle of the run: a check whose problem is not
    added to this list does not fail the run.
    """
    problems = list(count_problems) + list(link_problems) + list(negative_problems)
    if not browse_ok:
        problems.append("the imported data did not open in the app")
    if not reported_by_snackbar:
        problems.append("the restore never reported itself")
    return problems


def pull_counts(
    serial: str, out_dir: Path, scope: str | None = None
) -> tuple[dict[str, int], str]:
    """The counts of the profile on the device, or a note that they are unknown.

    The copy can fail: the app may refuse to stop, and then the database
    cannot be read as one moment. That is not a reason to end the run — the
    check that asked for the counts has to report what it could not learn.
    Every table comes back as `-1`, which is what "could not be read" means
    everywhere else in this command.

    The counts are the profile's counts: without a scope they are the whole
    database's, which is the profile's counts only when the database holds
    nothing else.
    """
    try:
        db_path = pull_database(serial, out_dir)
    except RunError as exc:
        return {key: -1 for key in COUNT_TABLES}, str(exc)
    return count_rows(db_path, scope), ""


def check_over_budget(
    serial: str,
    out_dir: Path,
    archive: Path,
    apk: Path | None = None,
    scope: str | None = None,
) -> dict:
    """Restore an archive that is over the budget; the app has to refuse it.

    The refusal has to be a refusal with a reason — a dialog that names the
    archive's size as the reason — and the profile's data has to be
    untouched by it.

    The profile is expected to be empty (the caller wipes it): the check is
    "the refused archive wrote nothing", which is only a statement about the
    archive when there was nothing else there to stay. The counts are the
    profile's counts, so the rows of another profile are not mistaken for
    rows the refused archive wrote.
    """
    padded = padded_archive(archive, out_dir)
    name = push_archive(serial, padded)
    outcome = restore_and_watch(serial, name, out_dir / "over-budget-flow.log", apk=apk)
    counts, pull_problem = pull_counts(serial, out_dir / "over-budget-db", scope)
    problems = over_budget_problems(outcome, counts)
    if pull_problem:
        problems.append(pull_problem)
    if problems:
        verdict = "; ".join(problems)
    else:
        verdict = "the archive was refused for its size and nothing was written"
    return {"outcome": outcome, "counts": counts, "problems": problems, "verdict": verdict}


def check_interrupted_import(
    serial: str,
    out_dir: Path,
    archive: Path,
    apk: Path | None = None,
    expected_counts: dict[str, int] | None = None,
    scope: str | None = None,
) -> dict:
    """Cut an import short and check that the profile is not left half-written.

    The app is stopped while the import is running. What must not happen is a
    profile that holds part of the dataset: either the whole dataset or
    nothing.

    The import is only cut short when the app says that it is working: the
    tap on the confirmation is not proof that the restore began, and an
    import that never began would leave the profile empty and the check
    would pass without testing anything.

    The profile is expected to be empty (the caller wipes it): the check is
    "the interrupted import wrote nothing", which a non-empty profile could
    not answer.
    """
    name = push_archive(serial, archive)
    command = flow_command(serial, "restore-archive.yaml", {"ARCHIVE_NAME": name})
    log = out_dir / "interrupted-flow.log"
    print("flow: restore-archive.yaml (interrupted)")
    wake_unlock(serial)
    prewarm_app(serial)
    if apk is not None:
        verify_apk_unchanged(serial, apk)
    tapped = False
    with log.open("w", encoding="utf-8") as handle:
        process = subprocess.Popen(command, cwd=ROOT, stdout=handle, stderr=subprocess.STDOUT)
        flow_started = time.monotonic()
        try:
            while time.monotonic() - flow_started < 900:
                text = log.read_text(encoding="utf-8", errors="replace")
                if CONFIRM_TAP_DONE in text:
                    # The tap was dispatched: the import is under way.
                    tapped = True
                    break
                if process.poll() is not None:
                    # The flow is over. The confirmation was dispatched if the
                    # step's line is there, even when the step never completed;
                    # if it is not there, the flow never got to the
                    # confirmation, and there is no import to interrupt.
                    tapped = CONFIRM_TAP in text
                    break
                time.sleep(0.2)
            if tapped:
                # The confirmation is the flow's last step, and Maestro writes
                # the line about it before it does the tap: the flow has to be
                # given the chance to finish it. Killing the flow on the line
                # alone would kill it before the tap, and there would be no
                # import to interrupt at all.
                try:
                    process.wait(timeout=120)
                except subprocess.TimeoutExpired:
                    pass
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=60)
                except subprocess.TimeoutExpired:
                    process.kill()
    # The flow has let go of the device, so the screen can be read now: the
    # app's own indicator is what says that the restore is under way.
    if tapped:
        importing, evidence = restore_started(serial)
    else:
        importing, evidence = False, "the confirmation was never tapped"
    stopped = stop_app(serial)
    counts, pull_problem = pull_counts(serial, out_dir / "interrupted-db", scope)
    problems = interrupted_import_problems(
        stopped, importing, counts, dict(expected_counts or {})
    )
    if pull_problem:
        problems.append(pull_problem)
    if problems:
        verdict = "; ".join(problems)
    else:
        verdict = "the interrupted import left the profile empty"
    return {
        "interrupted": stopped,
        "importStarted": importing,
        "evidence": evidence,
        "counts": counts,
        "problems": problems,
        "verdict": verdict,
    }


def main(argv: list[str] | None = None) -> int:
    """Restore, check, and report. Returns the exit code."""
    try:
        sys.stdout.reconfigure(line_buffering=True)
    except (AttributeError, ValueError):  # pragma: no cover — a closed stream
        pass
    args = parse_args(argv)

    out_dir = Path(args.out) if args.out else ROOT / "build" / default_output_dir_name()
    out_dir = out_dir.resolve()
    if output_dir_holds_results(out_dir):
        print(
            f"error: the output directory {out_dir} already holds the results of an "
            "earlier run: its archive, its reports and its database copies would be "
            "replaced. Give this run a directory of its own (--out).",
            file=sys.stderr,
        )
        return EXIT_SETUP
    out_dir.mkdir(parents=True, exist_ok=True)
    if not reserve_output_dir(out_dir):
        print(
            f"error: another run holds the output directory {out_dir}; its archive, "
            "its reports and its database copies would be replaced. Give this run a "
            "directory of its own (--out).",
            file=sys.stderr,
        )
        return EXIT_SETUP

    # The report is written whatever happens to the run. A run that dies half
    # way has measured things worth keeping — the archive, the import time,
    # the counts — and a report that exists only for a run that got all the
    # way through throws those measurements away exactly when they matter
    # most: the 50k profile restores, and then the app crashes on the task
    # list. The phases say how far the run got.
    report: dict = {"status": "incomplete", "phases": []}
    exit_code = EXIT_SETUP
    lock: DeviceLock | None = None
    serial = ""
    try:
        try:
            serial = capture.resolve_device(args.serial, capture.attached_devices())
        except capture.CaptureError as exc:
            raise RunError(str(exc)) from exc
        print(f"device: {serial}")
        report["device"] = {"serial": serial, **device_facts(serial)}

        # One run per device. The lock is the repository's own, and the
        # certified suite takes the same file: two runs on one device kill
        # each other's device server, so the second one is refused rather than
        # measured against a broken one.
        lock = DeviceLock(serial)
        try:
            lock.acquire()
        except DeviceLocked as exc:
            raise RunError(str(exc)) from exc
        report["phases"].append("device claimed")

        # The APK: built here unless one is named, installed, and checked
        # against the build on the device.
        if args.apk:
            apk = Path(args.apk).expanduser().resolve()
            if not apk.is_file():
                raise RunError(f"the APK {apk} does not exist")
            built_here = False
        else:
            apk = capture.build_capture_apk()
            built_here = True
        capture.install_apk(serial, apk)
        package, version = capture.verify_identity(serial, apk, built_here)
        digest = verify_installed_apk(serial, apk)
        # The procedure restores into the debug build: the wipe it needs is a
        # debug-only path, and the issue asks about the debug APK. An app that
        # is not debuggable — or a device that will not say — is refused
        # rather than measured and reported as the debug build.
        debuggable = installed_app_is_debuggable(serial)
        if debuggable is not True:
            raise RunError(
                f"the app installed on {serial} is not a debug build"
                + ("" if debuggable is None else " (it is a release build)")
                + ": this procedure restores into the debug APK, whose debug-only "
                "seed link it uses to wipe the profile. The run is refused rather "
                "than measured against another build."
            )
        print(f"build: {package} {version}")
        report["apk"] = {
            "path": str(apk),
            "package": package,
            "version": version,
            "variant": apk_variant(built_here, debuggable),
            "digest": digest,
        }
        report["phases"].append("apk installed")

        # A database left behind by a newer build cannot be opened by this
        # one, and the app then shows a migration error instead of its own
        # screens — a failure that has nothing to do with the restore.
        on_device = database_version(serial)
        expected = app_schema_version()
        if on_device is not None and on_device > expected:
            raise RunError(
                f"the database on the device is at schema {on_device}, and this build "
                f"speaks {expected}: the app cannot open it. Clear the app's data on "
                "the device and run the command again."
            )

        archive, sidecar, manifest = generate_archive(args.profile, args.seed, out_dir)
        decompressed = read_decompressed_size(archive)
        counts = manifest.get("actualCounts", {})
        report["dataset"] = {
            "profile": args.profile,
            "seed": args.seed,
            "archive": str(archive),
            "sidecar": str(sidecar),
            "archiveBytes": archive.stat().st_size,
            "decompressedBytes": decompressed,
            "counts": counts,
        }
        report["tools"] = tool_versions(serial)
        report["phases"].append("archive generated")

        # One wipe before the negative phases, and both of them share it: a
        # refused archive writes nothing, so the profile is still empty for
        # the interrupted import that follows.
        #
        # The database as the device holds it before the wipe is read first:
        # the rows the reset removes are the active profile's rows, and the
        # scope they carry is the scope every count of this run is about.
        before_db = pull_database(serial, out_dir / "before-wipe-db")
        wipe_counts, wipe_seconds, scope = reset_content(
            serial, out_dir, before_db=before_db
        )
        print(f"wipe: {wipe_seconds}s ({wipe_counts})")
        report["wipe"] = {"seconds": round(wipe_seconds, 1), "counts": wipe_counts}
        report["profile"] = {
            "scope": scope,
            "source": (
                "the rows the profile reset removed"
                if scope is not None
                else "unknown — the profile held no rows to begin with"
            ),
        }
        report["phases"].append("profile wiped")

        negative = {
            "over-budget": check_over_budget(serial, out_dir, archive, apk, scope),
            "interrupted": check_interrupted_import(
                serial,
                out_dir,
                archive,
                apk,
                expected_counts=dict(counts),
                scope=scope,
            ),
        }
        report["negative"] = negative
        report["phases"].append("the two ways it can go wrong checked")

        # The import that has to work.
        file_name = push_archive(serial, archive)
        # The interrupted import may have left rows behind; the import below
        # has to start from a clean profile, or the counts afterwards are the
        # counts of the interrupted import plus this one. Counts that could
        # not be read count as rows behind too: the profile is then in an
        # unknown state, and it has to be wiped before it is measured.
        interrupted_counts = negative["interrupted"]["counts"]
        if any(value != 0 for value in interrupted_counts.values()):
            _, second_wipe, second_scope = reset_content(
                serial, out_dir, before_db=pulled_db_path(out_dir / "interrupted-db")
            )
            wipe_seconds += second_wipe
            if scope is None:
                scope = second_scope
            report["wipe"] = {"seconds": round(wipe_seconds, 1), "counts": wipe_counts}
        outcome = restore_and_watch(serial, file_name, out_dir / "restore-flow.log", apk=apk)
        print(f"import: {outcome['durationSeconds']}s ({outcome['measured']})")

        # What the app is holding in memory, read while it is still running.
        memory = app_memory(serial)
        report["import"] = {**outcome, "memory": memory}
        report["phases"].append("dataset restored")

        # The counts, and the links between the rows.
        db_dir = pull_database(serial, out_dir / "device-db")
        # The scope the restore wrote into: the scope the dataset's own rows
        # carry. The rows of another profile are not the restore's rows, and
        # counting them would report a difference between the manifest and a
        # restore that was correct.
        restored_scope = dataset_scope(db_dir, archive_task_ids(archive))
        scope_problems = []
        if restored_scope is not None and scope is not None and restored_scope != scope:
            scope_problems.append(
                f"the restore wrote the dataset into the scope {restored_scope}, but the "
                f"profile that was wiped is the scope {scope}"
            )
        if scope is None:
            scope = restored_scope
        if scope is None:
            scopes = sorted(content_scopes(db_dir))
            raise RunError(
                "the scope of the profile under test could not be determined, so the "
                "counts cannot be attributed to a profile: the rows the profile reset "
                "removed did not name one, and the restored rows carry the scopes "
                f"{scopes or 'none'}. The run is refused rather than measured against "
                "a database that holds rows of several profiles."
            )
        report["profile"] = {
            "scope": scope,
            "source": (
                "the rows the profile reset removed"
                if restored_scope is None or restored_scope == scope
                else "the rows the restore wrote"
            ),
        }
        db_counts = count_rows(db_dir, scope)
        count_problems = compare_counts(counts, db_counts) + scope_problems
        link_problems = relationship_problems(db_dir, scope)
        # The links themselves, not only the rows they point at: the archive
        # says which task belongs to which project, carries which tag and
        # blocks which task, and the restored database has to say the same.
        expected_links = payload_links(archive)
        actual_links = restored_links(db_dir, scope)
        identity_problems = link_identity_problems(expected_links, actual_links)
        report["database"] = {
            "bytes": database_bytes(db_dir.parent),
            "scope": scope,
            "counts": db_counts,
            "countProblems": count_problems,
            "linkProblems": link_problems,
            "linkIdentityProblems": identity_problems,
            "subtasks": {
                "expected": len(expected_links["parent"]),
                "actual": len(actual_links["parent"]),
            },
        }
        report["phases"].append("counts compared")

        # The data has to survive a restart, and the paths have to open.
        adb("shell", "am", "force-stop", APP_ID, serial=serial)
        time.sleep(2)
        inbox_log = out_dir / "inbox-flow.log"
        if not run_flow(serial, "open-inbox.yaml", {}, inbox_log, apk=apk):
            raise VerificationError(f"the Inbox did not open — see {inbox_log}")
        row = wait_for_task_row(serial)
        if row is None:
            raise VerificationError("the Inbox shows no task row to open")
        task_id = row
        task_title = title_for_tag(db_dir, task_id, scope)
        if task_title is None:
            raise VerificationError(f"no task in the database carries the tag {task_id}")
        print(f"task row: {task_id} ({task_title})")

        related = related_task(db_dir, scope)
        if related is None:
            raise VerificationError(
                "the imported dataset holds no task with a relation, so the "
                "related-tasks picker cannot be reached"
            )
        related_id, related_title = related
        print(f"related task: {related_title}")
        # The picker is a search over the restored dataset, so it is exercised
        # with a task the picker can offer — one that is not the task itself,
        # not one of its relations, and one whose addition cannot close a
        # cycle.
        candidate = picker_candidate(db_dir, related_id, scope)
        if candidate is None:
            raise VerificationError(
                "the imported dataset holds no task the related-tasks picker "
                "could offer, so the picker cannot be exercised"
            )
        candidate_id, candidate_title = candidate
        print(f"picker candidate: {candidate_title}")
        browse_log = out_dir / "browse-flow.log"
        browse_ok = run_flow(
            serial,
            "browse-imported-data.yaml",
            {
                "TASK_ID": task_id,
                "TASK_TITLE": task_title,
                "RELATED_TITLE": related_title,
                "RELATED_ID": task_item_id(related_title),
                "PICKER_CANDIDATE_TITLE": candidate_title,
                "PICKER_CANDIDATE_SLUG": slug(candidate_id),
            },
            browse_log,
            apk=apk,
        )
        report["uiPaths"] = {
            "browse": "passed" if browse_ok else "failed",
            "openedTask": task_title,
            "searchedTask": related_title,
            # The picker added this relation to the profile — the only write
            # the run makes, and it happens after the counts were compared.
            "pickerAddedRelation": candidate_title,
            "log": str(browse_log),
        }
        report["phases"].append("ui paths opened")

        negative_problems = [
            f"{name}: {problem}"
            for name, phase in negative.items()
            for problem in phase.get("problems", [])
        ]

        report["verdict"] = {
            "problems": verdict_problems(
                count_problems,
                link_problems + identity_problems,
                negative_problems,
                browse_ok,
                bool(outcome["reportedBySnackbar"]),
            )
        }
        report["status"] = "complete"
        exit_code = EXIT_OK if not report["verdict"]["problems"] else EXIT_FAILED
        print("\n=== verdict ===")
        for problem in report["verdict"]["problems"]:
            print(f"  {problem}")
        if not report["verdict"]["problems"]:
            print("  the dataset was restored whole")
        for name, phase in negative.items():
            print(f"  {name}: {phase['verdict']}")
        print(f"report: {out_dir / 'report.md'}")
    except VerificationError as exc:
        # The dataset was restored and the app did not hold up: the run has
        # an answer, and the answer is "the restore did not work", not "the
        # command could not start". A caller that cannot tell the two apart
        # reads a failed restore as a broken measurement.
        report["status"] = "failed"
        report["failure"] = "verification"
        report["error"] = str(exc)
        print(f"verification failed: {exc}", file=sys.stderr)
        exit_code = EXIT_FAILED
    except RunError as exc:
        report["status"] = "failed"
        report["failure"] = "setup"
        report["error"] = str(exc)
        print(f"error: {exc}", file=sys.stderr)
        exit_code = EXIT_SETUP
    except capture.CaptureError as exc:
        report["status"] = "failed"
        report["failure"] = "setup"
        report["error"] = str(exc)
        print(f"error: {exc}", file=sys.stderr)
        exit_code = EXIT_SETUP
    finally:
        if lock is not None:
            lock.release()
        if serial:
            # The archives the run pushed into the device's Downloads are
            # the device's storage, not the run's: they go when the run is
            # done with them, whatever the run's outcome.
            remove_pushed_archives(serial)
        # The report is written while the directory is still reserved: a run
        # that released the reservation first would let another run claim the
        # directory and start writing its own archive into it.
        try:
            write_report(out_dir, report)
        finally:
            release_output_dir(out_dir)
    return exit_code


def read_decompressed_size(archive: Path) -> int:
    """How many bytes the archive holds once it is unpacked.

    The budget the app enforces is a budget on the unpacked bytes, so the
    number the report carries is measured from the archive itself.
    """
    with zipfile.ZipFile(archive) as zf:
        return sum(info.file_size for info in zf.infolist())


if __name__ == "__main__":
    raise SystemExit(main())
