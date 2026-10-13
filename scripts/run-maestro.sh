#!/bin/bash
# Thin wrapper: delegates to the Python version (run-maestro.py).
# Keeping the .sh extension preserves existing call-sites while the logic
# lives in a testable Python module.
exec "$(dirname "$0")/run-maestro.py" "$@"
