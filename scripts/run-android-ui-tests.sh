#!/bin/bash
# Thin wrapper: delegates to the Python version (run-android-ui-tests.py).
# Keeping the .sh extension preserves existing call-sites while the logic
# lives in a testable Python module.
exec "$(dirname "$0")/run-android-ui-tests.py" "$@"
