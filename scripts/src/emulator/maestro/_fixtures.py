"""maestro._fixtures — per-run fixture setup and teardown."""
from __future__ import annotations

import time
from typing import TYPE_CHECKING

from emulator.maestro._types import APP_ID

if TYPE_CHECKING:  # pragma: no cover — import cycle guard, annotations only
    from emulator.maestro._types import DeviceDeps


def reset_session(serial: str, deps: "DeviceDeps") -> None:
    """Give the app a fresh process, and with it the session it starts with.

    A session reset is a different kind of fixture from the content wipe in
    :func:`cleanup_run_fixtures`, and it needs its own mechanism: the wipe
    removes the *rows* a flow seeded, while this restores the *session* the
    next flow runs under. The two cannot be folded into each other — a flow
    that ends signed out leaves no rows behind and still hands the next flow
    an app that is signed out.

    In the E2E build the session lives in the process. The auth repository is
    a stub that reports ``Session.SignedIn`` from the first frame and keeps
    the session in memory, so a fresh process is a fresh session: force-
    stopping the app and starting it again is the reset, and it is what stops
    a flow that ends signed out from deciding the session of the flow after
    it.

    The flows that need a *signed-out* app ask for one themselves, through
    the app's debug session link; this is the other half — the state every
    other flow may assume.
    """
    if not deps.device_alive(serial):
        return
    deps.adb_on(serial, "shell", "am", "force-stop", APP_ID)
    time.sleep(1)
    deps.adb_on(serial, "shell", "am", "start", "-n", f"{APP_ID}/.MainActivity")
    deps.wait_for_app_resumed(serial)


def cleanup_run_fixtures(
    serial: str, run_id: str, mode: str, deps: "DeviceDeps",
) -> None:
    """Remove what the previous flow left on the device. Best-effort.

    Two scopes, and the default is the one that makes the suite mean what
    it says:

    - ``all`` (default) — wipe every task, note and project in the active
      profile. This is the only scope that gives each flow **its own data**:
      the flows share one device and one database, so anything a flow leaves
      behind is still there for the next one, and a flow that asserts an
      empty list then fails on the *previous* flow's row.
    - ``off`` — send nothing.

    The narrow scope — a sweep of the rows whose title starts with the run id
    or with a fixed title — is gone, and it is not coming back in that shape.
    Its boundary was a *list of titles* that somebody had to keep up to date
    by hand, and the list was already behind the suite: a title it forgot
    survived the run, and the failure that caused was silent — a flow that
    asserts an empty list fails on the *previous* flow's data, and a flow
    that asserts its own task fails because another flow's task is in the way.
    The nightly run of 2026-10-20 failed 43 flows across all four shards in
    exactly that shape, while the same flows passed in the smoke suite. See
    the ADR ``2026-10-28-the-bridge-policy-is-a-file.md``.

    Why ``all`` is the default and the seed activity's own warning about it
    ("not concurrency-safe by construction") does not apply: the runner holds
    a per-device lock for the whole run, so there is no second suite on the
    device to wipe. See ``maestro._lock`` and the ADR
    ``2026-10-21-each-flow-gets-a-clean-device.md``.

    The wipe is scoped to the active profile — the profile, the session and
    the auth state are left alone, and a flow that switched profiles is
    cleaned while that profile is still active.

    Uses ``deps.adb_on`` so ``-s <serial>`` is present; see
    ``emulator/maestro/_artifacts.py`` for the failure this prevents.

    ``run_id`` is the id of the flow whose fixtures are being swept. It is part
    of the ``DeviceDeps`` contract and every fake in the tree passes it, but
    the surviving scopes do not name individual rows — the wipe knows every
    row, and ``off`` sends nothing — so it is not read.
    """
    if mode == "off":
        return
    if mode != "all":
        raise ValueError(f"unknown cleanup scope: {mode!r}")
    if not deps.device_alive(serial):
        return
    cmd = "am start -a android.intent.action.VIEW -d todo-debug://seed?reset=content"
    deps.adb_on(serial, "shell", cmd)
    time.sleep(1)