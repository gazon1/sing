"""maestro._subprocess — subprocess helpers."""
from __future__ import annotations

import subprocess


def run(args: list[str], **kwargs) -> subprocess.CompletedProcess:
    """Run and return CompletedProcess; stdout/stderr captured by default."""
    return subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kwargs)


def try_run(args: list[str], **kwargs) -> subprocess.CompletedProcess | None:
    """Run, return None on OSError; output always captured."""
    try:
        return run(args, **kwargs)
    except OSError:
        return None
