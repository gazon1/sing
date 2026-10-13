#!/bin/sh
# Delegates to the `ensure-emulator` entry point (emulator._cli:main).
# Requires `pip install -e scripts/src/` to have been run, so that the
# `ensure-emulator` command is on PATH.
exec ensure-emulator "$@"
