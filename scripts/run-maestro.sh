#!/bin/bash
# Delegates to the `run-maestro` entry point (emulator.maestro._cli:main).
# Requires `pip install -e scripts/src/` to have been run, so that the
# `run-maestro` command is on PATH.
exec run-maestro "$@"
