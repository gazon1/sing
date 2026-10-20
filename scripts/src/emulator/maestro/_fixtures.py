"""maestro._fixtures — per-run fixture setup and teardown."""
from __future__ import annotations

import time
import urllib.parse
from typing import TYPE_CHECKING

from emulator.maestro._types import APP_ID

if TYPE_CHECKING:  # pragma: no cover — import cycle guard, annotations only
    from emulator.maestro._types import DeviceDeps


def reset_session(serial: str, deps: "DeviceDeps") -> None:
    """Give the app a fresh process, and with it the session it starts with.

    A session reset is a different kind of fixture from the row sweep in
    :func:`cleanup_run_fixtures`, and it needs its own mechanism: the sweep
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
    """Send cleanup deeplink to the app. Best-effort.

    Uses ``deps.adb_on`` so ``-s <serial>`` is present; see
    ``emulator/maestro/_artifacts.py`` for the failure this prevents.

    The list of prefixes is ``urllib.parse.quote``-encoded **whole**, with no
    shell quoting around it and with no pre-encoded characters inside it.

    Both of those used to be wrong, and the failure was silent. The value was
    built as ``f"'{run_id},Buy%20milk'"`` — shell-style quotes and a
    pre-encoded space — and then quoted again, so the app received the
    literal string ``'r4797858390,Buy%20milk'`` and split it into the
    prefixes ``"'r4797858390"`` and ``"Buy%20milk'"``. The app matches titles
    by *prefix*, so neither matched anything: the run id was preceded by an
    apostrophe and the fixed title carried a trailing apostrophe and an
    encoded space. Every fixture a flow seeded stayed on the device for the
    rest of the shard.

    Nothing about that is visible in the failure: a flow that asserts an
    empty list fails because of the *previous* flow's data, and a flow that
    asserts its own task fails because another flow's task is in the way. The
    nightly run of 2026-10-20 failed 43 flows across all four shards for
    exactly this reason, while the same flows passed in the smoke suite —
    fewer flows, less data, and the failures attributed to the product.

    The property that matters, and what the test below asserts: after
    decoding, every prefix must be a real prefix of a title the app can
    hold, and the run id must be one of them.
    """
    if mode == "off":
        return
    if not deps.device_alive(serial):
        return
    if mode == "all":
        cmd = "am start -a android.intent.action.VIEW -d todo-debug://seed?reset=content"
    else:
        encoded = urllib.parse.quote(f"{run_id},Buy milk")
        cmd = f"am start -a android.intent.action.VIEW -d todo-debug://seed?cleanup={encoded}"
    deps.adb_on(serial, "shell", cmd)
    time.sleep(1)