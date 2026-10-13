"""maestro._fixtures — per-run fixture setup and teardown."""
from __future__ import annotations

import time
import urllib.parse
from typing import TYPE_CHECKING

if TYPE_CHECKING:  # pragma: no cover — import cycle guard, annotations only
    from emulator.maestro._types import DeviceDeps


def cleanup_run_fixtures(
    serial: str, run_id: str, mode: str, deps: "DeviceDeps",
) -> None:
    """Send cleanup deeplink to the app. Best-effort.

    Uses ``deps.adb_on`` so ``-s <serial>`` is present; see
    ``emulator/maestro/_artifacts.py`` for the failure this prevents.
    """
    if mode == "off":
        return
    if not deps.device_alive(serial):
        return
    encoded = urllib.parse.quote(f"'{run_id},Buy%20milk'")
    if mode == "all":
        cmd = "am start -a android.intent.action.VIEW -d todo-debug://seed?reset=content"
    else:
        cmd = f"am start -a android.intent.action.VIEW -d todo-debug://seed?cleanup={encoded}"
    deps.adb_on(serial, "shell", cmd)
    time.sleep(1)