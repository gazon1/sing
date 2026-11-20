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

import json
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

#: A version number, as the engines print it.
_VERSION_NUMBER = r"\d+\.\d+(?:\.\d+)*"

#: A line that is a version number and nothing else: the bare version some
#: builds print as their whole output (`2.10.0`).
_BARE_VERSION = re.compile(rf"^\s*v?({_VERSION_NUMBER})\s*$")

#: The engine's own banner: the engine's name, and the version right after it.
#:
#: The version has to belong to the engine. A number that follows some other
#: word ("Maestro version unavailable (build 1.2)") is a number somebody else
#: printed, and reading it as the engine's version would name a build that
#: never ran. The engine's name is followed by a word boundary, so
#: "maestro-runner 1.2.3" is not the CLI's banner.
def _banner_pattern(engine: str | None) -> re.Pattern[str]:
    """The banner line of one engine, or of any engine that is known."""
    if engine is None:
        names = "|".join(re.escape(name) for name in ENGINES)
    else:
        names = re.escape(engine)
    return re.compile(
        rf"^\s*(?:{names})(?![-\w.])\s+(?:version\s+)?v?({_VERSION_NUMBER})"
        rf"(?:\s*\([^)]*\))?\s*$",
        re.IGNORECASE,
    )


def parse_engine_version(output: str, engine: str | None = None) -> str | None:
    """The version an engine's `--version` output names, or None.

    A version is read from the engine's own banner — the engine's name and
    the version after it — or from an output that is a version number and
    nothing else. Both are whole-line matches: a version that has to share
    its line with other words belongs to something else, and the engine's
    banner has to name the engine that was probed.

    Output that names no version names no version. The caller has to treat
    that as "undetected" rather than fall back to any number in the output:
    a version nobody established is not a version, and the cost of the
    stricter rule is a loud refusal instead of a wrong record.
    """
    lines = [line.strip() for line in (output or "").splitlines() if line.strip()]
    if not lines:
        return None
    banner = _banner_pattern(engine)
    for line in lines:
        match = banner.match(line)
        if match:
            return match.group(1)
    # A bare version is the whole output: a line that is a version and
    # nothing else. Mixed into other output it is a number somebody printed,
    # not the engine's version.
    if len(lines) == 1:
        match = _BARE_VERSION.match(lines[0])
        if match:
            return match.group(1)
    return None


def engine_version(engine: str, cwd: str | Path | None = None) -> str | None:
    """The version of the engine build on PATH, or None if it names none.

    The build is asked for its version and the answer has to be recognised
    as that engine's version. A build that fails (`--version` exits
    non-zero) has not named a version, so neither has this.
    """
    binary = ENGINE_MAESTRO if engine == ENGINE_MAESTRO else MAESTRO_RUNNER_BIN
    try:
        cp = subprocess.run(
            [binary, "--version"],
            cwd=cwd,
            capture_output=True,
            text=True,
            timeout=30,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    if cp.returncode != 0:
        return None
    return parse_engine_version((cp.stdout or "") + (cp.stderr or ""), engine)


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
    The build prints a banner (`maestro-runner 1.1.31`) and then a block of
    build details; only the banner names the version, so only the banner is
    read.
    """
    return engine_version(ENGINE_MAESTRO_RUNNER) or ""


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


# ── the wording of a failure ────────────────────────────────────────────────

# The CLI and maestro-runner describe the same failure in different words.
# The cause registry (`config/docs/maestro-debt.yaml`) is keyed on the CLI's
# words, so a runner report read as it stands says every cause is new: the
# debt did not change kind, only the sentence that describes it. The runner's
# wording is rewritten into the CLI's where the two mean the same thing, so
# that one registry describes both engines.
#
# The two say the same thing in these shapes:
#
#   runner                                        the CLI
#   --------------------------------------------  ----------------------------------
#   Element '#x' not visible within 8s            Assertion is false: id: x is visible
#   Element not visible: element '#x' not found   Assertion is false: id: x is visible
#   Element '#x' still visible after 5s           Assertion is false: id: x is not visible
#
# and in these two the runner names no element at all:
#
#   Element not found: handler_error: ...         Element not found: Id matching regex: x
#   Element not found after 20 scrolls            No visible element found: id: x
#
# For those two the element comes from the runner's own report tree, which
# records the selector of the command that failed. Where neither the message
# nor the tree names the element, the message is left as it is: a cause that
# does not say what it is about is a cause nobody can classify, and inventing
# one would be a lie.

#: The runner's message for a wait that ran out of time on an element.
_RUNNER_TIMED_OUT = re.compile(
    r"^Element '#(?P<value>[^']+)' not visible within \d+s \(cause: timed out\)$"
)

#: The runner's message for an assertion on an element that was not there.
_RUNNER_ELEMENT_NOT_FOUND = re.compile(
    r"^Element not visible: element '#(?P<value>[^']+)' not found "
    r"\(cause: element '#(?P=value)' not found\)$"
)

#: The runner's message for a wait for an element to go away that it did not.
_RUNNER_STILL_VISIBLE = re.compile(
    r"^Element '#(?P<value>[^']+)' still visible after \d+s \(cause: timed out\)$"
)

#: The runner's message for a command whose element it could not find at all.
_RUNNER_HANDLER_ERROR = re.compile(r"^Element not found: handler_error: ")

#: The runner's message for a scroll that made no progress.
_RUNNER_SCROLL_NO_PROGRESS = re.compile(
    r"^Element not found: scrolling down made no progress after \d+ scrolls "
    r"\(end of content\?\) \(cause: element not found\)$"
)

#: The runner's message for a scroll that ran out of scrolls.
_RUNNER_SCROLLED_OUT = re.compile(
    r"^Element not found after \d+ scrolls \(cause: element not found\)$"
)


def _cli_assertion(kind: str, value: str, visible: bool) -> str:
    """The CLI's wording for a failed visibility assertion."""
    subject = f"id: {value}" if kind == "id" else f'"{value}"'
    state = "is visible" if visible else "is not visible"
    return f"Assertion is false: {subject} {state}"


def _cli_element_not_found(kind: str, value: str) -> str:
    """The CLI's wording for a command whose element was not on the screen."""
    return f"Element not found: {kind.capitalize()} matching regex: {value}"


def _cli_no_visible_element(value: str) -> str:
    """The CLI's wording for a scroll that never found the element."""
    return f"No visible element found: id: {value}"


def _cli_wording(message: str, selector: tuple[str, str] | None) -> str | None:
    """The CLI's wording for the cause a runner message describes.

    `selector` is the (kind, value) of the element the failed command
    addressed — `None` when the runner's report tree does not say. The
    message alone is enough for the shapes that name the element; the
    selector is what the other two need.
    """
    text = message.strip()
    match = _RUNNER_TIMED_OUT.match(text)
    if match:
        return _cli_assertion("id", match.group("value"), visible=True)
    match = _RUNNER_ELEMENT_NOT_FOUND.match(text)
    if match:
        return _cli_assertion("id", match.group("value"), visible=True)
    match = _RUNNER_STILL_VISIBLE.match(text)
    if match:
        return _cli_assertion("id", match.group("value"), visible=False)
    if _RUNNER_HANDLER_ERROR.match(text):
        if selector is None:
            return None
        kind, value = selector
        return _cli_element_not_found(kind, value)
    if _RUNNER_SCROLL_NO_PROGRESS.match(text) or _RUNNER_SCROLLED_OUT.match(text):
        if selector is None or selector[0] != "id":
            return None
        return _cli_no_visible_element(selector[1])
    return None


#: The selector in a command's own line: `tapOn: id="x"`. The runner records
#: the selector of a command as its parameters when it has them, and only in
#: the command's own line when it does not.
_SELECTOR_IN_YAML = re.compile(r'\b(?P<kind>id|text)="(?P<value>[^"]+)"')


def _selector_from_yaml(yaml: str | None) -> tuple[str, str] | None:
    """The (kind, value) a command's own line asks for."""
    if not yaml:
        return None
    match = _SELECTOR_IN_YAML.search(yaml)
    if match is None:
        return None
    return match.group("kind"), match.group("value")


def _failed_selector(commands: list) -> tuple[str, str] | None:
    """The (kind, value) of the element the failed command addressed."""
    selector: tuple[str, str] | None = None
    for command in commands:
        if command.get("status") == "failed":
            params = command.get("params")
            found = params.get("selector") if isinstance(params, dict) else None
            if isinstance(found, dict) and found.get("type") and found.get("value"):
                selector = (str(found["type"]), str(found["value"]))
            else:
                selector = _selector_from_yaml(command.get("yaml")) or selector
        for key in ("subCommands", "commands"):
            nested = _failed_selector(command.get(key) or [])
            if nested is not None:
                selector = nested
    return selector


def _failing_selector(report_dir: Path | None, run_id: str) -> tuple[str, str] | None:
    """The element the failed command addressed, from the runner's report tree.

    maestro-runner writes one directory per attempt under the report
    directory it was given, each holding the flow's commands. The command
    that failed is the one that carries an ``error``, and its selector is
    what the flow asked for. The runner's own failure message does not name
    it — "handler_error: Element not found in any of 2 strategies" says that
    an element was missing and nothing about which one.

    Only the newest attempt is read: a report belongs to the attempt that
    wrote it, and an older attempt's tree would name the wrong element.

    The directory belongs to a single execution, so a tree that is not there
    is no tree at all: there is no other execution's tree to fall back on,
    and a message that stays without an element stays without one.
    """
    if report_dir is None or not report_dir.is_dir():
        return None
    attempts = sorted((p for p in report_dir.iterdir() if p.is_dir()), reverse=True)
    if not attempts:
        return None
    flows = attempts[0] / "flows"
    if not flows.is_dir():
        return None
    for flow_file in sorted(flows.glob("*.json")):
        try:
            data = json.loads(flow_file.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        selector = _failed_selector(data.get("commands") or [])
        if selector is None:
            continue
        kind, value = selector
        if run_id:
            value = value.replace("${RUN_ID}", run_id)
        if "${" in value:
            # A placeholder the run did not resolve: the CLI's wording would
            # name an element this report cannot name.
            return None
        return kind, value
    return None


def normalise_junit(
    junit_file: Path,
    flow_path: Path,
    report_dir: Path | None = None,
    run_id: str = "",
) -> None:
    """Give a maestro-runner report the shape the gates read.

    The CLI's ``testcase`` carries the flow's path in a ``file`` attribute;
    maestro-runner carries the same path in a ``property``. The gates, the
    traceability layer and the GitHub annotations all read the attribute, so a
    report written by the other engine would say "this run measured nothing
    in particular" — the flow would still be found by its file name, but the
    annotation would point at no file at all.

    The *wording* of a failure is normalised as well. The cause registry is
    keyed on the CLI's words, so a runner report that says "Element '#x' not
    visible within 8s" reads as a cause nobody has ever accepted — the debt
    did not change kind, only the sentence that describes it. The message is
    rewritten into the CLI's wording where the two mean the same thing; the
    element the runner's message does not name comes from its report tree,
    and a message that names no element and has no tree to say it is left
    exactly as it is.

    The rewrite is additive: an attribute is added and a message is rewritten,
    nothing is removed, and a report that does not need it is left exactly as
    it is.
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
    selector: tuple[str, str] | None = None
    selector_read = False
    for case in root.iter("testcase"):
        if not case.get("file"):
            flow_value = ""
            for prop in case.iter("property"):
                if prop.get("name") == "file":
                    flow_value = prop.get("value") or ""
                    break
            if not flow_value:
                flow_value = flow_path.as_posix()
            case.set("file", flow_value)
            changed = True
        failure = case.find("failure")
        if failure is None:
            continue
        original = failure.text or ""
        if not original.strip():
            continue
        canonical = _cli_wording(original, None)
        if canonical is None:
            # The runner's message names no element. Its report tree does.
            if not selector_read:
                selector = _failing_selector(report_dir, run_id)
                selector_read = True
            canonical = _cli_wording(original, selector)
        if canonical is not None and canonical != original:
            failure.text = canonical
            failure.set("message", canonical)
            changed = True
    if changed:
        tree.write(junit_file, encoding="unicode", xml_declaration=True)
