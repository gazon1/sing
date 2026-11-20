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

# The directories are positional: just only reads a `name=value` argument as
# an override for a *variable*, so `raw_dir=…` lands in the first positional
# slot with the whole `raw_dir=…` string as its value.
[group('android')]
[doc('Render the store screenshots from the raw captures: the compositions, the Play check, the manifest and the preview (raw_dir, out_dir)')]
[working-directory(justfile_directory())]
store-render raw_dir="" out_dir="":
    #!/usr/bin/env bash
    set -euo pipefail
    # The command is a script in this tree, for the same reason as the
    # capture command: it renders the store copy of *this* checkout's
    # templates, not of another checkout's.
    args=()
    if [ -n {{ quote(raw_dir) }} ]; then args+=(--raw-dir {{ quote(raw_dir) }}); fi
    if [ -n {{ quote(out_dir) }} ]; then args+=(--out-dir {{ quote(out_dir) }}); fi
    python3 scripts/store-render.py "${args[@]+"${args[@]}"}"
