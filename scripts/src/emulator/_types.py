"""emulator._types — shared constants, exit codes, and runtime configuration."""
from __future__ import annotations

import os
import shlex
from pathlib import Path

from pydantic import BaseModel, Field

# ── Exit codes ─────────────────────────────────────────────────────────────────
EXIT_OK = 0
EXIT_NOT_READY = 1
EXIT_CRASHED = 2
EXIT_TIMEOUT = 3
EXIT_LOCK = 4

# ── Runtime configuration ───────────────────────────────────────────────────────
# Mutable module-level state; updated by CLI before any operation runs.
# Patch fields directly in tests or via CLI options.
#
# UPPER_CASE aliases are properties on the Config so the emulator module public
# API (`emulator.AVD`, `emulator.BOOT_TIMEOUT`, …) is explicit and testable.


class Config(BaseModel):
    """Runtime configuration — fields map to environment variables and CLI overrides.

    ``model_validate`` is called once at module load; CLI overrides mutate the
    same singleton. ``validate_default`` ensures env-driven defaults are re-read
    from os.environ at import time, not at class-definition time.
    """

    avd: str = Field(default_factory=lambda: os.environ.get("AVD", "Medium_Phone"))
    pinned: str = Field(default_factory=lambda: os.environ.get("SERIAL", ""))
    boot_timeout: int = Field(
        default_factory=lambda: int(os.environ.get("EMULATOR_TIMEOUT", "300"))
    )
    lock_timeout: float = Field(
        default_factory=lambda: float(os.environ.get("EMULATOR_LOCK_TIMEOUT", "60"))
    )
    extra_flags: list[str] = Field(
        default_factory=lambda: shlex.split(os.environ.get("EMULATOR_EXTRA_FLAGS", ""))
    )
    log_file: Path = Field(
        default_factory=lambda: Path(os.environ.get("EMULATOR_LOG", "/tmp/singularity-emulator.log"))
    )
    lock_file: Path = Field(
        default_factory=lambda: Path(
            os.environ.get("TMPDIR", "/tmp")
        ) / "ensure-emulator.lock"
    )
    android_avd_home: Path = Field(
        default_factory=lambda: Path(
            os.environ.get("ANDROID_AVD_HOME", str(Path.home() / ".android" / "avd"))
        )
    )
    verbose: bool = False

    # ── UPPER_CASE aliases (properties for the emulator module public API) ───────

    @property
    def AVD(self) -> str:
        return self.avd

    @AVD.setter
    def AVD(self, v: str) -> None:
        self.avd = v

    @property
    def LOCK_FILE(self) -> Path:
        return self.lock_file

    @LOCK_FILE.setter
    def LOCK_FILE(self, v: Path) -> None:
        self.lock_file = v

    @property
    def EXTRA_FLAGS(self) -> list[str]:
        return self.extra_flags

    @EXTRA_FLAGS.setter
    def EXTRA_FLAGS(self, v: list[str]) -> None:
        self.extra_flags = v

    @property
    def BOOT_TIMEOUT(self) -> int:
        return self.boot_timeout

    @BOOT_TIMEOUT.setter
    def BOOT_TIMEOUT(self, v: int) -> None:
        self.boot_timeout = v

    @property
    def LOCK_TIMEOUT(self) -> float:
        return self.lock_timeout

    @LOCK_TIMEOUT.setter
    def LOCK_TIMEOUT(self, v: float) -> None:
        self.lock_timeout = v


# Singleton used by the whole package.
cfg = Config()
