#!/usr/bin/env python3
"""Run Maestro UI flows against a connected Android device or emulator.

Delegates to the `run-maestro` entry point (emulator.maestro._cli:main).
After `pip install -e scripts/src/` the `run-maestro` command is available
directly.  This file remains as a thin invocation shim for CI call-sites
that invoke the Python file directly.
"""
from __future__ import annotations

import sys

if __name__ == "__main__":
    # Entry point is the preferred invocation after pip install.
    from emulator.maestro._cli import main as _main
    raise SystemExit(_main())
