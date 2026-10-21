"""maestro._bridge — the only channel from a Maestro script to the host.

Why this exists
---------------
A Maestro flow can only say things to the app. It cannot reach the host: the
flows that need to touch the device's shell — set a system property, damage the
app's encrypted store — had no way to do it that worked.

What they had instead was three different guesses, and all three were wrong under
the Maestro this repository pins (2.10.0, see ``.maestro-version``):

* ``execSync(...)`` — a Node global. The engine is GraalJS, and it is not defined.
* ``java.lang.Runtime.getRuntime().exec(...)`` — Java host access. GraalJS runs
  with no host access, and ``java`` is not defined.
* ``env.PROP`` — the ``env:`` map. Maestro exposes each entry as a *bare global*
  (``PROP``), so ``env`` itself is undefined.

So the shell-out never ran, and the flows that depended on it failed for a reason
that had nothing to do with the behaviour they were asserting. Restoring them
means giving the script a channel that exists — and the only one Maestro offers
is HTTP.

The channel
-----------
The runner starts a small HTTP server on the loopback interface and passes its
address to every flow as ``E2E_BRIDGE_URL``. A script calls it:

    var res = http.get(E2E_BRIDGE_URL + "/shell?cmd=" + encodeURIComponent(cmd));
    var data = JSON.parse(res.body);

``http.get`` returns ``{ok, status, body, headers}`` and ``body`` is a *string*,
so the payload is parsed by the script.

The server is not a shell. It runs a device command only if the WHOLE command
matches one of the shapes the policy allows, and it always runs it through
``adb -s <serial> shell`` — the command never runs on the host. The server
binds to 127.0.0.1, on an ephemeral port, for the duration of one test run, and
it is stopped when the run ends.

What the policy is
------------------
The allowed shapes live in a file — ``config/maestro/bridge-policy.yaml`` —
not in a Python literal, because the set grows with the flows. A flow that
needs a new device command gets it by editing the policy, not by editing the
bridge.

The shapes are matched whole, never by prefix. A prefix check accepts
``run-as com.singularity.todo ; rm -rf /`` — the device shell would run the
second command as the *shell* user rather than the app's. The rule lives in
the code (``fullmatch``) and in
``docs/decisions/2026-10-28-the-bridge-policy-is-a-file.md``.

What it refuses
---------------
Anything that does not match a policy shape, and anything that is not a device
command at all. A rejected command is a 400 with a JSON body saying why; it is
never executed. A flow that needs the bridge and does not have it fails with a
message that says so, rather than passing on its next assertion.
"""
from __future__ import annotations

import json
import subprocess
import threading
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, HTTPServer
from typing import Callable
from urllib.parse import parse_qs, urlparse

from emulator.maestro._policy import Policy, default_policy
from emulator.maestro._types import APP_ID

#: The name the runner hands the flows. It is a global inside a script, not
#: ``env.E2E_BRIDGE_URL`` — Maestro exposes each ``env:`` entry as a bare global.
BRIDGE_ENV_NAME = "E2E_BRIDGE_URL"

#: How long a bridged command may run before the bridge gives up on it.
COMMAND_TIMEOUT_SEC = 30


@dataclass(frozen=True)
class CommandResult:
    """What one bridged command produced."""

    code: int
    stdout: str
    stderr: str


def _default_run_device_command(serial: str, command: str) -> CommandResult:
    """Run one device command through adb and capture all three outcomes.

    The command is passed as a *single* argument to ``adb shell`` so that the
    device's own shell parses the quoting — ``run-as … sh -c 'ls shared_prefs'``
    has to arrive on the device as three words with the quotes intact, and
    splitting it on the host would lose exactly that.
    """
    try:
        cp = subprocess.run(
            ["adb", "-s", serial, "shell", command],
            capture_output=True,
            text=True,
            timeout=COMMAND_TIMEOUT_SEC,
            check=False,
        )
    except subprocess.TimeoutExpired:
        return CommandResult(
            code=124,
            stdout="",
            stderr=f"the command did not finish within {COMMAND_TIMEOUT_SEC}s",
        )
    except OSError as exc:
        return CommandResult(code=127, stdout="", stderr=f"adb could not be run: {exc}")
    return CommandResult(code=cp.returncode, stdout=cp.stdout, stderr=cp.stderr)


def is_allowed(command: str, policy: Policy | None = None) -> bool:
    """Whether the bridge will run this device command.

    The command has to match one of the policy's shapes *whole*. A command
    that starts with an allowed shape and carries more arguments is a different
    command, and it is refused.
    """
    return (policy if policy is not None else default_policy()).allows(command)


class _BridgeHandler(BaseHTTPRequestHandler):
    """Serves one endpoint: run an allowed device command."""

    bridge: "Bridge"

    def do_GET(self) -> None:  # noqa: N802 — BaseHTTPRequestHandler's name
        parsed = urlparse(self.path)
        if parsed.path == "/health":
            self._respond(200, {"ok": True})
            return
        if parsed.path != "/shell":
            self._respond(404, {"error": f"unknown endpoint: {parsed.path}"})
            return

        params = parse_qs(parsed.query)
        commands = params.get("cmd") or []
        if len(commands) != 1:
            self._respond(400, {"error": "exactly one `cmd` parameter is required"})
            return

        command = commands[0]
        policy = self.bridge.policy
        if not policy.allows(command):
            self._respond(
                400,
                {
                    "error": (
                        "the bridge runs only the device commands its policy allows "
                        f"({policy.source}; shapes: {', '.join(policy.ids())}), and a "
                        "command has to match one of them whole; "
                        f"refused: {command!r}"
                    )
                },
            )
            return

        result = self.bridge.run_command(command)
        self._respond(
            200 if result.code == 0 else 500,
            {
                "stdout": result.stdout,
                "stderr": result.stderr,
                "code": result.code,
            },
        )

    def _respond(self, status: int, payload: dict) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt: str, *args) -> None:
        """Keep the runner's output clean — the bridge is not a service to watch."""


class Bridge:
    """A loopback HTTP server that runs allowed device commands for the flows.

    The bridge is started once per test run and stopped when it ends. It holds
    the serial it talks to, so a flow cannot address another device, and it only
    ever runs commands through ``adb -s <serial> shell``.
    """

    def __init__(
        self,
        serial: str,
        app_id: str = APP_ID,
        run_device_command: Callable[[str, str], CommandResult] | None = None,
        policy: Policy | None = None,
    ) -> None:
        self.serial = serial
        self.app_id = app_id
        self._run = run_device_command or _default_run_device_command
        self.policy = policy if policy is not None else default_policy()
        self._server: HTTPServer | None = None
        self._thread: threading.Thread | None = None

    @property
    def base_url(self) -> str:
        """The address the flows call. Empty until the bridge is started."""
        if self._server is None:
            return ""
        host, port = self._server.server_address[:2]
        return f"http://{host}:{port}"

    def start(self) -> str:
        """Start serving and return the base URL."""
        if self._server is not None:
            return self.base_url
        handler = type("_BoundBridgeHandler", (_BridgeHandler,), {"bridge": self})
        self._server = HTTPServer(("127.0.0.1", 0), handler)
        self._thread = threading.Thread(
            target=self._server.serve_forever,
            name="maestro-bridge",
            daemon=True,
        )
        self._thread.start()
        return self.base_url

    def stop(self) -> None:
        """Stop serving. Safe to call when the bridge was never started."""
        if self._server is not None:
            self._server.shutdown()
            self._server.server_close()
            self._server = None
        if self._thread is not None:
            self._thread.join(timeout=5)
            self._thread = None

    def run_command(self, command: str) -> CommandResult:
        """Run one device command. The caller has already checked the whitelist."""
        return self._run(self.serial, command)
