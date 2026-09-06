# ==============================================================================
# 🤖 ANDROID DEVICE / LOGS / TEST
# ==============================================================================

[group('device')]
[doc('List connected adb devices')]
[working-directory(justfile_directory())]
devices:
    #!/bin/bash
    set -euo pipefail
    adb devices -l

[group('device')]
[doc('Tail logcat for Singularity + AndroidRuntime on SERIAL')]
[working-directory(justfile_directory())]
logs serial="" lines="200":
    #!/bin/bash
    set -euo pipefail
    if [ -z "{{serial}}" ]; then
        echo "ERR: pass serial=… or set ADB_SERIAL"
        exit 1
    fi
    adb -s {{serial}} logcat -v time | grep --line-buffered -iE "singularity|AndroidRuntime" | tail -n {{lines}}

[group('device')]
[doc('Run instrumentation tests on SERIAL')]
[working-directory(justfile_directory())]
test serial="":
    #!/bin/bash
    set -euo pipefail
    if [ -z "{{serial}}" ]; then
        echo "ERR: pass serial=… or set ADB_SERIAL"
        exit 1
    fi
    ./gradlew :androidApp:connectedDebugAndroidTest \
        -Pandroid.testInstrumentationRunnerArguments.device={{serial}}

[group('device')]
[doc('Run Android JVM unit tests (no device needed)')]
[working-directory(justfile_directory())]
test-unit:
    #!/bin/bash
    set -euo pipefail
    ./gradlew :androidApp:testDebugUnitTest
