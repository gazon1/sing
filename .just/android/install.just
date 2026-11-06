# ==============================================================================
# 🤖 ANDROID INSTALL / RUN
# ==============================================================================

[group('install')]
[doc('Install debug APK on SERIAL (the debug build starts signed in)')]
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
[doc('assembleDebug + install + launch MainActivity on SERIAL (starts signed in)')]
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
[doc('Build APK, push it to device storage and install on SERIAL')]
[working-directory(justfile_directory())]
push-install serial="" variant="debug":
    #!/bin/bash
    set -euo pipefail
    SERIAL="{{serial}}"
    if [ -z "$SERIAL" ]; then
        SERIAL="${ADB_SERIAL:-}"
    fi
    if [ -z "$SERIAL" ]; then
        echo "ERR: pass serial=… or set ADB_SERIAL"
        exit 1
    fi
    case "{{variant}}" in
        debug)   APK="${PWD}/androidApp/build/outputs/apk/debug/androidApp-debug.apk" ;;
        release) APK="${PWD}/androidApp/build/outputs/apk/release/androidApp-release.apk" ;;
        *)
            echo "❌ Unknown variant: {{variant}} (use debug | release)"
            exit 1
            ;;
    esac
    just android::build {{variant}}
    REMOTE="/sdcard/Download/$(basename "$APK")"
    cleanup() { adb -s "$SERIAL" shell rm -f "$REMOTE" >/dev/null 2>&1 || true; }
    trap cleanup EXIT
    adb -s "$SERIAL" push "$APK" "$REMOTE"
    INSTALL_OUT=$(adb -s "$SERIAL" shell pm install -r "$REMOTE" 2>&1) || true
    echo "$INSTALL_OUT"
    if ! echo "$INSTALL_OUT" | grep -qi "Success"; then
        echo "❌ install failed on $SERIAL"
        exit 1
    fi
    adb -s "$SERIAL" shell am start -n com.singularity.todo/.MainActivity
    echo "✅ installed and launched $REMOTE on $SERIAL"

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
