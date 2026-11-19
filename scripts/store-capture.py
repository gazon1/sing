#!/usr/bin/env python3
"""Capture the store screenshots locally, and check what came out.

One command: take (or build) the capture APK, install it on a pinned device,
run the store-capture Maestro flows in the order the manifest gives, write the
raw PNGs into a fresh directory and verify that every expected image is
really there.

Usage:
    python3 scripts/store-capture.py                     # the whole set
    python3 scripts/store-capture.py --flow 01-today     # one capture
    python3 scripts/store-capture.py --serial emulator-5554
    python3 scripts/store-capture.py --apk path/to/app-debug.apk

SERIAL is read from the environment too, the way the other device commands in
this repository read it.

WHY A SCRIPT AND NOT A COMMAND IN THE `emulator` PACKAGE
--------------------------------------------------------
The package is installed as an editable install, and the install belongs to
whichever checkout ran `pip install -e scripts/src/` last. A capture taken
against another checkout's flows is a capture of something else, and the
runner it drives resolves its root from the *installed* package's file. This
script lives in the tree it captures, and it drives this checkout's runner
through `./rm`, which refuses to run against a foreign tree.

WHAT IT DELIBERATELY DOES NOT DO
--------------------------------
It does not boot an emulator, and it does not pick a device for you: the
capture device is named, and a missing or ambiguous device is an error rather
than a guess. It does not uninstall the app — a signature clash is reported,
not resolved by throwing away the data on somebody's phone.

The manifest is the single source of truth for what a capture is: the file
names, the order and the profile the images are measured against live in
`Maestro/capture/manifest.yaml`.
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import struct
import subprocess
import sys
import zlib
from dataclasses import dataclass
from pathlib import Path

import yaml

#: The repository root. The script resolves it from its own file, so it works
#: from any working directory.
ROOT = Path(__file__).resolve().parent.parent

#: The manifest: the file names, the order, and the capture profile.
MANIFEST_PATH = ROOT / "Maestro" / "capture" / "manifest.yaml"

#: Where the raw captures go. A dedicated generated directory, wiped at the
#: start of every run: a stale PNG from an earlier run must never be able to
#: stand in for a capture that did not happen.
OUTPUT_DIR = ROOT / "build" / "store-capture"

#: The package the capture flows drive.
APP_ID = "com.singularity.todo"

#: Where `assembleDebug` writes its APK.
DEBUG_APK_DIR = ROOT / "androidApp" / "build" / "outputs" / "apk" / "debug"

#: The PNG magic number.
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"

#: Exit codes.
EXIT_OK = 0
EXIT_FAILED = 1  # a capture or a validation failed
EXIT_SETUP = 2  # the run could not start: no device, no APK, no manifest


class CaptureError(RuntimeError):
    """The run could not start, or could not be trusted.

    A different failure from "the capture failed": this one says the command
    is not measuring the thing it claims to measure — no device, a stale
    install, an unreadable APK. Those are errors about the setup, and they
    are reported before a single flow runs.
    """


# ── the manifest ─────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class Capture:
    """One store screenshot: what it is called, and which flow produces it."""

    order: int
    file: str
    screen: str
    flow: str

    @property
    def stem(self) -> str:
        """The capture's name without the extension: `01-today`."""
        return Path(self.file).stem


@dataclass(frozen=True)
class Profile:
    """What every capture is measured against.

    The numbers live in the manifest next to the captures they constrain, so
    the storyboard, the manifest and the checker cannot drift apart.
    """

    width: int | None = None
    height: int | None = None
    aspect: tuple[int, int] | None = None
    format: str = "png"


@dataclass(frozen=True)
class Manifest:
    """The capture set: the captures in order, and the profile they share."""

    captures: tuple[Capture, ...]
    profile: Profile


def _profile(raw: dict) -> Profile:
    """Read the profile block of the manifest."""
    if not isinstance(raw, dict):
        raise CaptureError(f"the manifest's profile is not a mapping: {raw!r}")
    width = raw.get("width")
    height = raw.get("height")
    aspect = None
    if raw.get("aspect"):
        try:
            left, right = str(raw["aspect"]).split(":")
            aspect = (int(left), int(right))
        except ValueError as exc:
            raise CaptureError(
                f"the manifest's aspect is not a 'width:height' pair: {raw['aspect']!r}"
            ) from exc
    return Profile(
        width=int(width) if width else None,
        height=int(height) if height else None,
        aspect=aspect,
        format=str(raw.get("format", "png")).lower(),
    )


def load_manifest(path: Path = MANIFEST_PATH) -> Manifest:
    """Read the capture manifest: the captures, in order, and the profile."""
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise CaptureError(f"cannot read the manifest {path}: {exc}") from exc
    try:
        data = yaml.safe_load(text)
    except yaml.YAMLError as exc:
        raise CaptureError(f"the manifest {path} is not valid YAML: {exc}") from exc
    if not isinstance(data, dict):
        raise CaptureError(f"the manifest {path} does not hold a mapping")

    captures: list[Capture] = []
    for entry in data.get("captures") or []:
        try:
            captures.append(
                Capture(
                    order=int(entry["order"]),
                    file=str(entry["file"]),
                    screen=str(entry.get("screen", "")),
                    flow=str(entry["flow"]),
                )
            )
        except (KeyError, TypeError, ValueError) as exc:
            raise CaptureError(f"a capture in the manifest is malformed: {entry!r} ({exc})") from exc
    if not captures:
        raise CaptureError(f"the manifest {path} lists no captures")
    captures.sort(key=lambda capture: capture.order)
    return Manifest(tuple(captures), _profile(data.get("profile") or {}))


def select_captures(captures: tuple[Capture, ...], only: str) -> list[Capture]:
    """The captures a run is about: all of them, or the one that was named.

    A name is the capture's own — `01-today`, `01-today.png` or the flow's
    file name all reach the same capture.
    """
    if not only:
        return list(captures)
    wanted = only.strip().lower()
    if wanted.endswith(".png"):
        wanted = wanted[: -len(".png")]
    matched = [
        capture
        for capture in captures
        if wanted in (capture.stem.lower(), Path(capture.flow).stem.lower())
    ]
    if not matched:
        known = ", ".join(capture.stem for capture in captures)
        raise CaptureError(f"no capture is named '{only}'. Known captures: {known}")
    return matched


# ── the device ────────────────────────────────────────────────────────────────


def parse_devices(text: str) -> list[tuple[str, str]]:
    """The devices `adb devices` reports, as (serial, state) pairs.

    Only the lines that carry a serial and a state are read: the daemon
    chatter around them is not a device.
    """
    devices: list[tuple[str, str]] = []
    for line in text.splitlines():
        parts = line.split()
        if len(parts) == 2 and not parts[0].startswith("*"):
            devices.append((parts[0], parts[1]))
    return devices


def _describe(devices: list[tuple[str, str]]) -> str:
    """The attached devices, for an error message."""
    if not devices:
        return "nothing"
    return ", ".join(f"{serial} ({state})" for serial, state in devices)


def resolve_device(pinned: str, devices: list[tuple[str, str]]) -> str:
    """The serial the capture runs on.

    A pinned device has to be there: a capture that silently fell back to
    another device would be a capture of a screen nobody asked for. An
    unpinned run is only allowed when there is exactly one device to choose
    from — two attached devices make every choice a guess.
    """
    usable = [serial for serial, state in devices if state == "device"]
    if pinned:
        if pinned in usable:
            return pinned
        if any(serial == pinned for serial, _ in devices):
            raise CaptureError(
                f"device {pinned} is attached but not usable (adb reports it as "
                f"{dict(devices)[pinned]})"
            )
        raise CaptureError(
            f"device {pinned} is not attached. Attached: {_describe(devices)}. "
            "Boot or connect it, then run the command again."
        )
    if not usable:
        raise CaptureError(
            f"no device is attached (adb reports: {_describe(devices)}). "
            "Attach one, or boot the capture AVD, then run the command again."
        )
    if len(usable) > 1:
        raise CaptureError(
            f"{len(usable)} devices are attached ({', '.join(usable)}); the capture "
            "device has to be named. Pass --serial <serial> (or set SERIAL)."
        )
    return usable[0]


def adb(*args: str, serial: str = "", timeout: float = 60) -> tuple[int, str]:
    """Run an adb command; return its exit code and its output."""
    command = ["adb"]
    if serial:
        command += ["-s", serial]
    command += list(args)
    try:
        cp = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.SubprocessError) as exc:
        return 1, str(exc)
    return cp.returncode, f"{cp.stdout or ''}{cp.stderr or ''}"


def attached_devices() -> list[tuple[str, str]]:
    """The devices adb sees right now."""
    code, out = adb("devices")
    if code != 0:
        raise CaptureError(f"adb devices failed: {out.strip()}")
    return parse_devices(out)


def apply_profile(serial: str) -> list[str]:
    """Put the device into the capture profile the storyboard documents.

    The profile is part of the contract: the same six images have to come out
    of every capture machine. A step that does not take is a warning and not
    an error — the image check below is the hard gate, and a device that
    refuses a locale is still a device the capture can be taken on.
    """
    steps = (
        ("the app locale", ("shell", "cmd", "locale", "set-app-locales", APP_ID,
                            "--locales", "ru-RU")),
        ("the light theme", ("shell", "cmd", "uimode", "night", "no")),
        ("the system font scale", ("shell", "settings", "put", "system", "font_scale", "1.0")),
        ("the screen timeout", ("shell", "settings", "put", "system",
                                "screen_off_timeout", "1800000")),
    )
    warnings: list[str] = []
    for name, args in steps:
        code, out = adb(*args, serial=serial)
        if code != 0:
            warnings.append(f"{name}: {out.strip().splitlines()[-1] if out.strip() else 'failed'}")
    return warnings


# ── the APK ───────────────────────────────────────────────────────────────────


def find_aapt() -> str | None:
    """The `aapt` of the newest installed build-tools, or None."""
    root = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "")
    if not root.is_dir():
        return None
    candidates = sorted(root.glob("build-tools/*/aapt"), reverse=True)
    return str(candidates[0]) if candidates else None


def apk_identity(apk: Path) -> tuple[str, str] | None:
    """The package name and version name inside the APK, or None.

    Read from the file, not from the device: the question is what the APK
    about to be installed *is*, and the device cannot answer it before the
    install.
    """
    aapt = find_aapt()
    if aapt is None or not apk.is_file():
        return None
    try:
        cp = subprocess.run([aapt, "dump", "badging", str(apk)],
                            capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.SubprocessError):
        return None
    if cp.returncode != 0:
        return None
    for line in cp.stdout.splitlines():
        if not line.startswith("package:"):
            continue
        name = re.search(r"name='([^']+)'", line)
        version = re.search(r"versionName='([^']+)'", line)
        if name and version:
            return name.group(1), version.group(1)
    return None


def git_sha() -> str:
    """The short SHA of this checkout."""
    try:
        cp = subprocess.run(["git", "-C", str(ROOT), "rev-parse", "--short=12", "HEAD"],
                            capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.SubprocessError):
        return "unknown"
    return cp.stdout.strip() if cp.returncode == 0 else "unknown"


def build_capture_apk() -> Path:
    """Build the debug APK this checkout is the source of.

    The build carries `TEST_AUTH_BYPASS=true`, the same way the Maestro
    runner's own install does: without it the app opens on the "Connect your
    server" screen and every assertion about the agenda is made against a
    screen in front of the one it describes.
    """
    command = [str(ROOT / "gw"), ":androidApp:assembleDebug", "-PTEST_AUTH_BYPASS=true"]
    print(f"building the capture APK: {' '.join(command)}")
    try:
        cp = subprocess.run(command, cwd=ROOT)
    except (OSError, subprocess.SubprocessError) as exc:
        raise CaptureError(f"the build could not be started: {exc}") from exc
    if cp.returncode != 0:
        raise CaptureError("the debug build failed")
    apks = sorted(DEBUG_APK_DIR.glob("*.apk"))
    if not apks:
        raise CaptureError(f"the build produced no APK in {DEBUG_APK_DIR}")
    return apks[0]


def install_apk(serial: str, apk: Path) -> None:
    """Install the APK on the device."""
    code, out = adb("install", "-r", str(apk), serial=serial, timeout=300)
    if code != 0 or "Success" not in out:
        raise CaptureError(
            f"installing {apk.name} on {serial} failed:\n{out.strip()}\n"
            "A signature clash is not resolved here: the app is not uninstalled "
            "behind your back, because that takes the data on the device with it."
        )


def installed_version(serial: str) -> str | None:
    """The version name of the app installed on the device, or None."""
    code, out = adb("shell", "dumpsys", "package", APP_ID, serial=serial, timeout=30)
    if code != 0:
        return None
    for line in out.splitlines():
        if "versionName=" in line:
            return line.split("versionName=", 1)[1].split()[0].strip()
    return None


def verify_identity(serial: str, apk: Path, built_here: bool) -> tuple[str, str]:
    """Check that the device carries the APK that was just installed.

    Two questions, and both have to answer yes. The package has to be the
    app the capture flows drive, and the build on the device has to be the
    build in the APK — a capture taken from a stale install is a picture of
    an older app, and it looks exactly like a good capture.
    """
    identity = apk_identity(apk)
    if identity is None:
        raise CaptureError(
            f"the identity of {apk} cannot be read (aapt is missing, or the file "
            "is not an APK); the capture is refused rather than taken blind"
        )
    package, version = identity
    if package != APP_ID:
        raise CaptureError(f"{apk.name} holds {package}, not {APP_ID}")
    installed = installed_version(serial)
    if installed is None:
        raise CaptureError(f"{APP_ID} is not installed on {serial} after the install")
    if installed != version:
        raise CaptureError(
            f"the device carries {installed} but {apk.name} is {version} — the "
            "capture is refused rather than taken from a stale install"
        )
    if built_here:
        expected = git_sha()
        # The build puts `+g<sha>` behind the version name, and the `g` is
        # read away the way the runner reads it away — the two have to be
        # looking at the same string.
        suffix = version.split("+", 1)[1].lstrip("g") if "+" in version else ""
        if suffix != expected:
            raise CaptureError(
                f"{apk.name} is not this checkout's build (it carries g{suffix}, "
                f"the checkout is g{expected})"
            )
    return package, version


# ── the images ────────────────────────────────────────────────────────────────


def validate_png(path: Path, profile: Profile) -> list[str]:
    """What is wrong with the image, if anything is.

    An empty list means the file is a PNG of the expected size and shape. The
    checks are the ones a store asks for: a readable file, a real PNG, image
    data that decompresses, and the documented dimensions and aspect.
    """
    try:
        data = path.read_bytes()
    except OSError as exc:
        return [f"cannot be read: {exc}"]
    if not data:
        return ["is empty"]
    if profile.format == "png" and not data.startswith(PNG_SIGNATURE):
        return ["is not a PNG (the file signature is missing)"]

    width: int | None = None
    height: int | None = None
    image_data = bytearray()
    complete = False
    position = len(PNG_SIGNATURE)
    while position + 8 <= len(data):
        length = int.from_bytes(data[position : position + 4], "big")
        kind = data[position + 4 : position + 8]
        end = position + 8 + length
        if end + 4 > len(data):
            return ["is truncated"]
        if kind == b"IHDR":
            if length < 8:
                return ["is not a PNG (its image header is malformed)"]
            width, height = struct.unpack(">II", data[position + 8 : position + 16])
        elif kind == b"IDAT":
            image_data += data[position + 8 : end]
        elif kind == b"IEND":
            complete = True
            break
        position = end + 4

    problems: list[str] = []
    if width is None or height is None:
        return ["is not a PNG (it has no image header)"]
    if not image_data:
        problems.append("carries no image data")
    if not complete:
        problems.append("is truncated (it has no end marker)")
    if image_data:
        try:
            zlib.decompress(bytes(image_data))
        except zlib.error as exc:
            problems.append(f"has corrupt image data ({exc})")
    if profile.width and profile.height and (width, height) != (profile.width, profile.height):
        problems.append(
            f"is {width}x{height}, expected {profile.width}x{profile.height}"
        )
    if profile.aspect:
        left, right = profile.aspect
        # width/height against left/right, as a cross product: the tolerance is
        # one percent of the product, so a rounding difference does not fail.
        if abs(width * right - height * left) > max(1, width * right // 100):
            problems.append(f"is {width}x{height}, which is not {left}:{right}")
    return problems


def capture_screenshot(serial: str, path: Path) -> None:
    """Take the screen of the device and write it as a PNG.

    `exec-out` and not `shell`: the shell mangles the bytes it pipes, and a
    PNG with a mangled byte is not a PNG.
    """
    with path.open("wb") as handle:
        try:
            cp = subprocess.run(
                ["adb", "-s", serial, "exec-out", "screencap", "-p"],
                stdout=handle,
                stderr=subprocess.PIPE,
                timeout=120,
            )
        except (OSError, subprocess.SubprocessError) as exc:
            raise CaptureError(f"the screenshot could not be taken: {exc}") from exc
    if cp.returncode != 0:
        raise CaptureError(
            "the screenshot could not be taken: "
            f"{cp.stderr.decode('utf-8', 'replace').strip() or 'screencap failed'}"
        )


# ── the run ───────────────────────────────────────────────────────────────────


def prepare_output_dir() -> Path:
    """A fresh output directory.

    Wiped at the start of every run: a PNG left by an earlier run must never
    be able to stand in for a capture that did not happen.
    """
    if OUTPUT_DIR.exists():
        shutil.rmtree(OUTPUT_DIR)
    (OUTPUT_DIR / "logs").mkdir(parents=True, exist_ok=True)
    (OUTPUT_DIR / "junit").mkdir(parents=True, exist_ok=True)
    return OUTPUT_DIR


def run_flow(flow: str, serial: str, junit_dir: Path, allow_stale: bool, log_path: Path) -> bool:
    """Run one capture flow through this checkout's Maestro runner.

    The runner is driven once per flow, and the screen is taken right after
    the flow — that is the only order in which the picture belongs to the
    flow that produced it. `--keep-screen-on` matters here: the runner puts
    the device to sleep at the end of a run, and a sleeping screen photographs
    as a black rectangle.
    """
    command = [
        str(ROOT / "rm"),
        "--flow", flow,
        "--serial", serial,
        "--skip-install",
        "--keep-screen-on",
        "--junit-dir", str(junit_dir),
    ]
    if allow_stale:
        # The APK is not this checkout's build, so the runner's own provenance
        # check would refuse it. The check above is the stricter one: it
        # compares the build on the device with the APK that was installed.
        command.append("--allow-stale-apk")
    try:
        with log_path.open("w", encoding="utf-8") as log:
            cp = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
    except (OSError, subprocess.SubprocessError) as exc:
        raise CaptureError(f"the runner could not be started: {exc}") from exc
    return cp.returncode == 0


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    """The command line."""
    parser = argparse.ArgumentParser(
        prog="store-capture.py",
        description="Capture the store screenshots and check the raw images.",
    )
    parser.add_argument(
        "--flow",
        default=os.environ.get("FLOW", ""),
        help="capture one of them (e.g. 01-today); the whole set by default",
    )
    parser.add_argument(
        "--serial",
        default=os.environ.get("SERIAL", ""),
        help="the device to capture on (also read from SERIAL)",
    )
    parser.add_argument(
        "--apk",
        default="",
        help="an APK to install instead of building this checkout's debug build",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    """Capture the store screenshots. Returns the exit code."""
    # Line buffering: the run is often read from a log file, and a block-
    # buffered stdout puts the error messages (which go to stderr, and are not
    # buffered) before the lines that explain them.
    try:
        sys.stdout.reconfigure(line_buffering=True)
    except (AttributeError, ValueError):  # pragma: no cover — a closed stream
        pass
    args = parse_args(argv)

    try:
        manifest = load_manifest()
        captures = select_captures(manifest.captures, args.flow)
        serial = resolve_device(args.serial, attached_devices())
    except CaptureError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_SETUP

    output = prepare_output_dir()
    junit_dir = output / "junit"
    logs_dir = output / "logs"

    print(f"device: {serial}")
    print(f"output: {output.relative_to(ROOT)}")

    try:
        if args.apk:
            apk = Path(args.apk).expanduser().resolve()
            if not apk.is_file():
                raise CaptureError(f"the APK {apk} does not exist")
            built_here = False
        else:
            apk = build_capture_apk()
            built_here = True
        print(f"apk:    {apk}")

        install_apk(serial, apk)
        package, version = verify_identity(serial, apk, built_here)
        print(f"build:  {package} {version} — the identity on {serial} matches")

        for warning in apply_profile(serial):
            print(f"warning: the capture profile did not fully apply ({warning})")
    except CaptureError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_SETUP

    results: list[tuple[Capture, Path | None, list[str], str]] = []
    for capture in captures:
        print(f"\n=== {capture.order}. {capture.file} ({capture.screen}) ===")
        log_path = logs_dir / f"{capture.stem}.log"
        if not run_flow(capture.flow, serial, junit_dir, not built_here, log_path):
            results.append((capture, None, [f"the flow did not pass (see {log_path.relative_to(ROOT)})"], "failed"))
            print(f"FAILED: the flow did not pass — {log_path.relative_to(ROOT)}")
            continue
        image = output / capture.file
        try:
            capture_screenshot(serial, image)
        except CaptureError as exc:
            results.append((capture, None, [str(exc)], "failed"))
            print(f"FAILED: {exc}")
            continue
        problems = validate_png(image, manifest.profile)
        if problems:
            results.append((capture, image, problems, "failed"))
            print(f"FAILED: {capture.file} {'; '.join(problems)}")
            continue
        size = image.stat().st_size
        results.append((capture, image, [], "ok"))
        print(f"ok: {capture.file} ({size // 1024} KB)")

    print("\n=== store capture ===")
    for capture, image, problems, verdict in results:
        detail = "; ".join(problems) if problems else "ok"
        print(f"  {capture.file:<22} {detail}")
    failed = [result for result in results if result[3] != "ok"]
    captured = len(results) - len(failed)
    print(f"{captured} of {len(results)} captures are in {output.relative_to(ROOT)}")
    if failed:
        print(f"failed: {', '.join(result[0].file for result in failed)}")
        print(f"logs:   {logs_dir.relative_to(ROOT)}")
        return EXIT_FAILED
    return EXIT_OK


if __name__ == "__main__":
    raise SystemExit(main())
