#!/usr/bin/env python3
"""Ensure a usable Android device is available; print its serial on stdout.

Delegates to the `ensure-emulator` entry point (emulator._cli:main).
After `pip install -e scripts/src/` the `ensure-emulator` command is available
directly.  This file remains as a thin invocation shim for CI call-sites
that invoke the Python file directly.
"""
from __future__ import annotations

import sys

if __name__ == "__main__":
    from emulator._cli import main as _main
    raise SystemExit(_main())
