"""maestro._provenance — APK SHA tracking and git SHA helpers."""
from __future__ import annotations

from typing import TYPE_CHECKING

from emulator.maestro._types import APP_ID, ROOT

if TYPE_CHECKING:  # pragma: no cover — import cycle guard, annotations only
    from emulator.maestro._types import DeviceDeps


def apk_sha_from_device(serial: str, deps: "DeviceDeps") -> str | None:
    """Read the +g<sha> suffix from versionName on the device."""
    out = deps.adb_on(serial, "shell", "dumpsys", "package", APP_ID, timeout=10) or ""
    for line in out.splitlines():
        if "versionName=" in line:
            version = line.split("versionName=", 1)[1].split()[0].strip()
            if "+" in version:
                sha = version.split("+", 1)[1].strip()
                return sha.lstrip("g")
    return None


def git_sha(deps: "DeviceDeps") -> str:
    cp = deps.try_run(["git", "-C", str(ROOT), "rev-parse", "--short=12", "HEAD"])
    return cp.stdout.strip() if cp and cp.returncode == 0 else "unknown"