"""maestro._engine — the executors a Maestro flow can be run with.

Two engines read the same YAML:

* **maestro** — the Maestro CLI. The default, and the one the failure floor
  was calibrated with.
* **maestro-runner** — a single Go binary that reads the same flow files with
  no JVM. It exists because the CLI starts a JVM per flow (four to six
  seconds of warm-up, times one hundred and thirty flows) and drives the
  device over an HTTP layer that costs a round trip per element lookup.

The engines are not interchangeable in their output. The CLI writes the flow's
path into the ``file`` attribute of the JUnit ``testcase``; maestro-runner
writes it as a ``property`` instead, and the gates read the attribute. So a
maestro-runner report is normalised before the gates see it — the shape of the
report is the contract, not the shape one binary happens to write.

Only the *execution* is swapped. The runner around it — the fixture wipe, the
session reset, the retry, the device-loss recovery, the provenance check — is
the same code either way, because that is where the meaning of a run lives.
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

from emulator.maestro._bridge import BRIDGE_ENV_NAME

#: The Maestro CLI.
ENGINE_MAESTRO = "maestro"

#: maestro-runner, the Go binary.
ENGINE_MAESTRO_RUNNER = "maestro-runner"

#: The engines the runner knows.
ENGINES = (ENGINE_MAESTRO, ENGINE_MAESTRO_RUNNER)

#: The binary name of the Go runner, as it is on PATH.
MAESTRO_RUNNER_BIN = "maestro-runner"

#: Where maestro-runner looks for the driver binaries it installs on the device.
MAESTRO_RUNNER_HOME_ENV = "MAESTRO_RUNNER_HOME"

#: The version of maestro-runner this repository was measured against.
MAESTRO_RUNNER_VERSION = "1.1.31"

#: The runner reports one anonymous event per run unless this is set.
MAESTRO_RUNNER_NO_TELEMETRY_ENV = "MAESTRO_RUNNER_NO_TELEMETRY"

#: The runner checks for a newer release unless this is set.
MAESTRO_RUNNER_NO_UPDATE_CHECK_ENV = "MAESTRO_RUNNER_NO_UPDATE_CHECK"


def runner_home() -> Path | None:
    """The distribution root that holds maestro-runner's driver binaries.

    The binary alone is not enough: the driver that talks to the device (the
    on-device agent, or the Appium uiautomator2 server) ships as a set of APKs
    under ``drivers/android``, and the runner refuses to start without them.
    A release tarball unpacks to ``<root>/bin/maestro-runner`` and
    ``<root>/drivers``; a copy of the binary on its own — which is what a
    single-file install produces — is a runner that cannot drive anything.

    The root is found in this order: the explicit ``MAESTRO_RUNNER_HOME``,
    then the directory the binary sits in, then its parent (the ``bin/``
    layout of a release tarball).
    """
    explicit = os.environ.get(MAESTRO_RUNNER_HOME_ENV)
    if explicit:
        root = Path(explicit).expanduser()
        return root if (root / "drivers").is_dir() else None

    found = shutil.which(MAESTRO_RUNNER_BIN)
    if found is None:
        return None
    binary = Path(found).resolve()
    for candidate in (binary.parent, binary.parent.parent):
        if (candidate / "drivers").is_dir():
            return candidate
    return None


def engine_available(engine: str) -> bool:
    """Whether the engine can actually run a flow here."""
    if engine == ENGINE_MAESTRO:
        return shutil.which("maestro") is not None
    return shutil.which(MAESTRO_RUNNER_BIN) is not None and runner_home() is not None


def runner_version() -> str:
    """The version of the maestro-runner build on PATH, or an empty string.

    The version is read so a run can say which build produced it: a verdict
    that belongs to one build must not be silently attributed to another.
    """
    try:
        cp = subprocess.run(
            [MAESTRO_RUNNER_BIN, "--version"],
            capture_output=True,
            text=True,
            timeout=30,
        )
    except (OSError, subprocess.SubprocessError):
        return ""
    out = (cp.stdout or cp.stderr or "")
    # The build prints a banner (`maestro-runner 1.1.31`) and then a block of
    # build details; the version is the first number that looks like one.
    match = re.search(r"\d+\.\d+\.\d+", out)
    return match.group(0) if match else ""


def engine_env(engine: str) -> dict[str, str]:
    """The environment overrides an engine needs, on top of the caller's.

    For maestro-runner this is the distribution root (without it the runner
    exits before it drives anything) and the switches that keep the run
    private and reproducible: no telemetry event, no update check that would
    pull a different build into a run whose verdict is already recorded.
    """
    if engine != ENGINE_MAESTRO_RUNNER:
        return {}
    env = {
        MAESTRO_RUNNER_NO_TELEMETRY_ENV: "1",
        MAESTRO_RUNNER_NO_UPDATE_CHECK_ENV: "1",
        "DO_NOT_TRACK": "1",
    }
    home = runner_home()
    if home is not None:
        env[MAESTRO_RUNNER_HOME_ENV] = str(home)
    return env


def flow_command(
    engine: str,
    flow_path: Path,
    serial: str,
    junit_file: Path,
    run_id: str,
    bridge_url: str = "",
    report_dir: Path | None = None,
) -> list[str]:
    """Build the command that runs one flow with the given engine.

    The two commands are not translations of each other; they are the same
    intent expressed in two tools:

    * the CLI takes the device as ``--device`` and writes its report to
      ``--output``;
    * maestro-runner takes the same device as ``--device`` and, with
      ``--format junit``, writes the JUnit report to ``--output`` as well —
      the full report tree (JSON, HTML, Allure) goes to
      ``--test-output-dir`` instead.

    ``--env`` carries the same globals either way: ``RUN_ID`` for the flows
    that stamp their rows with it, and the bridge URL for the flows that talk
    to the host.
    """
    if engine == ENGINE_MAESTRO:
        cmd = [
            "maestro", "test", str(flow_path),
            "--device", serial,
            "--env", f"RUN_ID={run_id}",
            "--format", "JUNIT",
            "--output", str(junit_file),
        ]
    elif engine == ENGINE_MAESTRO_RUNNER:
        cmd = [
            MAESTRO_RUNNER_BIN, "test", str(flow_path),
            "--device", serial,
            "--env", f"RUN_ID={run_id}",
            "--format", "junit",
            "--output", str(junit_file),
        ]
        if report_dir is not None:
            cmd += ["--test-output-dir", str(report_dir)]
    else:
        raise ValueError(f"unknown engine: {engine!r}")

    if bridge_url:
        cmd += ["--env", f"{BRIDGE_ENV_NAME}={bridge_url}"]
    return cmd


def normalise_junit(junit_file: Path, flow_path: Path) -> None:
    """Give a maestro-runner report the shape the gates read.

    The CLI's ``testcase`` carries the flow's path in a ``file`` attribute;
    maestro-runner carries the same path in a ``property``. The gates, the
    traceability layer and the GitHub annotations all read the attribute, so a
    report written by the other engine would say "this run measured nothing
    in particular" — the flow would still be found by its file name, but the
    annotation would point at no file at all.

    The rewrite is additive: an attribute is added, nothing is removed, and a
    report that does not need it is left exactly as it is.
    """
    if not junit_file.is_file():
        return
    try:
        tree = ET.parse(junit_file)
    except ET.ParseError:
        # A report that is not XML is not this function's problem: the caller
        # reads the flow's outcome from the exit code, and the malformed file
        # is the evidence of that.
        return
    root = tree.getroot()
    changed = False
    for case in root.iter("testcase"):
        if case.get("file"):
            continue
        flow_value = ""
        for prop in case.iter("property"):
            if prop.get("name") == "file":
                flow_value = prop.get("value") or ""
                break
        if not flow_value:
            flow_value = flow_path.as_posix()
        case.set("file", flow_value)
        changed = True
    if changed:
        tree.write(junit_file, encoding="unicode", xml_declaration=True)
