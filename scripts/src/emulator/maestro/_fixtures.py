"""maestro._fixtures — per-run fixture setup and teardown."""
from __future__ import annotations

import time
import urllib.parse


def cleanup_run_fixtures(
    serial: str, run_id: str, mode: str, deps: "DeviceDeps",
) -> None:
    """Send cleanup deeplink to the app. Best-effort."""
    if mode == "off":
        return
    if not deps.device_alive(serial):
        return
    encoded = urllib.parse.quote(f"'{run_id},Buy%20milk'")
    if mode == "all":
        cmd = "am start -a android.intent.action.VIEW -d todo-debug://seed?reset=content"
    else:
        cmd = f"am start -a android.intent.action.VIEW -d todo-debug://seed?cleanup={encoded}"
    deps.adb(serial, "shell", cmd)
    time.sleep(1)
