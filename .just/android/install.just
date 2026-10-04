# ==============================================================================
# 🤖 ANDROID INSTALL / RUN
# ==============================================================================

[group('install')]
[doc('Install debug APK on SERIAL')]
[working-directory(justfile_directory())]
install serial="":
    #!/bin/bash
    set -euo pipefail
    if [ -z "{{serial}}" ]; then
        echo "ERR: pass serial=… or set ADB_SERIAL"
        exit 1
    fi
    ./gw :androidApp:installDebug
    adb -s {{serial}} install -r "${PWD}/androidApp/build/outputs/apk/debug/androidApp-debug.apk"

[group('install')]
[doc('assembleDebug + install + launch MainActivity on SERIAL')]
[working-directory(justfile_directory())]
run serial="":
    #!/bin/bash
    set -euo pipefail
    if [ -z "{{serial}}" ]; then
        echo "ERR: pass serial=… or set ADB_SERIAL"
        exit 1
    fi
    ./gw :androidApp:assembleDebug
    adb -s {{serial}} install -r "${PWD}/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
    adb -s {{serial}} shell am start -n com.singularity.todo/.MainActivity

[group('install')]
[doc('Uninstall the app on SERIAL')]
[working-directory(justfile_directory())]
uninstall serial="":
    #!/bin/bash
    set -euo pipefail
    if [ -z "{{serial}}" ]; then
        echo "ERR: pass serial=… or set ADB_SERIAL"
        exit 1
    fi
    adb -s {{serial}} uninstall com.singularity.todo

[group('install')]
[doc('Force-stop the app on SERIAL')]
[working-directory(justfile_directory())]
force-stop serial="":
    #!/bin/bash
    set -euo pipefail
    if [ -z "{{serial}}" ]; then
        echo "ERR: pass serial=… or set ADB_SERIAL"
        exit 1
    fi
    adb -s {{serial}} shell am force-stop com.singularity.todo
