"""emulator._types — shared constants, exit codes, and runtime configuration."""
from __future__ import annotations

import os
import shlex
from dataclasses import dataclass, field
from pathlib import Path

# ── Exit codes ─────────────────────────────────────────────────────────────────
EXIT_OK = 0
EXIT_NOT_READY = 1
EXIT_CRASHED = 2
EXIT_TIMEOUT = 3
EXIT_LOCK = 4

# ── Runtime configuration ───────────────────────────────────────────────────────
# Mutable module-level state; updated by CLI before any operation runs.
# Use configure() to set, or patch directly in tests.


@dataclass
class Config:
    avd: str = os.environ.get("AVD", "Medium_Phone")
    pinned: str = os.environ.get("SERIAL", "")
    boot_timeout: int = int(os.environ.get("EMULATOR_TIMEOUT", "300"))
    extra_flags: list[str] = field(
        default_factory=lambda: shlex.split(os.environ.get("EMULATOR_EXTRA_FLAGS", ""))
    )
    log_file: Path = Path(os.environ.get("EMULATOR_LOG", "/tmp/singularity-emulator.log"))
    lock_file: Path = Path(os.environ.get("TMPDIR", "/tmp")) / "ensure-emulator.lock"
    android_avd_home: Path = Path(
        os.environ.get("ANDROID_AVD_HOME", Path.home() / ".android" / "avd")
    )
    verbose: bool = False


# Singleton used by the whole package.
cfg = Config()
