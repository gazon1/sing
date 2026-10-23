#!/usr/bin/env bash
# probe-packaged-tool-server.sh — determine HOW the MCP tool server can be launched
# from a packaged desktop build.
#
# WHY THIS IS A REPOSITORY ARTIFACT
#
# The question "can the tool server be started from the desktop app's own
# classpath?" has more than one plausible answer, and they lead to completely
# different implementations:
#
#   A. The packaged launcher's classpath contains the shared application classes.
#      Then no second artifact is needed: the app hands the agent a command that
#      runs the tool server's main class from that same classpath, and the MCP
#      code lives in shared/src/jvmMain with :mcp-server reduced to a thin main().
#
#   B. The tool server is shipped as its own artifact and packaged into the
#      distribution by a Gradle task, located relative to the installation root.
#
# Guessing wrong is expensive: option A implemented against a build that turns
# out to be B produces a launch command that only fails after packaging, on a
# user's machine. So the probe runs first, and its output is the input to the
# decision. The result belongs in the ADR.
#
# WHAT IT REPORTS
#
#   1. Where the packaged application image is, and what its launcher puts on
#      the classpath.
#   2. Which classes that classpath actually carries — read out of the jars
#      themselves, which is a fact rather than an inference.
#   3. Whether a given main class can be started from that classpath.
#   4. Where the bundled JRE and the application jars live.
#   5. Whether the tool server's own distributable (the :mcp-server fat jar)
#      exists and starts — the route option B depends on.
#
# USAGE
#
#   scripts/probe-packaged-tool-server.sh [--app-image DIR] [--build]
#
#   --app-image DIR   Use an existing app image instead of searching for one.
#   --build           Build the distribution first (slow: jpackage).
#
# Without --app-image and without --build, the script searches the usual
# Compose Desktop output directories and fails with a clear message if it
# finds nothing.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

APP_IMAGE=""
DO_BUILD="no"
MCP_MAIN_CLASS="com.singularity.todo.mcp.MainKt"
DESKTOP_MAIN_CLASS="com.singularity.todo.MainKt"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --app-image) APP_IMAGE="$2"; shift 2 ;;
        --build) DO_BUILD="yes"; shift ;;
        -h|--help) sed -n '2,50p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

say() { printf '\n=== %s ===\n' "$1"; }

# ── 1. Locate the packaged application image ──────────────────────────────────
#
# A Compose Desktop native distribution unpacks to
#   <target>/<packageName>/{bin,lib/app,lib/runtime}
# and the same tree appears under the Debian package's payload.

find_app_image() {
    local roots=(
        "desktopApp/build/compose/binaries/main/deb"
        "desktopApp/build/compose/binaries/main/rpm"
        "desktopApp/build/compose/binaries/main/msi"
        "desktopApp/build/compose/binaries/main/dmg"
        "desktopApp/build/compose/binaries/main/app"
    )
    local root candidate
    for root in "${roots[@]}"; do
        [[ -d "$root" ]] || continue
        # The image is the directory that has lib/app inside it.
        while IFS= read -r candidate; do
            if [[ -d "$candidate/lib/app" ]]; then
                printf '%s\n' "$candidate"
                return 0
            fi
        done < <(find "$root" -maxdepth 4 -type d -name app -path "*/lib/*" 2>/dev/null | sed 's#/lib/app$##')
    done
    return 1
}

if [[ "$DO_BUILD" == "yes" ]]; then
    say "Building the packaged distribution"
    ./gw :desktopApp:packageDeb || {
        echo "packaging failed — cannot probe a build that does not exist" >&2
        exit 1
    }
fi

# The Debian package is the shipped artefact, and jpackage leaves only the
# package behind — the exploded image it was built from is not kept. So the
# package is unpacked rather than searched for: what is probed is exactly what
# a user would install.
if [[ -z "$APP_IMAGE" ]]; then
    say "Locating the packaged application image"
    APP_IMAGE="$(find_app_image || true)"
    if [[ -z "$APP_IMAGE" ]]; then
        DEB="$(find desktopApp/build/compose/binaries/main/deb -maxdepth 1 -name '*.deb' 2>/dev/null | head -1 || true)"
        if [[ -n "$DEB" ]]; then
            echo "package: $DEB — unpacking it"
            UNPACK_DIR="$(mktemp -d)"
            dpkg-deb -x "$DEB" "$UNPACK_DIR"
            APP_IMAGE="$(find "$UNPACK_DIR" -maxdepth 5 -type d -name app -path "*/lib/*" 2>/dev/null | sed 's#/lib/app$##' | head -1 || true)"
            if [[ -z "$APP_IMAGE" ]]; then
                echo "the package does not contain a lib/app image" >&2
                exit 1
            fi
            echo "unpacked to: $APP_IMAGE"
        fi
    fi
    if [[ -z "$APP_IMAGE" ]]; then
        cat >&2 <<'EOF'
No packaged application image found.

Build one first, then re-run:

    scripts/probe-packaged-tool-server.sh --build

or point the probe at an existing image:

    scripts/probe-packaged-tool-server.sh --app-image /opt/singularity-todo
EOF
        exit 1
    fi
fi

say "Application image"
echo "path: $APP_IMAGE"

LAUNCHER=""
if [[ -d "$APP_IMAGE/bin" ]]; then
    LAUNCHER="$(find "$APP_IMAGE/bin" -maxdepth 1 -type f -name '*' ! -name '*.exe' ! -name '*.bat' | head -1 || true)"
fi
[[ -n "$LAUNCHER" ]] && echo "launcher: $LAUNCHER" || echo "launcher: (none found)"

# ── 2. What does the launcher put on the classpath? ───────────────────────────
#
# The Compose launcher is a shell script that assembles CLASSPATH and execs the
# bundled JRE. Reading it is more truthful than guessing a layout.

say "Classpath as the packaged launcher assembles it"
CP=""
if [[ -n "$LAUNCHER" && -f "$LAUNCHER" ]]; then
    grep -nE 'CLASSPATH=' "$LAUNCHER" | head -5 || true
    CP="$(find "$APP_IMAGE/lib/app" -maxdepth 1 -name '*.jar' -printf '%p:' 2>/dev/null || true)"
    echo "reconstructed: $CP"
else
    CP="$(find "$APP_IMAGE/lib/app" -maxdepth 1 -name '*.jar' -printf '%p:' 2>/dev/null || true)"
    echo "reconstructed: $CP"
fi

# ── 3. The bundled JRE ───────────────────────────────────────────────────────

say "Bundled JRE"
JAVA_BIN=""
for candidate in \
    "$APP_IMAGE/lib/runtime/bin/java" \
    "$APP_IMAGE/runtime/bin/java" \
    "$APP_IMAGE/../runtime/bin/java"
do
    if [[ -x "$candidate" ]]; then JAVA_BIN="$candidate"; break; fi
done
if [[ -z "$JAVA_BIN" ]]; then
    JAVA_BIN="$(command -v java || true)"
    echo "no bundled JRE found; falling back to: $JAVA_BIN"
else
    echo "java: $JAVA_BIN"
fi
[[ -n "$JAVA_BIN" ]] || { echo "no java at all — cannot probe" >&2; exit 1; }

say "Application jars in the image"
find "$APP_IMAGE/lib/app" -maxdepth 1 -name '*.jar' -printf '%f\n' 2>/dev/null | sort || echo "(none)"
APP_JAR="$(find "$APP_IMAGE/lib/app" -maxdepth 1 -name '*.jar' 2>/dev/null | head -1 || true)"
echo "app jar: ${APP_JAR:-<none>}"

# ── 4. Which classes does that classpath actually carry? ──────────────────────
#
# Reading the jars is a fact; starting a class and watching it fail is an
# inference. The jar listing answers the question that decides the mechanism:
# does the packaged classpath carry the tool server's main class, or only the
# shared application classes?

say "Classes carried by the packaged classpath"
jar_has_class() {
    local class_path="$1" pattern="$2" jar
    while IFS= read -r jar; do
        [[ -n "$jar" ]] || continue
        if unzip -l "$jar" "$class_path" 2>/dev/null | grep -q "$pattern"; then
            printf '%s' "$jar"
            return 0
        fi
    done < <(find "$APP_IMAGE/lib/app" -maxdepth 1 -name '*.jar' 2>/dev/null | sort)
    return 1
}

report_class() {
    local label="$1" class_path="$2"
    local jar
    if jar="$(jar_has_class "$class_path" "$class_path")"; then
        printf '  %-28s PRESENT in %s\n' "$label" "$(basename "$jar")"
    else
        printf '  %-28s ABSENT\n' "$label"
    fi
}

report_class "tool server main"        "com/singularity/todo/mcp/MainKt.class"
report_class "desktop app main"        "com/singularity/todo/MainKt.class"
report_class "shared activity events"  "com/singularity/todo/core/llm/AgentEvent.class"
report_class "shared tool catalogue"   "com/singularity/todo/feature/ai/McpToolCatalog.class"

# ── 5. Can a given main class be started from this classpath? ─────────────────
#
# The distinction that matters is "the class is not there" versus "the class
# started and then did something else". The JVM says the first thing itself, in
# a fixed wording, on stderr — so that wording is the discriminator.
#
# stdin is closed rather than inherited: a server that starts correctly and
# waits for input would otherwise be reported as a failure, which is how a
# probe that decides a shipping mechanism can quietly decide it wrong.

probe_class() {
    local label="$1" class="$2" out
    out="$(mktemp)"
    set +e
    timeout 60 "$JAVA_BIN" -cp "$CP" "$class" --probe </dev/null >"$out" 2>&1
    local code=$?
    set -e
    if grep -qE 'Could not find or load main class|ClassNotFoundException' "$out"; then
        echo "$label: NOT ON THE CLASSPATH (exit $code)"
        sed 's/^/    /' "$out" | head -3
        rm -f "$out"
        return 1
    fi
    echo "$label: STARTED (exit $code)"
    sed 's/^/    /' "$out" | head -3
    rm -f "$out"
    return 0
}

say "Can the tool server's main class start from the packaged classpath?"
probe_class "tool server main ($MCP_MAIN_CLASS)" "$MCP_MAIN_CLASS" || true

say "Can the desktop app's own main class start from the packaged classpath?"
probe_class "desktop main ($DESKTOP_MAIN_CLASS)" "$DESKTOP_MAIN_CLASS" || true

# ── 6. The tool server's own distributable ───────────────────────────────────
#
# The other route: the tool server ships as its own artifact and is copied into
# the packaged distribution. Whether that artifact exists and starts is the fact
# that decides whether the route is open at all.

say "The tool server's own artifact"
MCP_JAR="mcp-server/build/libs/mcp-server.jar"
if [[ -f "$MCP_JAR" ]]; then
    echo "fat jar: $MCP_JAR ($(du -h "$MCP_JAR" | cut -f1))"
    out="$(mktemp)"
    set +e
    timeout 60 "$JAVA_BIN" -jar "$MCP_JAR" --probe </dev/null >"$out" 2>&1
    code=$?
    set -e
    if grep -qE 'Could not find or load main class|ClassNotFoundException' "$out"; then
        echo "fat jar main: NOT STARTABLE (exit $code)"
    else
        echo "fat jar main: STARTED (exit $code)"
    fi
    sed 's/^/    /' "$out" | head -3
    rm -f "$out"
else
    echo "fat jar: $MCP_JAR — NOT BUILT (run ./gw :mcp-server:jar)"
fi

# ── 7. Verdict ───────────────────────────────────────────────────────────────

say "Verdict"
cat <<EOF
Read the class listing and the start probes together:

  tool server main PRESENT + STARTED
      -> the packaged classpath carries everything. Option A: the MCP code lives
         in shared/src/jvmMain, :mcp-server is a thin main(), and the agent gets
         a command that runs from the app's own classpath. One runtime, one
         copy of the classes.

  tool server main ABSENT + desktop main STARTED
      -> the classpath is complete for what the app ships, and the only missing
         piece is the module boundary. Two ways to close it, and the probe
         below says which one is open:
           * move the MCP code into shared/src/jvmMain (option A) — no extra
             bytes in the distribution;
           * package the tool server's own fat jar into the distribution
             (option B) — a second copy of the runtime in the download.

  desktop main NOT ON THE CLASSPATH
      -> the packaged classpath cannot start a second main class at all.
         Option B, and the artifact must be launched with its own classpath.

The fat-jar probe above decides between the two routes in the middle case.
Record the outcome in
docs/decisions/2026-10-23-acp-client-and-agent-activity.md before implementing
the delivery mechanism. Exactly ONE mechanism is implemented on the strength of
this probe.
EOF
