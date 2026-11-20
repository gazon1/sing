# ==============================================================================
# 🤖 ANDROID — store screenshots
# Recipes available as android::<recipe>.
# ==============================================================================

[group('android')]
[doc('Capture the store screenshots: build+install the APK, run the capture flows, check the raw PNGs (flow=, serial=, apk=)')]
[working-directory(justfile_directory())]
store-capture flow="" serial="" apk="":
    #!/usr/bin/env bash
    set -euo pipefail
    # The command is a script in this tree, not a console script: the
    # `emulator` package is an editable install that belongs to whichever
    # checkout installed it last, and a capture taken against another
    # checkout's flows is a capture of something else.
    args=()
    if [ -n {{ quote(flow) }} ]; then args+=(--flow {{ quote(flow) }}); fi
    if [ -n {{ quote(serial) }} ]; then args+=(--serial {{ quote(serial) }}); fi
    if [ -n {{ quote(apk) }} ]; then args+=(--apk {{ quote(apk) }}); fi
    python3 scripts/store-capture.py "${args[@]+"${args[@]}"}"
